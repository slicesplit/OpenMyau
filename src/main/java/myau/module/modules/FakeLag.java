package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;
import myau.event.EventTarget;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.event.types.EventType;
import myau.module.Module;
import myau.property.properties.*;
import myau.Myau;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.*;
import net.minecraft.util.AxisAlignedBB;

import java.util.ArrayDeque;

/**
 * FakeLag - PROPERLY ARCHITECTED
 * 
 * Delays movement packets to create lag compensation advantage.
 * Works by holding packets during approach then releasing for burst hits.
 * 
 * Architecture:
 * - Clean state machine: IDLE -> SPOOFING -> RELEASING -> COOLDOWN
 * - Proper distance tracking with target change detection
 * - Safe packet handling via addToSendQueue
 * - Performance optimized (squared distance, cached references)
 */
@ModuleInfo(category = ModuleCategory.COMBAT)
public class FakeLag extends Module {
    
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // ==================== STATE MACHINE ====================
    private enum State {
        IDLE,       // Not active, waiting for conditions
        SPOOFING,   // Holding packets, building queue
        RELEASING,  // Sending queued packets
        COOLDOWN    // Waiting before next spoof cycle
    }
    
    private State currentState = State.IDLE;
    
    // ==================== SETTINGS ====================
    // Range settings
    public final FloatProperty maxSpoofRange = new FloatProperty("max-spoof-range", 3.5F, 1.0F, 6.0F);
    public final FloatProperty minSpoofRange = new FloatProperty("min-spoof-range", 2.0F, 1.0F, 4.0F);
    public final FloatProperty releaseRangeBuffer = new FloatProperty("release-buffer", 0.5F, 0.1F, 2.0F);
    
    // Timing settings
    public final IntProperty minSpoofTicks = new IntProperty("min-spoof-ticks", 5, 3, 15);
    public final IntProperty maxSpoofTicks = new IntProperty("max-spoof-ticks", 15, 5, 40);
    public final IntProperty cooldownTicks = new IntProperty("cooldown-ticks", 20, 5, 60);
    public final IntProperty maxTimeoutTicks = new IntProperty("max-timeout-ticks", 60, 20, 200);
    
    // Queue settings
    public final IntProperty maxQueueSize = new IntProperty("max-queue-size", 20, 10, 50);
    public final IntProperty releasePerTick = new IntProperty("release-per-tick", 3, 1, 10);
    
    // Behavior settings
    public final BooleanProperty onlyWhenMoving = new BooleanProperty("only-when-moving", true);
    public final BooleanProperty onlyWhenAttacking = new BooleanProperty("only-when-attacking", true);
    public final BooleanProperty requireGround = new BooleanProperty("require-ground", true);
    
    // Integration settings
    public final BooleanProperty smartIntegration = new BooleanProperty("smart-integration", true);
    public final IntProperty maxTotalLagTicks = new IntProperty("max-total-lag-ticks", 25, 10, 50);
    
    // Smoothing settings
    public final IntProperty smoothingWindow = new IntProperty("smoothing-window", 5, 3, 10);
    public final FloatProperty closingSpeedThreshold = new FloatProperty("closing-speed-threshold", 0.15F, 0.05F, 0.5F);
    
    // ==================== STATE VARIABLES ====================
    private final ArrayDeque<Packet<?>> packetQueue = new ArrayDeque<>();
    
    private int spoofTicksElapsed = 0;
    private int cooldownTicksElapsed = 0;
    private int totalTicksSinceSpoof = 0; // Timeout safety
    
    // Rubberband detection
    private double lastPosX = 0.0;
    private double lastPosY = 0.0;
    private double lastPosZ = 0.0;
    
    // Distance tracking
    private final double[] distanceHistory;
    private int distanceHistoryIndex = 0;
    private double currentSmoothedDistance = 0.0;
    private double prevSmoothedDistance = 0.0;
    private int lastTargetId = -1;
    
    // Cached references
    private KillAura killAura = null;
    private OldBacktrack oldBacktrack = null;
    private NewBacktrack newBacktrack = null;
    private LagRange lagRange = null;
    
    // ==================== CONSTRUCTOR ====================
    public FakeLag() {
        super("FakeLag", false);
        this.distanceHistory = new double[10]; // Max smoothing window
    }
    
