package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.*;
import myau.module.Module;
import myau.property.properties.*;
import myau.mixin.IAccessorRenderManager;
import myau.util.RenderUtil;
import myau.util.RotationUtil;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.util.AxisAlignedBB;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class Backtrack extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    // Core Settings
    public final IntProperty ticks = new IntProperty("ticks", 6, 1, 15);
    public final BooleanProperty renderPreviousTicks = new BooleanProperty("render-previous-ticks", true);
    public final ColorProperty color = new ColorProperty("color", 0xFF0000);
    
    // Ultimate Closet Bypass Settings
    public final BooleanProperty grimBypass = new BooleanProperty("grim-bypass", true);
    public final BooleanProperty closetMode = new BooleanProperty("closet-mode", true, () -> this.grimBypass.getValue());
    public final FloatProperty maxReachDistance = new FloatProperty("max-reach-distance", 2.95F, 2.8F, 3.0F, () -> this.grimBypass.getValue());
    public final BooleanProperty smartPositionSelect = new BooleanProperty("smart-position-select", true, () -> this.grimBypass.getValue());
    public final BooleanProperty respectPing = new BooleanProperty("respect-ping", true, () -> this.grimBypass.getValue());
    public final IntProperty maxBacktrackTime = new IntProperty("max-backtrack-time", 150, 50, 300, () -> this.grimBypass.getValue() && this.respectPing.getValue());
    public final BooleanProperty onlyOnAdvantage = new BooleanProperty("only-on-advantage", true, () -> this.grimBypass.getValue());
    public final FloatProperty minAdvantage = new FloatProperty("min-advantage", 0.5F, 0.2F, 1.0F, () -> this.grimBypass.getValue() && this.onlyOnAdvantage.getValue());
    public final BooleanProperty hitboxCheck = new BooleanProperty("hitbox-check", true, () -> this.grimBypass.getValue());
    public final BooleanProperty raytraceValidation = new BooleanProperty("raytrace-validation", true, () -> this.grimBypass.getValue());
    public final IntProperty cooldownHits = new IntProperty("cooldown-hits", 3, 1, 10, () -> this.grimBypass.getValue());

    private final Map<Integer, LinkedList<PositionData>> entityPositions = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastHitTimes = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> consecutiveHits = new ConcurrentHashMap<>();
    private int playerPing = 0;
    
    // Closet mode state (ultimate bypass)
    private boolean closetActive = false;
    private EntityPlayer closetTarget = null;
    private PositionData closetPosition = null;
    private long lastBacktrackUse = 0L;

    public Backtrack() {
        super("Backtrack", false);
    }

    @Override
    public void onEnabled() {
        entityPositions.clear();
        lastHitTimes.clear();
        consecutiveHits.clear();
        updatePlayerPing();
        closetActive = false;
        closetTarget = null;
        closetPosition = null;
        lastBacktrackUse = 0L;
    }

    @Override
    public void onDisabled() {
        entityPositions.clear();
        lastHitTimes.clear();
        consecutiveHits.clear();
        
        // Reset closet target if active
        if (closetActive && closetTarget != null && closetPosition != null) {
            restoreEntityPosition(closetTarget, closetPosition);
        }
        closetActive = false;
        closetTarget = null;
        closetPosition = null;
    }
    
    private void updatePlayerPing() {
        if (mc.getNetHandler() != null && mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID()) != null) {
            playerPing = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID()).getResponseTime();
        }
    }


    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        // Update ping for Grim bypass calculations
        if (this.grimBypass.getValue() && mc.thePlayer.ticksExisted % 20 == 0) {
            updatePlayerPing();
        }

        long currentTime = System.currentTimeMillis();

        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == mc.thePlayer || player.isDead) {
                continue;
            }

            LinkedList<PositionData> positions = entityPositions.computeIfAbsent(player.getEntityId(), k -> new LinkedList<>());
            
            positions.addFirst(new PositionData(
                player.posX, player.posY, player.posZ,
                player.rotationYaw, player.rotationPitch,
                player.rotationYawHead, player.limbSwing, player.limbSwingAmount,
                currentTime
            ));

            // Grim bypass: Limit backtrack history based on ping and settings
            int maxTicks = this.ticks.getValue();
            if (this.grimBypass.getValue() && this.respectPing.getValue()) {
                // Calculate safe backtrack window based on ping
                int pingBasedLimit = Math.min((int) Math.ceil(playerPing / 50.0), maxTicks);
                maxTicks = Math.max(3, pingBasedLimit);
            }

            while (positions.size() > maxTicks) {
                positions.removeLast();
            }
            
            // Grim bypass: Remove positions older than max backtrack time
            if (this.grimBypass.getValue() && this.respectPing.getValue()) {
                positions.removeIf(pos -> currentTime - pos.timestamp > this.maxBacktrackTime.getValue());
            }
        }

        entityPositions.keySet().removeIf(id -> mc.theWorld.getEntityByID(id) == null);
        
        // Clean old hit times
        lastHitTimes.entrySet().removeIf(entry -> currentTime - entry.getValue() > 1000);
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        if (this.renderPreviousTicks.getValue()) {
            renderHistoricalPositions();
        }
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }

        if (event.getTarget() instanceof EntityPlayer) {
            EntityPlayer target = (EntityPlayer) event.getTarget();
            LinkedList<PositionData> positions = entityPositions.get(target.getEntityId());
            
            if (positions != null && !positions.isEmpty()) {
                PositionData backtrackPos = selectOptimalPosition(positions, target);
                if (backtrackPos != null && shouldUseBacktrack(target, backtrackPos)) {
                    PositionData originalPos = positions.getFirst();
                    
                    // Apply backtrack position
                    applyBacktrackPosition(target, backtrackPos);
                    
                    // Closet mode: Restore immediately after attack
                    if (this.grimBypass.getValue() && this.closetMode.getValue()) {
                        // Restore on next tick (after attack packet sent)
                        new Thread(() -> {
                            try {
                                Thread.sleep(1); // 1ms delay
                                if (target != null && !target.isDead) {
                                    restoreEntityPosition(target, originalPos);
                                    closetActive = false;
                                    closetTarget = null;
                                    closetPosition = null;
                                }
                            } catch (InterruptedException ignored) {}
                        }).start();
                    }
                }
            }
        }
    }


    private PositionData selectOptimalPosition(LinkedList<PositionData> positions, EntityPlayer target) {
        if (positions.isEmpty()) {
            return null;
        }

        double currentDistance = mc.thePlayer.getDistanceToEntity(target);
        double maxReach = this.grimBypass.getValue() ? this.maxReachDistance.getValue() : 3.5;
        
        PositionData bestPosition = null;
        double bestDistance = currentDistance;
        long currentTime = System.currentTimeMillis();
        
        // Ultimate Closet Mode: Ultra-safe position selection
        if (this.grimBypass.getValue() && this.closetMode.getValue() && this.smartPositionSelect.getValue()) {
            // Prioritize positions that are:
            // 1. Within safe reach (max 3.0)
            // 2. Not too old (respect ping)
            // 3. Provide clear advantage
            
            for (PositionData pos : positions) {
                // Skip positions that are too old (shorter window for closet)
                if (this.respectPing.getValue() && currentTime - pos.timestamp > this.maxBacktrackTime.getValue()) {
                    continue;
                }
                
                // Calculate exact distance to hitbox center (like Grim does)
                double backtrackDistance = calculateExactDistance(pos, target);
                
                // Closet mode: NEVER exceed 2.95 (stay well under 3.0)
                if (backtrackDistance > maxReach) {
                    continue;
                }
                
                // Raytrace validation: Ensure we can actually hit the hitbox
                if (this.raytraceValidation.getValue() && !canRaytraceHit(pos, target)) {
                    continue;
                }
                
                // Hitbox check: Validate we're aiming at valid part
                if (this.hitboxCheck.getValue() && !isValidHitboxTarget(pos, target)) {
                    continue;
                }
                
                // Closet: Only use backtrack if advantage is significant
                if (this.onlyOnAdvantage.getValue()) {
                    double advantage = currentDistance - backtrackDistance;
                    if (advantage < this.minAdvantage.getValue()) {
                        continue; // Not enough advantage
                    }
                }
                
                // Check if this position is better
                if (backtrackDistance < currentDistance && backtrackDistance < bestDistance) {
                    // Prefer more recent positions if distance is similar
                    if (bestPosition != null) {
                        double distanceDiff = Math.abs(backtrackDistance - bestDistance);
                        // Closer time preference for closet mode
                        if (distanceDiff < 0.1 && pos.timestamp > bestPosition.timestamp) {
                            bestDistance = backtrackDistance;
                            bestPosition = pos;
                        } else if (backtrackDistance < bestDistance) {
                            bestDistance = backtrackDistance;
                            bestPosition = pos;
                        }
                    } else {
                        bestDistance = backtrackDistance;
                        bestPosition = pos;
                    }
                }
            }
        } else {
            // Simple selection without Grim bypass
            for (PositionData pos : positions) {
                double backtrackDistance = Math.sqrt(mc.thePlayer.getDistanceSq(pos.x, pos.y, pos.z));
                
                if (backtrackDistance < currentDistance && backtrackDistance <= maxReach) {
                    if (backtrackDistance < bestDistance) {
                        bestDistance = backtrackDistance;
                        bestPosition = pos;
                    }
                }
            }
        }
        
        return bestPosition;
    }

    private boolean shouldUseBacktrack(EntityPlayer target, PositionData backtrackPos) {
        double currentDist = mc.thePlayer.getDistanceToEntity(target);
        double backtrackDist = calculateExactDistance(backtrackPos, target);
        double maxReach = this.grimBypass.getValue() ? this.maxReachDistance.getValue() : 3.5;
        
        if (backtrackDist >= currentDist) {
            return false;
        }
        
        // Closet mode: NEVER exceed max reach (default 2.95)
        if (backtrackDist > maxReach) {
            return false;
        }
        
        if (this.grimBypass.getValue() && this.closetMode.getValue()) {
            // Check hit cooldown pattern (closet mode)
            Integer hits = consecutiveHits.getOrDefault(target.getEntityId(), 0);
            if (hits >= this.cooldownHits.getValue()) {
                // Force cooldown every N hits
                Long lastHitTime = lastHitTimes.get(target.getEntityId());
                if (lastHitTime != null && System.currentTimeMillis() - lastHitTime < 300) {
                    return false; // Skip this hit
                } else {
                    // Reset counter after cooldown
                    consecutiveHits.put(target.getEntityId(), 0);
                }
            }
            
            // Global backtrack cooldown (don't spam backtrack)
            if (System.currentTimeMillis() - lastBacktrackUse < 50) {
                return false;
            }
            
            // Check vertical distance
            double eyeHeight = mc.thePlayer.getEyeHeight();
            double playerEyeY = mc.thePlayer.posY + eyeHeight;
            double targetCenterY = backtrackPos.y + (target.height / 2.0);
            double verticalDist = Math.abs(playerEyeY - targetCenterY);
            
            if (verticalDist > 2.0) {
                return false;
            }
            
            // Only use if position is recent
            if (this.respectPing.getValue()) {
                long timeSincePosition = System.currentTimeMillis() - backtrackPos.timestamp;
                if (timeSincePosition > this.maxBacktrackTime.getValue()) {
                    return false;
                }
            }
            
            // Check if advantage is significant
            if (this.onlyOnAdvantage.getValue()) {
                double advantage = currentDist - backtrackDist;
                if (advantage < this.minAdvantage.getValue()) {
                    return false;
                }
            }
            
            // Raytrace validation
            if (this.raytraceValidation.getValue() && !canRaytraceHit(backtrackPos, target)) {
                return false;
            }
            
            // Hitbox validation
            if (this.hitboxCheck.getValue() && !isValidHitboxTarget(backtrackPos, target)) {
                return false;
            }
        }
        
        return true;
    }

    private void applyBacktrackPosition(EntityPlayer target, PositionData pos) {
        // Closet mode: Only apply client-side, never send to server
        if (this.grimBypass.getValue() && this.closetMode.getValue()) {
            // Store original position for restoration
            if (!closetActive || closetTarget != target) {
                closetTarget = target;
                closetPosition = new PositionData(
                    target.posX, target.posY, target.posZ,
                    target.rotationYaw, target.rotationPitch,
                    target.rotationYawHead, target.limbSwing, target.limbSwingAmount,
                    System.currentTimeMillis()
                );
                closetActive = true;
            }
            
            // Apply backtrack position ONLY client-side
            target.posX = pos.x;
            target.posY = pos.y;
            target.posZ = pos.z;
            target.lastTickPosX = pos.x;
            target.lastTickPosY = pos.y;
            target.lastTickPosZ = pos.z;
            target.rotationYaw = pos.yaw;
            target.rotationPitch = pos.pitch;
            target.rotationYawHead = pos.headYaw;
            
            // Update interpolation
            target.serverPosX = (int)(pos.x * 32.0);
            target.serverPosY = (int)(pos.y * 32.0);
            target.serverPosZ = (int)(pos.z * 32.0);
            
        } else {
            // Direct position change for non-closet mode
            target.setPosition(pos.x, pos.y, pos.z);
            target.rotationYaw = pos.yaw;
            target.rotationPitch = pos.pitch;
            target.rotationYawHead = pos.headYaw;
        }
        
        // Track this hit
        lastHitTimes.put(target.getEntityId(), System.currentTimeMillis());
        lastBacktrackUse = System.currentTimeMillis();
        
        // Update consecutive hit counter
        int hits = consecutiveHits.getOrDefault(target.getEntityId(), 0);
        consecutiveHits.put(target.getEntityId(), hits + 1);
    }
    
    private void restoreEntityPosition(EntityPlayer target, PositionData originalPos) {
        // Restore entity to original position
        target.posX = originalPos.x;
        target.posY = originalPos.y;
        target.posZ = originalPos.z;
        target.lastTickPosX = originalPos.x;
        target.lastTickPosY = originalPos.y;
        target.lastTickPosZ = originalPos.z;
        target.rotationYaw = originalPos.yaw;
        target.rotationPitch = originalPos.pitch;
        target.rotationYawHead = originalPos.headYaw;
        
        target.serverPosX = (int)(originalPos.x * 32.0);
        target.serverPosY = (int)(originalPos.y * 32.0);
        target.serverPosZ = (int)(originalPos.z * 32.0);
    }
    
    // Calculate exact distance like Grim does (from eye to hitbox)
    private double calculateExactDistance(PositionData pos, EntityPlayer target) {
        double eyeX = mc.thePlayer.posX;
        double eyeY = mc.thePlayer.posY + mc.thePlayer.getEyeHeight();
        double eyeZ = mc.thePlayer.posZ;
        
        // Get closest point on hitbox
        double closestX = Math.max(pos.x - 0.3, Math.min(eyeX, pos.x + 0.3));
        double closestY = Math.max(pos.y, Math.min(eyeY, pos.y + target.height));
        double closestZ = Math.max(pos.z - 0.3, Math.min(eyeZ, pos.z + 0.3));
        
        // Calculate distance from eye to closest point
        double deltaX = eyeX - closestX;
        double deltaY = eyeY - closestY;
        double deltaZ = eyeZ - closestZ;
        
        return Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
    }
    
    // Raytrace validation (mimics Grim's raytrace check)
    private boolean canRaytraceHit(PositionData pos, EntityPlayer target) {
        double eyeX = mc.thePlayer.posX;
        double eyeY = mc.thePlayer.posY + mc.thePlayer.getEyeHeight();
        double eyeZ = mc.thePlayer.posZ;
        
        // Calculate look vector
        float yaw = mc.thePlayer.rotationYaw;
        float pitch = mc.thePlayer.rotationPitch;
        
        double yawRad = Math.toRadians(-yaw);
        double pitchRad = Math.toRadians(-pitch);
        
        double vecX = Math.sin(yawRad) * Math.cos(pitchRad);
        double vecY = Math.sin(pitchRad);
        double vecZ = Math.cos(yawRad) * Math.cos(pitchRad);
        
        // Extend vector to reach distance
        double reachDist = this.maxReachDistance.getValue();
        double targetX = eyeX + vecX * reachDist;
        double targetY = eyeY + vecY * reachDist;
        double targetZ = eyeZ + vecZ * reachDist;
        
        // Check if ray intersects with entity hitbox
        double minX = pos.x - 0.3;
        double minY = pos.y;
        double minZ = pos.z - 0.3;
        double maxX = pos.x + 0.3;
        double maxY = pos.y + target.height;
        double maxZ = pos.z + 0.3;
        
        // Simple AABB raytrace
        return rayIntersectsAABB(eyeX, eyeY, eyeZ, targetX, targetY, targetZ, minX, minY, minZ, maxX, maxY, maxZ);
    }
    
    // Check if hitbox target is valid
    private boolean isValidHitboxTarget(PositionData pos, EntityPlayer target) {
        // Check vertical distance (Grim checks this)
        double eyeY = mc.thePlayer.posY + mc.thePlayer.getEyeHeight();
        double targetCenterY = pos.y + (target.height / 2.0);
        double verticalDist = Math.abs(eyeY - targetCenterY);
        
        // Reject if too high/low
        if (verticalDist > 2.5) {
            return false;
        }
        
        // Check if we're looking somewhat towards target
        double deltaX = pos.x - mc.thePlayer.posX;
        double deltaZ = pos.z - mc.thePlayer.posZ;
        
        float targetYaw = (float) (Math.atan2(deltaZ, deltaX) * 180.0 / Math.PI) - 90.0F;
        float yawDiff = Math.abs(net.minecraft.util.MathHelper.wrapAngleTo180_float(targetYaw - mc.thePlayer.rotationYaw));
        
        // Allow up to 90 degree difference (generous for closet mode)
        return yawDiff < 90.0F;
    }
    
    // Simple AABB-Ray intersection
    private boolean rayIntersectsAABB(double x1, double y1, double z1, double x2, double y2, double z2,
                                      double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double dirX = x2 - x1;
        double dirY = y2 - y1;
        double dirZ = z2 - z1;
        
        double tMin = 0.0;
        double tMax = 1.0;
        
        // X slab
        if (Math.abs(dirX) > 0.0001) {
            double t1 = (minX - x1) / dirX;
            double t2 = (maxX - x1) / dirX;
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
        }
        
        // Y slab
        if (Math.abs(dirY) > 0.0001) {
            double t1 = (minY - y1) / dirY;
            double t2 = (maxY - y1) / dirY;
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
        }
        
        // Z slab
        if (Math.abs(dirZ) > 0.0001) {
            double t1 = (minZ - z1) / dirZ;
            double t2 = (maxZ - z1) / dirZ;
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
        }
        
        return tMax >= tMin && tMax >= 0.0;
    }

    private void renderHistoricalPositions() {
        for (Map.Entry<Integer, LinkedList<PositionData>> entry : entityPositions.entrySet()) {
            EntityPlayer player = (EntityPlayer) mc.theWorld.getEntityByID(entry.getKey());
            
            if (player == null || player == mc.thePlayer || player.isDead) {
                continue;
            }
            
            if (TeamUtil.isFriend(player)) {
                continue;
            }

            LinkedList<PositionData> positions = entry.getValue();
            if (positions.isEmpty()) {
                continue;
            }

            Color baseColor = new Color(this.color.getValue());
            
            for (int i = 0; i < positions.size(); i++) {
                PositionData pos = positions.get(i);
                float alpha = 1.0F - ((float) i / positions.size()) * 0.8F;
                
                renderPlayerAtPosition(player, pos, new Color(
                    baseColor.getRed(),
                    baseColor.getGreen(),
                    baseColor.getBlue(),
                    (int) (alpha * 100)
                ));
            }
        }
    }

    private void renderPlayerAtPosition(EntityPlayer player, PositionData pos, Color color) {
        double renderX = pos.x - ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosX();
        double renderY = pos.y - ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosY();
        double renderZ = pos.z - ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosZ();

        AxisAlignedBB box = new AxisAlignedBB(
            renderX - 0.3, renderY, renderZ - 0.3,
            renderX + 0.3, renderY + 1.8, renderZ + 0.3
        );
        
        RenderUtil.enableRenderState();
        RenderUtil.drawFilledBox(box, color.getRed(), color.getGreen(), color.getBlue());
        RenderUtil.drawBoundingBox(box, color.getRed(), color.getGreen(), color.getBlue(), color.getAlpha(), 1.5F);
        RenderUtil.disableRenderState();
    }

    private static class PositionData {
        final double x, y, z;
        final float yaw, pitch, headYaw, limbSwing, limbSwingAmount;
        final long timestamp;

        PositionData(double x, double y, double z, float yaw, float pitch, 
                    float headYaw, float limbSwing, float limbSwingAmount, long timestamp) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.headYaw = headYaw;
            this.limbSwing = limbSwing;
            this.limbSwingAmount = limbSwingAmount;
            this.timestamp = timestamp;
        }
    }
}
