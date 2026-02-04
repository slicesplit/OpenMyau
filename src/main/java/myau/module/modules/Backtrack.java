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
    
    // Grim Bypass Settings (Ghost Mode)
    public final BooleanProperty grimBypass = new BooleanProperty("grim-bypass", true);
    public final BooleanProperty ghostMode = new BooleanProperty("ghost-mode", true, () -> this.grimBypass.getValue());
    public final FloatProperty maxReachDistance = new FloatProperty("max-reach-distance", 3.0F, 2.8F, 3.0F, () -> this.grimBypass.getValue());
    public final BooleanProperty smartPositionSelect = new BooleanProperty("smart-position-select", true, () -> this.grimBypass.getValue());
    public final BooleanProperty respectPing = new BooleanProperty("respect-ping", true, () -> this.grimBypass.getValue());
    public final IntProperty maxBacktrackTime = new IntProperty("max-backtrack-time", 200, 100, 500, () -> this.grimBypass.getValue() && this.respectPing.getValue());
    public final BooleanProperty onlyOnAdvantage = new BooleanProperty("only-on-advantage", true, () -> this.grimBypass.getValue());
    public final BooleanProperty smoothTransition = new BooleanProperty("smooth-transition", false, () -> this.grimBypass.getValue() && !this.ghostMode.getValue());

    private final Map<Integer, LinkedList<PositionData>> entityPositions = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastHitTimes = new ConcurrentHashMap<>();
    private int playerPing = 0;
    
    // Ghost mode state
    private boolean ghostActive = false;
    private EntityPlayer ghostTarget = null;
    private PositionData ghostPosition = null;

    public Backtrack() {
        super("Backtrack", false);
    }

    @Override
    public void onEnabled() {
        entityPositions.clear();
        lastHitTimes.clear();
        updatePlayerPing();
        ghostActive = false;
        ghostTarget = null;
        ghostPosition = null;
    }

    @Override
    public void onDisabled() {
        entityPositions.clear();
        lastHitTimes.clear();
        
        // Reset ghost target if active
        if (ghostActive && ghostTarget != null && ghostPosition != null) {
            restoreEntityPosition(ghostTarget, ghostPosition);
        }
        ghostActive = false;
        ghostTarget = null;
        ghostPosition = null;
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
                    
                    // Ghost mode: Restore immediately after attack
                    if (this.grimBypass.getValue() && this.ghostMode.getValue()) {
                        // Restore on next tick (after attack packet sent)
                        new Thread(() -> {
                            try {
                                Thread.sleep(1); // 1ms delay
                                if (target != null && !target.isDead) {
                                    restoreEntityPosition(target, originalPos);
                                    ghostActive = false;
                                    ghostTarget = null;
                                    ghostPosition = null;
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
        
        // Grim bypass: Smart position selection
        if (this.grimBypass.getValue() && this.smartPositionSelect.getValue()) {
            // Prioritize positions that are:
            // 1. Within safe reach (max 3.0)
            // 2. Not too old (respect ping)
            // 3. Provide clear advantage
            
            for (PositionData pos : positions) {
                // Skip positions that are too old
                if (this.respectPing.getValue() && currentTime - pos.timestamp > this.maxBacktrackTime.getValue()) {
                    continue;
                }
                
                double backtrackDistance = Math.sqrt(mc.thePlayer.getDistanceSq(pos.x, pos.y, pos.z));
                
                // Never exceed 3.0 reach for Grim safety
                if (backtrackDistance > maxReach) {
                    continue;
                }
                
                // Grim bypass: Only use backtrack if it provides advantage
                if (this.onlyOnAdvantage.getValue()) {
                    double advantage = currentDistance - backtrackDistance;
                    if (advantage < 0.3) {
                        continue; // Not enough advantage
                    }
                }
                
                // Check if this position is better
                if (backtrackDistance < currentDistance && backtrackDistance < bestDistance) {
                    // Grim bypass: Prefer more recent positions if distance is similar
                    if (bestPosition != null) {
                        double distanceDiff = Math.abs(backtrackDistance - bestDistance);
                        if (distanceDiff < 0.2 && pos.timestamp > bestPosition.timestamp) {
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
        double backtrackDist = Math.sqrt(mc.thePlayer.getDistanceSq(backtrackPos.x, backtrackPos.y, backtrackPos.z));
        double maxReach = this.grimBypass.getValue() ? this.maxReachDistance.getValue() : 3.5;
        
        if (backtrackDist >= currentDist) {
            return false;
        }
        
        // Grim bypass: NEVER exceed 3.0 blocks
        if (backtrackDist > maxReach) {
            return false;
        }
        
        if (this.grimBypass.getValue()) {
            // Check vertical distance (Grim checks this)
            double eyeHeight = mc.thePlayer.getEyeHeight();
            double playerEyeY = mc.thePlayer.posY + eyeHeight;
            double targetCenterY = backtrackPos.y + (target.height / 2.0);
            double verticalDist = Math.abs(playerEyeY - targetCenterY);
            
            if (verticalDist > 2.0) {
                return false;
            }
            
            // Grim bypass: Check for hit cooldown (prevent spam)
            Long lastHitTime = lastHitTimes.get(target.getEntityId());
            if (lastHitTime != null && System.currentTimeMillis() - lastHitTime < 100) {
                return false; // Too soon since last hit
            }
            
            // Grim bypass: Only use if position is recent enough
            if (this.respectPing.getValue()) {
                long timeSincePosition = System.currentTimeMillis() - backtrackPos.timestamp;
                if (timeSincePosition > this.maxBacktrackTime.getValue()) {
                    return false;
                }
            }
            
            // Grim bypass: Check if advantage is significant enough
            if (this.onlyOnAdvantage.getValue()) {
                double advantage = currentDist - backtrackDist;
                if (advantage < 0.3) {
                    return false;
                }
            }
        }
        
        return true;
    }

    private void applyBacktrackPosition(EntityPlayer target, PositionData pos) {
        // Ghost mode: Only apply client-side, never send to server
        if (this.grimBypass.getValue() && this.ghostMode.getValue()) {
            // Store original position for restoration
            if (!ghostActive || ghostTarget != target) {
                ghostTarget = target;
                ghostPosition = new PositionData(
                    target.posX, target.posY, target.posZ,
                    target.rotationYaw, target.rotationPitch,
                    target.rotationYawHead, target.limbSwing, target.limbSwingAmount,
                    System.currentTimeMillis()
                );
                ghostActive = true;
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
            // Smooth transition for non-ghost mode
            if (this.grimBypass.getValue() && this.smoothTransition.getValue()) {
                double smoothFactor = 0.7;
                
                double newX = target.posX + (pos.x - target.posX) * smoothFactor;
                double newY = target.posY + (pos.y - target.posY) * smoothFactor;
                double newZ = target.posZ + (pos.z - target.posZ) * smoothFactor;
                
                target.setPosition(newX, newY, newZ);
            } else {
                target.setPosition(pos.x, pos.y, pos.z);
            }
            
            target.rotationYaw = pos.yaw;
            target.rotationPitch = pos.pitch;
            target.rotationYawHead = pos.headYaw;
        }
        
        // Track this hit
        lastHitTimes.put(target.getEntityId(), System.currentTimeMillis());
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