    // ==================== LIFECYCLE ====================
    @Override
    public void onEnabled() {
        super.onEnabled();
        
        // Cache module references
        killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        oldBacktrack = (OldBacktrack) Myau.moduleManager.modules.get(OldBacktrack.class);
        newBacktrack = (NewBacktrack) Myau.moduleManager.modules.get(NewBacktrack.class);
        lagRange = (LagRange) Myau.moduleManager.modules.get(LagRange.class);
        
        // Reset state
        transitionTo(State.IDLE);
        flushPackets();
        resetDistanceTracking();
        
        spoofTicksElapsed = 0;
        cooldownTicksElapsed = 0;
        totalTicksSinceSpoof = 0;
        lastTargetId = -1;
    }
    
    @Override
    public void onDisabled() {
        super.onDisabled();
        
        // Safety: release all packets
        flushPackets();
        transitionTo(State.IDLE);
    }
    
    // ==================== EVENT HANDLERS ====================
    @EventTarget
    public void onPacket(PacketEvent event) {
        if (mc.thePlayer == null || mc.theWorld == null) return;
        
        // FIXED: Use correct EventType for outgoing packets
        if (event.getType() != EventType.SEND) return;
        
        Packet<?> packet = event.getPacket();
        
        // Only intercept position packets
        if (!(packet instanceof C03PacketPlayer)) return;
        
        // Only hold packets in SPOOFING state
        if (currentState != State.SPOOFING) return;
        
        // SMART INTEGRATION: Check if we should hold this packet
        if (smartIntegration.getValue()) {
            int smartMaxTicks = getSmartMaxTicks();
            if (packetQueue.size() >= smartMaxTicks) {
                return; // Already at smart limit, let packet through
            }
        }
        
        // Check queue size limit
        if (packetQueue.size() >= maxQueueSize.getValue()) {
            // Queue full, start releasing
            transitionTo(State.RELEASING);
            return;
        }
        
        // Hold the packet
        packetQueue.add(packet);
        event.setCancelled(true);
    }
    
    @EventTarget
    public void onTick(TickEvent event) {
        // FIXED: Only run on PRE tick to avoid double execution
        if (event.getType() != EventType.PRE) return;
        
        if (mc.thePlayer == null || mc.theWorld == null) {
            // FIXED: Safety flush on disconnect/world change
            if (!packetQueue.isEmpty()) {
                flushPackets();
            }
            return;
        }
        
        // Update distance tracking
        updateDistanceTracking();
        
        // State machine
        switch (currentState) {
            case IDLE:
                tickIdle();
                break;
            case SPOOFING:
                tickSpoofing();
                break;
            case RELEASING:
                tickReleasing();
                break;
            case COOLDOWN:
                tickCooldown();
                break;
        }
        
        // Timeout safety: force release if stuck spoofing too long
        if (currentState == State.SPOOFING) {
            totalTicksSinceSpoof++;
            if (totalTicksSinceSpoof >= maxTimeoutTicks.getValue()) {
                transitionTo(State.RELEASING);
            }
        }
    }
    
    // ==================== STATE MACHINE LOGIC ====================
    private void tickIdle() {
        // Check if we should start spoofing
        if (!shouldStartSpoof()) return;
        
        // Transition to spoofing
        transitionTo(State.SPOOFING);
    }
    
    private void tickSpoofing() {
        spoofTicksElapsed++;
        
        // SMART INTEGRATION: Get safe max ticks based on other active modules
        int smartMaxTicks = getSmartMaxTicks();
        
        // ANTI-RUBBERBAND: Check total system lag limit
        if (packetQueue.size() > maxTotalLagTicks.getValue()) {
            transitionTo(State.RELEASING);
            return;
        }
        
        // ANTI-RUBBERBAND: Detect large position desync (rubberband indicator)
        if (detectRubberband()) {
            transitionTo(State.RELEASING);
            return;
        }
        
        // Check if we should stop spoofing
        boolean shouldStop = false;
        
        // SMART INTEGRATION: Max ticks reached (using smart calculation)
        if (spoofTicksElapsed >= smartMaxTicks) {
            shouldStop = true;
        }
        
        // Target conditions broke
        if (!canSpoofTarget()) {
            shouldStop = true;
        }
        
        // FIXED: Moving away detection using prev/current smoothed distance
        if (currentSmoothedDistance > prevSmoothedDistance + releaseRangeBuffer.getValue()) {
            shouldStop = true;
        }
        
        if (shouldStop && spoofTicksElapsed >= minSpoofTicks.getValue()) {
            transitionTo(State.RELEASING);
        }
    }
    
    private void tickReleasing() {
        // Release packets gradually
        int toRelease = Math.min(releasePerTick.getValue(), packetQueue.size());
        
        for (int i = 0; i < toRelease; i++) {
            Packet<?> packet = packetQueue.poll();
            if (packet != null) {
                // FIXED: Use addToSendQueue instead of sendPacketDirect
                mc.thePlayer.sendQueue.addToSendQueue(packet);
            }
        }
        
        // Check if done releasing
        if (packetQueue.isEmpty()) {
            transitionTo(State.COOLDOWN);
        }
    }
    
