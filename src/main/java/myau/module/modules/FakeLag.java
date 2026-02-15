package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;
import myau.event.EventTarget;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.event.types.EventType;
import myau.module.Module;
import myau.module.modules.KillAura;
import myau.property.properties.*;
import myau.Myau;
// Removed unused import: UnifiedPredictionSystem
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.*;
import net.minecraft.util.AxisAlignedBB;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * FakeLag - Premium Distance-Based Lag System
 * 
 * INTELLIGENT DESIGN:
 * - Distance-based activation (only lag when approaching target)
 * - Delay system prevents constant activation
 * - Clean packet queue with proper timing
 * - No fake "priority" system - FIFO is correct
 * - No packet dropping (causes desync)
 * - Integrates with UnifiedPredictionSystem
 * 
 * HOW IT WORKS:
 * 1. When approaching target (distance decreasing)
 * 2. Wait for delay period (prevents spam)
 * 3. Hold packets for X ticks
 * 4. Release all at once (creates teleport effect)
 * 5. Repeat when distance resets
 */
@ModuleInfo(category = ModuleCategory.COMBAT)
public class FakeLag extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    public FakeLag() {
        super("FakeLag", false);
    }
    
    // State machine for clean logic
    private enum FakeLagState {
        IDLE,           // Not active, waiting
        COOLDOWN,       // Waiting after last spoof
        SPOOFING        // Actively holding packets
    }
    
    // Settings
    public final FloatProperty distance = new FloatProperty("distance", 3.5F, 1.0F, 6.0F);
    public final FloatProperty minDistance = new FloatProperty("min-distance", 2.0F, 1.0F, 4.0F);
    public final IntProperty minSpoofTicks = new IntProperty("min-spoof-ticks", 5, 3, 15);
    public final IntProperty maxSpoofTicks = new IntProperty("max-spoof-ticks", 15, 5, 40);
    public final IntProperty cooldownTicks = new IntProperty("cooldown-ticks", 20, 5, 60);
    public final IntProperty maxQueueSize = new IntProperty("max-queue", 30, 10, 100);
    public final BooleanProperty onlyWhenMoving = new BooleanProperty("only-moving", true);
    public final BooleanProperty burstRelease = new BooleanProperty("burst-release", true);
    public final IntProperty releasePerTick = new IntProperty("release-per-tick", 3, 1, 10);
    
    // State tracking with proper separation
    private FakeLagState state = FakeLagState.IDLE;
    private int spoofTicksElapsed = 0;
    private int cooldownTicksElapsed = 0;
    private int releaseTicksElapsed = 0;
    
    // Distance smoothing (anti-jitter)
    private final double[] distanceHistory = new double[10];
    private int distanceHistoryIndex = 0;
    private static final double DISTANCE_DEADZONE = 0.05;
    
    // Packet queue with safety
    private final ConcurrentLinkedQueue<DelayedPacket> packetQueue = new ConcurrentLinkedQueue<>();
    
    /**
     * Simple packet wrapper with timestamp
     */
    private static class DelayedPacket {
        private final Packet<?> packet;
        private final long time;
        
        public DelayedPacket(Packet<?> packet) {
            this.packet = packet;
            this.time = System.currentTimeMillis();
        }
        
        public Packet<?> getPacket() {
            return packet;
        }
        
        public long getTime() {
            return time;
        }
    }
    
    @Override
    public void onEnabled() {
        resetState();
    }
    
    @Override
    public void onDisabled() {
        resetState();
        safeFlushQueue();
    }
    
    /**
     * Reset all state to idle
     */
    private void resetState() {
        state = FakeLagState.IDLE;
        spoofTicksElapsed = 0;
        cooldownTicksElapsed = 0;
        releaseTicksElapsed = 0;
        clearDistanceHistory();
        packetQueue.clear();
    }
    
    /**
     * Clear distance history
     */
    private void clearDistanceHistory() {
        for (int i = 0; i < distanceHistory.length; i++) {
            distanceHistory[i] = 0;
        }
        distanceHistoryIndex = 0;
    }
    
    /**
     * Main tick logic with state machine
     */
    public void tickCheck() {
        if (!isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        
        // Validate target
        Module killAura = Myau.moduleManager.modules.get(KillAura.class);
        if (killAura == null || !killAura.isEnabled()) {
            if (state != FakeLagState.IDLE) {
                transitionToIdle();
            }
            return;
        }
        
        KillAura ka = (KillAura) killAura;
        if (ka.target == null || ka.target.getEntity().isDead) {
            if (state != FakeLagState.IDLE) {
                transitionToIdle();
            }
            return;
        }
        
        // Only spoof when moving (if enabled)
        if (onlyWhenMoving.getValue()) {
            double playerSpeed = Math.sqrt(
                mc.thePlayer.motionX * mc.thePlayer.motionX +
                mc.thePlayer.motionZ * mc.thePlayer.motionZ
            );
            if (playerSpeed < 0.05) {
                if (state == FakeLagState.SPOOFING) {
                    transitionToRelease();
                }
                return;
            }
        }
        
        // Calculate smoothed distance
        double currentDistance = getDistanceToBox(ka.target.getBox());
        addToDistanceHistory(currentDistance);
        double smoothedDistance = getSmoothedDistance();
        double previousDistance = getPreviousSmoothedDistance();
        
        // Calculate closing speed with deadzone
        double closingSpeed = previousDistance - smoothedDistance;
        boolean isApproaching = closingSpeed > DISTANCE_DEADZONE;
        boolean inRange = smoothedDistance >= minDistance.getValue() && 
                         smoothedDistance <= distance.getValue();
        
        // State machine
        switch (state) {
            case IDLE:
                // Transition to spoofing if conditions met
                if (inRange && isApproaching) {
                    transitionToSpoofing();
                }
                break;
                
            case SPOOFING:
                spoofTicksElapsed++;
                
                // Check stop conditions
                boolean minTimeReached = spoofTicksElapsed >= minSpoofTicks.getValue();
                boolean maxTimeReached = spoofTicksElapsed >= maxSpoofTicks.getValue();
                boolean queueFull = packetQueue.size() >= maxQueueSize.getValue();
                boolean outOfRange = smoothedDistance > distance.getValue() + 1.0;
                boolean targetMovedAway = closingSpeed < -DISTANCE_DEADZONE;
                
                // GRIM FIX: Release earlier to prevent massive rubberbanding
                // Limit queue size to 20 packets max (prevents desync)
                if (queueFull || outOfRange || packetQueue.size() > 20) {
                    // Emergency stop
                    transitionToRelease();
                } else if (minTimeReached && (maxTimeReached || targetMovedAway)) {
                    // Normal stop
                    transitionToRelease();
                }
                break;
                
            case COOLDOWN:
                cooldownTicksElapsed++;
                
                // GRIM FIX: Always release packets quickly to reduce rubberband
                // Release 2-3 packets per tick instead of 1
                if (!packetQueue.isEmpty()) {
                    if (burstRelease.getValue()) {
                        releasePacketsGradually();
                    } else {
                        // Fast release: 3 packets per tick
                        int packetsToRelease = Math.min(3, packetQueue.size());
                        for (int i = 0; i < packetsToRelease; i++) {
                            DelayedPacket delayedPacket = packetQueue.poll();
                            if (delayedPacket != null) {
                                mc.thePlayer.sendQueue.addToSendQueue(delayedPacket.getPacket());
                            }
                        }
                    }
                }
                
                // Return to idle after cooldown
                if (cooldownTicksElapsed >= cooldownTicks.getValue() && packetQueue.isEmpty()) {
                    transitionToIdle();
                }
                break;
        }
    }
    
    /**
     * Add distance to smoothing history
     */
    private void addToDistanceHistory(double distance) {
        distanceHistory[distanceHistoryIndex] = distance;
        distanceHistoryIndex = (distanceHistoryIndex + 1) % distanceHistory.length;
    }
    
    /**
     * Get smoothed distance (average of last N samples)
     */
    private double getSmoothedDistance() {
        double sum = 0;
        int count = 0;
        for (double d : distanceHistory) {
            if (d > 0) {
                sum += d;
                count++;
            }
        }
        return count > 0 ? sum / count : 0;
    }
    
    /**
     * Get previous smoothed distance for trend detection
     */
    private double getPreviousSmoothedDistance() {
        int prevIndex = (distanceHistoryIndex - 3 + distanceHistory.length) % distanceHistory.length;
        return distanceHistory[prevIndex] > 0 ? distanceHistory[prevIndex] : getSmoothedDistance();
    }
    
    /**
     * Transition to spoofing state
     */
    private void transitionToSpoofing() {
        state = FakeLagState.SPOOFING;
        spoofTicksElapsed = 0;
    }
    
    /**
     * Transition to release/cooldown state
     */
    private void transitionToRelease() {
        state = FakeLagState.COOLDOWN;
        cooldownTicksElapsed = 0;
        
        if (!burstRelease.getValue()) {
            // Instant flush
            safeFlushQueue();
        }
    }
    
    /**
     * Transition to idle state
     */
    private void transitionToIdle() {
        state = FakeLagState.IDLE;
        spoofTicksElapsed = 0;
        cooldownTicksElapsed = 0;
        safeFlushQueue();
    }
    
    /**
     * Release packets gradually (burst mode)
     */
    private void releasePacketsGradually() {
        int released = 0;
        while (released < releasePerTick.getValue() && !packetQueue.isEmpty()) {
            DelayedPacket delayed = packetQueue.poll();
            if (delayed != null) {
                sendPacketDirect(delayed.getPacket());
                released++;
            }
        }
    }
    
    /**
     * Calculate distance to bounding box
     */
    private double getDistanceToBox(AxisAlignedBB box) {
        double centerX = (box.minX + box.maxX) / 2.0;
        double centerY = (box.minY + box.maxY) / 2.0;
        double centerZ = (box.minZ + box.maxZ) / 2.0;
        
        double dx = mc.thePlayer.posX - centerX;
        double dy = mc.thePlayer.posY - centerY;
        double dz = mc.thePlayer.posZ - centerZ;
        
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
    
    /**
     * Safe flush - controlled packet release
     */
    private void safeFlushQueue() {
        DelayedPacket delayed;
        while ((delayed = packetQueue.poll()) != null) {
            sendPacketDirect(delayed.getPacket());
        }
    }
    
    /**
     * Send packet directly without interception
     */
    private void sendPacketDirect(Packet<?> packet) {
        if (mc.thePlayer != null && mc.thePlayer.sendQueue != null) {
            mc.thePlayer.sendQueue.getNetworkManager().sendPacket(packet);
        }
    }
    
    /**
     * Tick event handler
     */
    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() == EventType.PRE) {
            tickCheck();
        }
    }
    
    /**
     * Handle outbound packets
     */
    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || mc.thePlayer == null) {
            return;
        }
        
        if (event.getType() == EventType.SEND) {
            Packet<?> packet = event.getPacket();
            
            // Only intercept movement packets when spoofing
            if (state == FakeLagState.SPOOFING && isMovementPacket(packet)) {
                // Safety check: don't overflow queue
                if (packetQueue.size() < maxQueueSize.getValue()) {
                    event.setCancelled(true);
                    packetQueue.add(new DelayedPacket(packet));
                }
            }
        }
    }
    
    /**
     * Check if packet is a movement packet (GRIM-SAFE)
     */
    private boolean isMovementPacket(Packet<?> packet) {
        // ONLY delay player position packets
        // NEVER delay transactions (C0F) - causes BadPacketsN flags
        // NEVER delay use entity (C02) - breaks attack timing
        return packet instanceof C03PacketPlayer;
    }
    
    /**
     * UNIFIED PREDICTION INTEGRATION: Check if currently releasing packets
     */
    public boolean isReleasingPackets() {
        return state != FakeLagState.SPOOFING && packetQueue.isEmpty();
    }
    
    /**
     * Get current queue size
     */
    public int getQueueSize() {
        return packetQueue.size();
    }
    
    /**
     * Check if actively holding (pulse active)
     */
    public boolean isPulseActive() {
        return state == FakeLagState.SPOOFING;
    }
    
    /**
     * Get visualized target position (for ESP)
     */
    public AxisAlignedBB getTargetPosition() {
        Module killAura = Myau.moduleManager.modules.get(KillAura.class);
        if (killAura != null && killAura.isEnabled()) {
            KillAura ka = (KillAura) killAura;
            if (ka.target != null) {
                return ka.target.getBox();
            }
        }
        return null;
    }
    
    /**
     * Get color for visualization based on state
     */
    public int getVisualizationColor() {
        if (state != FakeLagState.SPOOFING) {
            return 0x00FF00; // Green - not spoofing
        }
        
        int maxTicks = maxSpoofTicks.getValue();
        if (spoofTicksElapsed > maxTicks * 0.85) {
            return 0xFF0000; // Red - about to release
        } else if (spoofTicksElapsed > maxTicks * 0.65) {
            return 0xFFA500; // Orange
        } else if (spoofTicksElapsed > maxTicks * 0.35) {
            return 0xFFFF00; // Yellow
        }
        return 0x00FF00; // Green
    }
    
    public String[] getHUDInfo() {
        if (!isEnabled()) {
            return new String[]{};
        }
        
        switch (state) {
            case SPOOFING:
                return new String[]{"SPOOF [" + spoofTicksElapsed + "/" + maxSpoofTicks.getValue() + "]"};
            case COOLDOWN:
                return new String[]{"CD [" + packetQueue.size() + "q]"};
            case IDLE:
            default:
                return new String[]{"READY"};
        }
    }
}
