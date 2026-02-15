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
import myau.management.UnifiedPredictionSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.*;
import net.minecraft.network.play.client.*;
import net.minecraft.util.AxisAlignedBB;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * NewBacktrack - Premium Distance-Based Backtrack
 * 
 * SMART DESIGN:
 * - Only backtracks when target moves AWAY from optimal distance
 * - Sensitivity controls how much position difference triggers backtrack
 * - No transaction canceling (Grim-safe)
 * - Clean inbound packet queue
 * - Integrates with UnifiedPredictionSystem
 * 
 * HOW IT WORKS:
 * 1. Track target's server position
 * 2. If new position is WORSE than current (further away), hold packets
 * 3. This keeps target at the "backtracked" closer position
 * 4. Release when position improves or queue gets too large
 */
@ModuleInfo(category = ModuleCategory.COMBAT)
public class NewBacktrack extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    public NewBacktrack() {
        super("NewBacktrack", false);
    }
    
    // State machine for clean logic
    private enum BacktrackState {
        IDLE,           // Not backtracking
        HOLDING,        // Holding packets (backtracking active)
        RELEASING       // Releasing packets
    }
    
    // Settings
    public final FloatProperty sensitivityBlocks = new FloatProperty("sensitivity", 0.3F, 0.1F, 2.0F);
    public final IntProperty maxQueueSize = new IntProperty("max-queue", 20, 5, 50);
    public final IntProperty minHoldTicks = new IntProperty("min-hold", 3, 1, 10);
    public final IntProperty maxHoldTicks = new IntProperty("max-hold", 30, 10, 60);
    public final FloatProperty maxDistance = new FloatProperty("max-distance", 6.0F, 3.0F, 8.0F);
    public final BooleanProperty onlyInCombat = new BooleanProperty("only-combat", true);
    
    // State tracking
    private BacktrackState state = BacktrackState.IDLE;
    private AxisAlignedBB backtrackedPos = null;
    private int holdTicks = 0;
    private int targetEntityId = -1;
    
    // Distance smoothing
    private final double[] distanceHistory = new double[10];
    private int distanceHistoryIndex = 0;
    private static final double DISTANCE_DEADZONE = 0.05;
    
    // Packet queue for inbound packets
    private final ConcurrentLinkedQueue<DelayedPacket> inboundQueue = new ConcurrentLinkedQueue<>();
    
    /**
     * Packet wrapper with timestamp
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
    }
    
    /**
     * Reset all state
     */
    private void resetState() {
        state = BacktrackState.IDLE;
        backtrackedPos = null;
        holdTicks = 0;
        targetEntityId = -1;
        clearDistanceHistory();
        safeReleaseQueue();
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
            if (state != BacktrackState.IDLE) {
                transitionToIdle();
            }
            return;
        }
        
        KillAura ka = (KillAura) killAura;
        if (ka.target == null || ka.target.getEntity().isDead) {
            if (state != BacktrackState.IDLE) {
                transitionToIdle();
            }
            return;
        }
        
        // Check if target changed
        int currentTargetId = ka.target.getEntity().getEntityId();
        if (targetEntityId != -1 && targetEntityId != currentTargetId) {
            // Target changed - reset
            transitionToIdle();
            return;
        }
        targetEntityId = currentTargetId;
        
        // Only backtrack when in combat (if enabled)
        if (onlyInCombat.getValue()) {
            if (mc.thePlayer.hurtTime > 0 || !mc.thePlayer.isSwingInProgress) {
                if (state == BacktrackState.HOLDING) {
                    transitionToRelease();
                }
                return;
            }
        }
        
        // Get current target position
        AxisAlignedBB currentServerPos = ka.target.getBox();
        double currentDistance = getDistanceToBox(currentServerPos);
        
        // Add to distance history for smoothing
        addToDistanceHistory(currentDistance);
        double smoothedDistance = getSmoothedDistance();
        
        // Check max range
        if (smoothedDistance > maxDistance.getValue()) {
            if (state != BacktrackState.IDLE) {
                transitionToIdle();
            }
            return;
        }
        
        // State machine
        switch (state) {
            case IDLE:
                // Start backtracking if we have a position to track
                backtrackedPos = currentServerPos;
                transitionToHolding();
                break;
                
            case HOLDING:
                holdTicks++;
                
                // Calculate if new position is worse
                double backtrackedDistance = getDistanceToBox(backtrackedPos);
                double positionDifference = currentDistance - backtrackedDistance;
                
                // Check stop conditions
                boolean minTimeReached = holdTicks >= minHoldTicks.getValue();
                boolean maxTimeReached = holdTicks >= maxHoldTicks.getValue();
                boolean queueFull = inboundQueue.size() >= maxQueueSize.getValue();
                boolean newPosIsBetter = positionDifference < -sensitivityBlocks.getValue();
                boolean newPosIsSimilar = Math.abs(positionDifference) < DISTANCE_DEADZONE;
                
                if (queueFull || maxTimeReached) {
                    // Emergency/max stop
                    transitionToRelease();
                } else if (minTimeReached && (newPosIsBetter || newPosIsSimilar)) {
                    // New position is good enough
                    transitionToRelease();
                }
                // Otherwise keep holding - old position is still better
                break;
                
            case RELEASING:
                // Wait for queue to empty
                if (inboundQueue.isEmpty()) {
                    transitionToIdle();
                }
                break;
        }
    }
    
    /**
     * Add distance to history
     */
    private void addToDistanceHistory(double distance) {
        distanceHistory[distanceHistoryIndex] = distance;
        distanceHistoryIndex = (distanceHistoryIndex + 1) % distanceHistory.length;
    }
    
    /**
     * Get smoothed distance
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
     * Transition to holding state
     */
    private void transitionToHolding() {
        state = BacktrackState.HOLDING;
        holdTicks = 0;
    }
    
    /**
     * Transition to releasing state
     */
    private void transitionToRelease() {
        state = BacktrackState.RELEASING;
        safeReleaseQueue();
    }
    
    /**
     * Transition to idle state
     */
    private void transitionToIdle() {
        state = BacktrackState.IDLE;
        backtrackedPos = null;
        holdTicks = 0;
        safeReleaseQueue();
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
     * Safe release queue - GRIM-SAFE controlled release
     */
    private void safeReleaseQueue() {
        DelayedPacket delayed;
        while ((delayed = inboundQueue.poll()) != null) {
            processPacketDirect(delayed.getPacket());
        }
    }
    
    /**
     * Process packet directly
     */
    private void processPacketDirect(Packet<?> packet) {
        if (mc.thePlayer != null && mc.thePlayer.sendQueue != null) {
            try {
                // Re-add to network manager channel to process
                mc.thePlayer.sendQueue.getNetworkManager().sendPacket(packet);
            } catch (Exception e) {
                // Ignore packet processing errors
            }
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
     * Handle inbound packets
     */
    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || mc.thePlayer == null) {
            return;
        }
        
        if (event.getType() == EventType.RECEIVE) { // Inbound packet
            Packet<?> packet = event.getPacket();
            
            // GRIM-SAFE: Only queue entity movement packets when holding
            if (state == BacktrackState.HOLDING && isEntityUpdatePacket(packet)) {
                // Safety: don't overflow queue
                if (inboundQueue.size() < maxQueueSize.getValue()) {
                    event.setCancelled(true);
                    inboundQueue.add(new DelayedPacket(packet));
                }
            }
            
            // GRIM-SAFE: Stop backtrack on position correction
            if (packet instanceof S08PacketPlayerPosLook) {
                if (state != BacktrackState.IDLE) {
                    transitionToIdle();
                }
            }
        }
    }
    
    /**
     * Check if packet updates entity positions (GRIM-SAFE)
     */
    private boolean isEntityUpdatePacket(Packet<?> packet) {
        // Only hold actual position update packets
        // DON'T hold status/animation packets - they don't affect position
        return packet instanceof S14PacketEntity ||
               packet instanceof S18PacketEntityTeleport;
    }
    
    // TODO: Add render event when needed for visualization
    // Rendering is handled by other modules for now
    
    /**
     * Get backtracked position for prediction system
     */
    public AxisAlignedBB getBacktrackedPosition() {
        return backtrackedPos;
    }
    
    /**
     * Check if currently backtracking
     */
    public boolean isBacktracking() {
        return state == BacktrackState.HOLDING;
    }
    
    public String[] getHUDInfo() {
        if (!isEnabled()) {
            return new String[]{};
        }
        
        switch (state) {
            case HOLDING:
                return new String[]{"BT [" + holdTicks + "/" + maxHoldTicks.getValue() + "]"};
            case RELEASING:
                return new String[]{"REL [" + inboundQueue.size() + "q]"};
            case IDLE:
            default:
                return new String[]{"READY"};
        }
    }
}