    private void tickCooldown() {
        cooldownTicksElapsed++;
        
        if (cooldownTicksElapsed >= cooldownTicks.getValue()) {
            transitionTo(State.IDLE);
        }
    }
    
    // ==================== STATE TRANSITIONS ====================
    private void transitionTo(State newState) {
        if (currentState == newState) return;
        
        // Exit current state
        switch (currentState) {
            case SPOOFING:
                // Leaving spoofing: ensure we release if needed
                if (newState != State.RELEASING && !packetQueue.isEmpty()) {
                    // Safety: flush packets if not transitioning to release
                    flushPackets();
                }
                break;
        }
        
        // Enter new state
        switch (newState) {
            case IDLE:
                spoofTicksElapsed = 0;
                cooldownTicksElapsed = 0;
                totalTicksSinceSpoof = 0;
                // FIXED: Ensure queue is always empty in IDLE
                if (!packetQueue.isEmpty()) {
                    flushPackets();
                }
                break;
            case SPOOFING:
                spoofTicksElapsed = 0;
                totalTicksSinceSpoof = 0;
                break;
            case RELEASING:
                // Nothing to reset
                break;
            case COOLDOWN:
                cooldownTicksElapsed = 0;
                break;
        }
        
        currentState = newState;
    }
    
    // ==================== CONDITION CHECKS ====================
    private boolean shouldStartSpoof() {
        // Must have target
        if (!canSpoofTarget()) return false;
        
        // FIXED: Use prevSmoothedDistance for closing speed calculation
        double closingSpeed = prevSmoothedDistance - currentSmoothedDistance;
        
        if (closingSpeed < closingSpeedThreshold.getValue()) {
            return false; // Not approaching fast enough
        }
        
        // Check range
        if (currentSmoothedDistance < minSpoofRange.getValue() || currentSmoothedDistance > maxSpoofRange.getValue()) {
            return false;
        }
        
        return true;
    }
    
    private boolean canSpoofTarget() {
        // Check basic conditions
        if (onlyWhenMoving.getValue() && !isPlayerMoving()) {
            return false;
        }
        
        if (requireGround.getValue() && !mc.thePlayer.onGround) {
            return false;
        }
        
        // FIXED: Refresh KillAura if null
        if (killAura == null) {
            killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        }
        
        // FIXED: Always check KillAura since distance tracking depends on it
        if (killAura == null || !killAura.isEnabled() || killAura.target == null) {
            return false;
        }
        
        if (killAura.target.getEntity() == null || killAura.target.getEntity().isDead) {
            return false;
        }
        
        // Additional check: only when attacking mode
        if (onlyWhenAttacking.getValue()) {
            // Already validated above
        }
        
        return true;
    }
    
    private boolean isPlayerMoving() {
        return mc.thePlayer.movementInput.moveForward != 0.0F || 
               mc.thePlayer.movementInput.moveStrafe != 0.0F;
    }
    
    private boolean isBacktrackActive() {
        // Refresh references if null
        if (oldBacktrack == null) {
            oldBacktrack = (OldBacktrack) Myau.moduleManager.modules.get(OldBacktrack.class);
        }
        if (newBacktrack == null) {
            newBacktrack = (NewBacktrack) Myau.moduleManager.modules.get(NewBacktrack.class);
        }
        
        return (oldBacktrack != null && oldBacktrack.isEnabled()) || 
               (newBacktrack != null && newBacktrack.isEnabled());
    }
    
    private boolean isLagRangeActive() {
        // Refresh reference if null
        if (lagRange == null) {
            lagRange = (LagRange) Myau.moduleManager.modules.get(LagRange.class);
        }
        
        return lagRange != null && lagRange.isEnabled();
    }
    
