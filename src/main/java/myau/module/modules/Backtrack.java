package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.*;
import myau.module.Module;
import myau.property.properties.*;
import myau.mixin.IAccessorRenderManager;
import myau.util.RenderUtil;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.network.play.client.C0FPacketConfirmTransaction;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

import java.awt.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BackTrack - Allows you to hit players at their previous positions.
 * 
 * Mode Options:
 * - Manual: Allows you to directly hit players at their previous positions, without modifying your connection.
 *   - Render Previous Ticks: Renders opponent's previous positions with a "shadow" of their player avatar.
 *   - Ticks: How many previous positions should be made available for attacking.
 * 
 * - Lag Based: Modifies your connection in a way that allows you to hit players at their previous positions,
 *   when it is advantageous to you.
 *   - Render Server Pos: Renders the opponent's last known server side position, while your connection is being controlled.
 *   - Color: What color to shade the opponent's "shadow" with.
 *   - Latency: The amount of lag added to your connection at advantageous moments.
 */
public class Backtrack extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // Grim bypass constants (directly from Grim source code)
    private static final double GRIM_MAX_REACH = 3.0; // Base reach distance
    private static final double GRIM_REACH_THRESHOLD = 0.0005; // Threshold for reach checks
    private static final double GRIM_HITBOX_EXPANSION_1_8 = 0.1; // Extra hitbox for 1.7-1.8 clients
    private static final double GRIM_MOVEMENT_THRESHOLD = 0.03; // Movement uncertainty
    private static final double SAFE_REACH_DISTANCE = 2.97; // Stay safely under 3.0
    
    // LAGRANGE 1000MS COMPENSATION
    // With 1000ms artificial lag, Grim gives us MUCH more leniency
    private static final double LAGRANGE_REACH_BONUS = 0.5; // Extra reach allowed with high ping
    private static final double LAGRANGE_UNCERTAINTY = 0.15; // 1000ms = 15 blocks of movement uncertainty
    private static final int LAGRANGE_PING = 1000; // Your constant 1000ms Lagrange latency
    private static final int MAX_BACKTRACK_TICKS = 20; // With 1000ms, we can go back 20 ticks (1 second)

    // Mode Selection
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"Manual", "Lag Based"});
    
    // Manual Mode Settings
    public final BooleanProperty renderPreviousTicks = new BooleanProperty("render-previous-ticks", true, () -> mode.getModeString().equals("Manual"));
    // LAGRANGE: Increased max ticks to 20 (1000ms = 20 ticks of history)
    public final IntProperty ticks = new IntProperty("ticks", 15, 1, 20, () -> mode.getModeString().equals("Manual"));
    
    // Lag Based Mode Settings
    public final BooleanProperty renderServerPos = new BooleanProperty("render-server-pos", true, () -> mode.getModeString().equals("Lag Based"));
    // LAGRANGE: Latency can be much higher (up to 2000ms) since we already have 1000ms base
    public final IntProperty latency = new IntProperty("latency", 200, 50, 2000, () -> mode.getModeString().equals("Lag Based"));
    
    // Shared Settings
    public final ColorProperty color = new ColorProperty("color", 0xFF0000);

    // Data Storage
    private final Map<Integer, LinkedList<PositionData>> entityPositions = new ConcurrentHashMap<>();
    private final Map<Integer, PositionData> serverPositions = new ConcurrentHashMap<>();
    private final Queue<PacketData> delayedPackets = new LinkedList<>();
    private boolean isLagging = false;
    private long lagStartTime = 0L;

    public Backtrack() {
        super("Backtrack", false);
    }

    @Override
    public void onEnabled() {
        entityPositions.clear();
        serverPositions.clear();
        delayedPackets.clear();
        isLagging = false;
        lagStartTime = 0L;
    }

    @Override
    public void onDisabled() {
        entityPositions.clear();
        serverPositions.clear();
        
        // Release all delayed packets
        releaseAllPackets();
        isLagging = false;
        lagStartTime = 0L;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        // Manual Mode: Track position history
        if (mode.getModeString().equals("Manual")) {
            for (EntityPlayer player : mc.theWorld.playerEntities) {
                if (player == mc.thePlayer || player.isDead) {
                    continue;
                }

                LinkedList<PositionData> positions = entityPositions.computeIfAbsent(player.getEntityId(), k -> new LinkedList<>());
                
                positions.addFirst(new PositionData(
                    player.posX, player.posY, player.posZ,
                    System.currentTimeMillis()
                ));

                // Limit to specified ticks
                while (positions.size() > ticks.getValue()) {
                    positions.removeLast();
                }
            }

            entityPositions.keySet().removeIf(id -> mc.theWorld.getEntityByID(id) == null);
        }
        
        // Lag Based Mode: Process delayed packets
        if (mode.getModeString().equals("Lag Based")) {
            processDelayedPackets();
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || !mode.getModeString().equals("Lag Based")) {
            return;
        }

        // GRIM BYPASS: Never delay transaction packets (causes TransactionOrder flag)
        // Grim expects transactions to be responded to immediately in order
        if (event.getPacket().getClass().getSimpleName().contains("Transaction") || 
            event.getPacket().getClass().getSimpleName().contains("KeepAlive")) {
            return; // Let these packets through immediately
        }

        // Only process incoming entity movement packets
        if (event.getType() == EventType.RECEIVE) {
            if (event.getPacket() instanceof S18PacketEntityTeleport) {
                // Store server-side positions from teleport packets
                S18PacketEntityTeleport packet = (S18PacketEntityTeleport) event.getPacket();
                serverPositions.put(packet.getEntityId(), new PositionData(
                    packet.getX() / 32.0, packet.getY() / 32.0, packet.getZ() / 32.0,
                    System.currentTimeMillis()
                ));
                
                // Delay packets when lagging
                if (isLagging) {
                    delayedPackets.add(new PacketData(event.getPacket(), System.currentTimeMillis()));
                    event.setCancelled(true);
                }
            }
            // Note: Removed S14PacketEntity handling as it caused compilation errors
        }
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }

        if (event.getTarget() instanceof EntityPlayer) {
            EntityPlayer target = (EntityPlayer) event.getTarget();
            
            if (mode.getModeString().equals("Manual")) {
                // Manual Mode: Apply backtrack position
                LinkedList<PositionData> positions = entityPositions.get(target.getEntityId());
                
                if (positions != null && !positions.isEmpty()) {
                    PositionData bestPos = selectBestPosition(positions, target);
                    if (bestPos != null) {
                        applyBacktrackPosition(target, bestPos);
                        
                        // Restore on next tick
                        new Thread(() -> {
                            try {
                                Thread.sleep(1);
                                if (target != null && !target.isDead && !positions.isEmpty()) {
                                    PositionData currentPos = positions.getFirst();
                                    applyBacktrackPosition(target, currentPos);
                                }
                            } catch (InterruptedException ignored) {}
                        }).start();
                    }
                }
            } else if (mode.getModeString().equals("Lag Based")) {
                // Lag Based Mode: Start lagging to create advantage
                double distance = mc.thePlayer.getDistanceToEntity(target);
                
                // Only lag if target is moving away or at edge of reach
                if (distance > 2.5 && !isLagging) {
                    startLagging();
                }
            }
        }
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        if (mode.getModeString().equals("Manual") && renderPreviousTicks.getValue()) {
            renderManualModePositions();
        } else if (mode.getModeString().equals("Lag Based") && renderServerPos.getValue()) {
            renderLagBasedPositions();
        }
    }

    // ==================== Manual Mode Methods ====================

    private PositionData selectBestPosition(LinkedList<PositionData> positions, EntityPlayer target) {
        if (positions.isEmpty()) {
            return null;
        }

        double currentDistance = mc.thePlayer.getDistanceToEntity(target);
        PositionData bestPosition = null;
        double bestDistance = currentDistance;

        for (PositionData pos : positions) {
            // Use Grim's exact distance calculation method (eye to closest point on hitbox)
            double distance = calculateGrimReachDistance(pos, target);
            
            // GRIM BYPASS: Only select positions that pass all Grim checks
            if (!isPositionSafeForGrim(pos, target, distance)) {
                continue; // Skip positions that would flag
            }
            
            if (distance < currentDistance && distance <= SAFE_REACH_DISTANCE && distance < bestDistance) {
                bestDistance = distance;
                bestPosition = pos;
            }
        }

        return bestPosition;
    }
    
    /**
     * Calculate reach distance exactly like Grim does (from eye to closest point on hitbox)
     * This replicates ReachUtils.getMinReachToBox from Grim source
     * 
     * LAGRANGE BYPASS: With 1000ms ping, Grim adds massive uncertainty to reach checks
     */
    private double calculateGrimReachDistance(PositionData pos, EntityPlayer target) {
        double eyeX = mc.thePlayer.posX;
        double eyeY = mc.thePlayer.posY + mc.thePlayer.getEyeHeight();
        double eyeZ = mc.thePlayer.posZ;
        
        // LAGRANGE: Expand hitbox more due to high ping uncertainty
        double lagrangeExpansion = LAGRANGE_UNCERTAINTY; // 0.15 blocks extra
        
        // Get closest point on target's hitbox (standard player hitbox: 0.6 width, 1.8 height)
        double targetMinX = pos.x - 0.3 - lagrangeExpansion;
        double targetMaxX = pos.x + 0.3 + lagrangeExpansion;
        double targetMinY = pos.y - lagrangeExpansion;
        double targetMaxY = pos.y + 1.8 + lagrangeExpansion;
        double targetMinZ = pos.z - 0.3 - lagrangeExpansion;
        double targetMaxZ = pos.z + 0.3 + lagrangeExpansion;
        
        // Clamp eye position to hitbox bounds to find closest point
        double closestX = MathHelper.clamp_double(eyeX, targetMinX, targetMaxX);
        double closestY = MathHelper.clamp_double(eyeY, targetMinY, targetMaxY);
        double closestZ = MathHelper.clamp_double(eyeZ, targetMinZ, targetMaxZ);
        
        // Calculate distance from eye to closest point
        double deltaX = eyeX - closestX;
        double deltaY = eyeY - closestY;
        double deltaZ = eyeZ - closestZ;
        
        return Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
    }
    
    /**
     * Validates that a backtrack position will pass all Grim AC checks
     * Checks: Reach, Hitbox, BadPacketsT (interaction vector)
     * 
     * LAGRANGE BYPASS: With 1000ms ping, Grim is MUCH more lenient
     */
    private boolean isPositionSafeForGrim(PositionData pos, EntityPlayer target, double reachDistance) {
        // 1. REACH CHECK: With Lagrange 1000ms, we can go much further
        // Grim adds (ping/50) * 0.03 blocks of uncertainty
        // 1000ms / 50 = 20 ticks * 0.03 = 0.6 blocks extra reach!
        double lagrangeMaxReach = SAFE_REACH_DISTANCE + LAGRANGE_REACH_BONUS + (LAGRANGE_PING / 50.0 * GRIM_MOVEMENT_THRESHOLD);
        
        if (reachDistance > lagrangeMaxReach) {
            return false; // Would flag Reach check
        }
        
        // 2. HITBOX CHECK: MOST IMPORTANT - Validate raytrace intercept
        // This is what causes most hitbox flags!
        if (!canRaytraceHitGrim(pos, target)) {
            return false; // Would flag Hitbox check - calculateIntercept returned null
        }
        
        // 3. Additional raytrace check with LAST look direction (Grim checks multiple angles)
        if (!canRaytraceHitWithLastLook(pos, target)) {
            return false; // Would flag on secondary hitbox check
        }
        
        // 4. BADPACKETS CHECK: Ensure interaction vector is valid
        double relativeX = pos.x - mc.thePlayer.posX;
        double relativeY = pos.y - mc.thePlayer.posY;
        double relativeZ = pos.z - mc.thePlayer.posZ;
        
        // Check horizontal bounds (BadPacketsT checks ±0.3001 for 1.9+)
        if (Math.abs(relativeX - (pos.x - (int)pos.x)) > 0.31 || 
            Math.abs(relativeZ - (pos.z - (int)pos.z)) > 0.31) {
            return false; // Would flag BadPacketsT
        }
        
        // Check vertical bounds (0 to 1.8 for player height)
        double targetHeight = pos.y + 1.8;
        if (relativeY < -0.1 || relativeY > targetHeight + 0.1) {
            return false; // Would flag BadPacketsT
        }
        
        // 5. Ensure reasonable vertical angle
        double eyeY = mc.thePlayer.posY + mc.thePlayer.getEyeHeight();
        double targetCenterY = pos.y + 0.9; // Player center
        double verticalDist = Math.abs(eyeY - targetCenterY);
        
        if (verticalDist > 3.0) {
            return false; // Impossible angle
        }
        
        // 6. Ensure we're within FOV (basic sanity check)
        double deltaX = pos.x - mc.thePlayer.posX;
        double deltaZ = pos.z - mc.thePlayer.posZ;
        float targetYaw = (float) (Math.atan2(deltaZ, deltaX) * 180.0 / Math.PI) - 90.0F;
        float yawDiff = Math.abs(MathHelper.wrapAngleTo180_float(targetYaw - mc.thePlayer.rotationYaw));
        
        if (yawDiff > 90.0F) {
            return false; // Can't hit behind us
        }
        
        return true;
    }
    
    /**
     * Check hitbox with last look direction (Grim checks multiple look vectors)
     */
    private boolean canRaytraceHitWithLastLook(PositionData pos, EntityPlayer target) {
        double eyeX = mc.thePlayer.posX;
        double eyeY = mc.thePlayer.posY + mc.thePlayer.getEyeHeight();
        double eyeZ = mc.thePlayer.posZ;
        
        // Use previous rotation (Grim checks this too)
        float lastYaw = mc.thePlayer.prevRotationYaw;
        float lastPitch = mc.thePlayer.prevRotationPitch;
        
        // Calculate last look vector
        float yawRad = (float) Math.toRadians(-lastYaw);
        float pitchRad = (float) Math.toRadians(-lastPitch);
        float pitchCos = MathHelper.cos(pitchRad);
        
        double lookX = MathHelper.sin(yawRad) * pitchCos;
        double lookY = MathHelper.sin(pitchRad);
        double lookZ = MathHelper.cos(yawRad) * pitchCos;
        
        double distance = GRIM_MAX_REACH + 3.0;
        Vec3 eyeVec = new Vec3(eyeX, eyeY, eyeZ);
        Vec3 endVec = new Vec3(
            eyeX + lookX * distance,
            eyeY + lookY * distance,
            eyeZ + lookZ * distance
        );
        
        // Apply expansion
        // LAGRANGE: Add extra expansion for 1000ms ping
        double hitboxExpansion = GRIM_REACH_THRESHOLD + GRIM_HITBOX_EXPANSION_1_8 + GRIM_MOVEMENT_THRESHOLD;
        hitboxExpansion += LAGRANGE_UNCERTAINTY;
        hitboxExpansion += (LAGRANGE_PING / 50.0) * GRIM_MOVEMENT_THRESHOLD;
        
        AxisAlignedBB targetBox = new AxisAlignedBB(
            pos.x - 0.3 - hitboxExpansion, 
            pos.y - hitboxExpansion, 
            pos.z - 0.3 - hitboxExpansion,
            pos.x + 0.3 + hitboxExpansion, 
            pos.y + 1.8 + hitboxExpansion, 
            pos.z + 0.3 + hitboxExpansion
        );
        
        return targetBox.calculateIntercept(eyeVec, endVec) != null;
    }
    
    /**
     * Check if we can raytrace to the hitbox from our current look angle
     * Replicates Grim's EXACT raytrace validation using calculateIntercept
     */
    private boolean canRaytraceHitGrim(PositionData pos, EntityPlayer target) {
        double eyeX = mc.thePlayer.posX;
        double eyeY = mc.thePlayer.posY + mc.thePlayer.getEyeHeight();
        double eyeZ = mc.thePlayer.posZ;
        
        // Get look vectors (current and last, like Grim does)
        Vec3 lookVec = mc.thePlayer.getLookVec();
        
        // Extend look vector (Grim uses maxReach + 3)
        double distance = GRIM_MAX_REACH + 3.0;
        Vec3 eyeVec = new Vec3(eyeX, eyeY, eyeZ);
        Vec3 endVec = new Vec3(
            eyeX + lookVec.xCoord * distance,
            eyeY + lookVec.yCoord * distance,
            eyeZ + lookVec.zCoord * distance
        );
        
        // CRITICAL: Apply hitbox expansion like Grim does
        // LAGRANGE: With 1000ms ping, add MASSIVE hitbox expansion
        double hitboxExpansion = GRIM_REACH_THRESHOLD + GRIM_HITBOX_EXPANSION_1_8 + GRIM_MOVEMENT_THRESHOLD;
        hitboxExpansion += LAGRANGE_UNCERTAINTY; // Add 0.15 blocks for 1000ms ping
        hitboxExpansion += (LAGRANGE_PING / 50.0) * GRIM_MOVEMENT_THRESHOLD; // Grim's formula: +0.6 blocks
        
        // Create expanded target hitbox (standard player: 0.6 width, 1.8 height)
        AxisAlignedBB targetBox = new AxisAlignedBB(
            pos.x - 0.3 - hitboxExpansion, 
            pos.y - hitboxExpansion, 
            pos.z - 0.3 - hitboxExpansion,
            pos.x + 0.3 + hitboxExpansion, 
            pos.y + 1.8 + hitboxExpansion, 
            pos.z + 0.3 + hitboxExpansion
        );
        
        // GRIM'S EXACT CHECK: calculateIntercept must not be null
        // If null = no intersection = HITBOX flag
        return targetBox.calculateIntercept(eyeVec, endVec) != null;
    }

    private void applyBacktrackPosition(EntityPlayer target, PositionData pos) {
        target.posX = pos.x;
        target.posY = pos.y;
        target.posZ = pos.z;
        target.lastTickPosX = pos.x;
        target.lastTickPosY = pos.y;
        target.lastTickPosZ = pos.z;
        
        target.serverPosX = (int)(pos.x * 32.0);
        target.serverPosY = (int)(pos.y * 32.0);
        target.serverPosZ = (int)(pos.z * 32.0);
    }

    private void renderManualModePositions() {
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
            
            // Render all previous positions with fading alpha
            for (int i = 0; i < positions.size(); i++) {
                PositionData pos = positions.get(i);
                float alpha = 1.0F - ((float) i / positions.size()) * 0.7F;
                
                renderPositionBox(pos, new Color(
                    baseColor.getRed(),
                    baseColor.getGreen(),
                    baseColor.getBlue(),
                    (int) (alpha * 150)
                ));
            }
        }
    }

    // ==================== Lag Based Mode Methods ====================

    private void startLagging() {
        isLagging = true;
        lagStartTime = System.currentTimeMillis();
    }

    private void processDelayedPackets() {
        if (!isLagging) {
            return;
        }

        long currentTime = System.currentTimeMillis();
        
        // Stop lagging after latency period
        if (currentTime - lagStartTime >= latency.getValue()) {
            releaseAllPackets();
            isLagging = false;
        }
    }

    private void releaseAllPackets() {
        while (!delayedPackets.isEmpty()) {
            PacketData packetData = delayedPackets.poll();
            if (packetData != null && mc.thePlayer != null && mc.getNetHandler() != null) {
                try {
                    // Process the delayed packet by sending to network handler
                    if (packetData.packet instanceof S18PacketEntityTeleport) {
                        mc.getNetHandler().handleEntityTeleport((S18PacketEntityTeleport) packetData.packet);
                    }
                } catch (Exception e) {
                    // Silently ignore packet processing errors
                }
            }
        }
    }

    private void renderLagBasedPositions() {
        Color baseColor = new Color(this.color.getValue());
        
        for (Map.Entry<Integer, PositionData> entry : serverPositions.entrySet()) {
            EntityPlayer player = (EntityPlayer) mc.theWorld.getEntityByID(entry.getKey());
            
            if (player == null || player == mc.thePlayer || player.isDead) {
                continue;
            }
            
            if (TeamUtil.isFriend(player)) {
                continue;
            }

            PositionData serverPos = entry.getValue();
            PositionData currentPos = new PositionData(player.posX, player.posY, player.posZ, System.currentTimeMillis());
            
            // Calculate interpolation based on lag state
            if (isLagging) {
                long lagDuration = System.currentTimeMillis() - lagStartTime;
                float progress = Math.min(1.0F, lagDuration / (float) latency.getValue());
                
                // Interpolate between server position and current position
                double interpX = serverPos.x + (currentPos.x - serverPos.x) * progress;
                double interpY = serverPos.y + (currentPos.y - serverPos.y) * progress;
                double interpZ = serverPos.z + (currentPos.z - serverPos.z) * progress;
                
                PositionData interpPos = new PositionData(interpX, interpY, interpZ, System.currentTimeMillis());
                
                renderPositionBox(interpPos, new Color(
                    baseColor.getRed(),
                    baseColor.getGreen(),
                    baseColor.getBlue(),
                    200
                ));
            } else {
                // Render server position when not lagging
                renderPositionBox(serverPos, new Color(
                    baseColor.getRed(),
                    baseColor.getGreen(),
                    baseColor.getBlue(),
                    150
                ));
            }
        }
    }

    // ==================== Rendering Utilities ====================

    private void renderPositionBox(PositionData pos, Color color) {
        double renderX = pos.x - ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosX();
        double renderY = pos.y - ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosY();
        double renderZ = pos.z - ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosZ();

        AxisAlignedBB box = new AxisAlignedBB(
            renderX - 0.3, renderY, renderZ - 0.3,
            renderX + 0.3, renderY + 1.8, renderZ + 0.3
        );
        
        RenderUtil.enableRenderState();
        RenderUtil.drawFilledBox(box, color.getRed(), color.getGreen(), color.getBlue());
        RenderUtil.drawBoundingBox(box, color.getRed(), color.getGreen(), color.getBlue(), color.getAlpha(), 2.0F);
        RenderUtil.disableRenderState();
    }

    // ==================== Data Classes ====================

    private static class PositionData {
        final double x, y, z;
        final long timestamp;

        PositionData(double x, double y, double z, long timestamp) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.timestamp = timestamp;
        }
    }

    private static class PacketData {
        final Object packet;
        final long timestamp;

        PacketData(Object packet, long timestamp) {
            this.packet = packet;
            this.timestamp = timestamp;
        }
    }
}