    /**
     * SMART INTEGRATION: Calculate how many ticks we can safely spoof
     * considering backtrack and lagrange delays
     */
    private int getSmartMaxTicks() {
        if (!smartIntegration.getValue()) {
            return maxSpoofTicks.getValue(); // No coordination, use full value
        }
        
        int baseTicks = maxSpoofTicks.getValue();
        int totalSystemLag = 0;
        
        // Estimate backtrack lag (typically holds 3-5 ticks)
        if (isBacktrackActive()) {
            totalSystemLag += 5; // Conservative estimate: 5 ticks from backtrack
        }
        
        // Estimate LagRange lag (convert ms to ticks: 50ms = 1 tick)
        if (isLagRangeActive() && lagRange != null) {
            int lagRangeDelay = lagRange.delay.getValue(); // in milliseconds
            int lagRangeTicks = lagRangeDelay / 50; // Convert to ticks
            totalSystemLag += lagRangeTicks;
        }
        
        // Calculate safe FakeLag ticks: maxTotalLag - other module lags
        int safeFakeLagTicks = maxTotalLagTicks.getValue() - totalSystemLag;
        
        // Clamp to minimum 3 ticks and maximum baseTicks
        return Math.max(3, Math.min(baseTicks, safeFakeLagTicks));
    }
    
    private boolean detectRubberband() {
        // Detect large sudden position changes (server rubberband)
        double dx = mc.thePlayer.posX - lastPosX;
        double dy = mc.thePlayer.posY - lastPosY;
        double dz = mc.thePlayer.posZ - lastPosZ;
        double distanceSq = dx * dx + dy * dy + dz * dz;
        
        // Update last position
        lastPosX = mc.thePlayer.posX;
        lastPosY = mc.thePlayer.posY;
        lastPosZ = mc.thePlayer.posZ;
        
        // If moved more than 5 blocks in one tick = rubberband
        // Normal player movement is max ~0.6 blocks/tick
        return distanceSq > 25.0; // 5 blocks squared
    }
    
    // ==================== DISTANCE TRACKING ====================
    private void updateDistanceTracking() {
        // FIXED: Refresh KillAura reference if null (module reload safety)
        if (killAura == null) {
            killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        }
        
        // No tracking without KillAura target
        if (killAura == null || killAura.target == null) {
            return;
        }
        
        int currentTargetId = killAura.target.getEntity().getEntityId();
        
        // FIXED: Reset smoothing when target changes
        if (currentTargetId != lastTargetId) {
            resetDistanceTracking();
            lastTargetId = currentTargetId;
        }
        
        // FIXED: Use squared distance for performance
        double distanceSq = getDistanceToBoxSquared(killAura.target.getBox());
        double distance = Math.sqrt(distanceSq);
        
        // FIXED: Add to history using array length (not smoothingWindow which can change)
        distanceHistory[distanceHistoryIndex] = distance;
        distanceHistoryIndex = (distanceHistoryIndex + 1) % distanceHistory.length;
    }
    
    private double getCurrentSmoothedDistance() {
        // FIXED: Read circular buffer correctly based on current index
        int window = smoothingWindow.getValue();
        double sum = 0.0;
        int count = 0;
        
        // Read last N samples from circular buffer
        for (int i = 0; i < window; i++) {
            int index = (distanceHistoryIndex - 1 - i + distanceHistory.length) % distanceHistory.length;
            if (distanceHistory[index] > 0.0) {
                sum += distanceHistory[index];
                count++;
            }
        }
        
        if (count == 0) return currentSmoothedDistance; // Return previous if no data
        
        // FIXED: Update prev before current
        prevSmoothedDistance = currentSmoothedDistance;
        currentSmoothedDistance = sum / count;
        return currentSmoothedDistance;
    }
    
    private void resetDistanceTracking() {
        for (int i = 0; i < distanceHistory.length; i++) {
            distanceHistory[i] = 0.0;
        }
        distanceHistoryIndex = 0;
        currentSmoothedDistance = 0.0;
        prevSmoothedDistance = 0.0;
    }
    
    private double getDistanceToBoxSquared(AxisAlignedBB box) {
        double x = mc.thePlayer.posX;
        double y = mc.thePlayer.posY + mc.thePlayer.getEyeHeight();
        double z = mc.thePlayer.posZ;
        
        double dx = Math.max(0.0, Math.max(box.minX - x, x - box.maxX));
        double dy = Math.max(0.0, Math.max(box.minY - y, y - box.maxY));
        double dz = Math.max(0.0, Math.max(box.minZ - z, z - box.maxZ));
        
        return dx * dx + dy * dy + dz * dz;
    }
    
    // ==================== PACKET MANAGEMENT ====================
    private void flushPackets() {
        while (!packetQueue.isEmpty()) {
            Packet<?> packet = packetQueue.poll();
            if (packet != null) {
                mc.thePlayer.sendQueue.addToSendQueue(packet);
            }
        }
    }
    
    // ==================== HUD ====================
    public String getHUDInfo() {
        if (!isEnabled()) return null;
        
        return String.format("%s [Q:%d]", 
            currentState.name(), 
            packetQueue.size()
        );
    }
}
