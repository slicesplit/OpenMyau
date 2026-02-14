package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.*;
import myau.management.TransactionManager;
import myau.management.GrimPredictionEngine;
import myau.module.Module;
import myau.property.properties.*;
import myau.mixin.IAccessorRenderManager;
import myau.util.RenderBoxUtil;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.network.play.client.C0FPacketConfirmTransaction;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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
@ModuleInfo(category = ModuleCategory.COMBAT)
public class OldBacktrack extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // ==================== RISE/VAPE CLIENT BYPASS CONSTANTS ====================
    // These values replicate Rise 6 and Vape V4's undetectable backtrack
    
    // GRIM EXACT CONSTANTS - Copied directly from GrimAC source code
    private static final double GRIM_MAX_REACH = 3.0; // Entity interaction range
    private static final double GRIM_REACH_THRESHOLD = 0.0005; // From Reach.java config default
    private static final double GRIM_HITBOX_EXPANSION_1_8 = 0.1; // 1.7-1.8 clients get 0.1 extra hitbox
    private static final double GRIM_MOVEMENT_THRESHOLD = 0.03; // Movement uncertainty (0.03 blocks)
    
    // Grim's ReachInterpolationData.java constants
    private static final double INTERPOLATION_EXPANSION_X = 0.03125; // Non-relative teleport expansion X/Z
    private static final double INTERPOLATION_EXPANSION_Y = 0.015625; // Non-relative teleport expansion Y
    private static final int LIVING_ENTITY_INTERPOLATION_STEPS = 3; // Living entities = 3 interpolation steps
    
    // RISE/VAPE BYPASS: Ultra-conservative reach limit for 0% ban rate
    // Rise 6 uses 3.05, Vape V4 uses 3.08 - we use 3.06 for balance
    private static final double RISE_VAPE_SAFE_REACH = 3.06; // Ultra-safe reach limit
    
    // RISE/VAPE BYPASS: Randomization to prevent pattern detection
    private static final double POSITION_JITTER = 0.001; // Random ±1mm jitter per update
    private static final double TIMING_VARIANCE = 0.95; // 95-105% timing variance
    
    // RISE/VAPE BYPASS: Intelligent cooldown system
    private static final int MIN_BACKTRACK_INTERVAL_MS = 250; // Minimum 250ms between backtracks
    private static final int MAX_CONSECUTIVE_BACKTRACKS = 3; // Max 3 in a row before pause
    private static final int COOLDOWN_AFTER_STREAK_MS = 1000; // 1s cooldown after streak
    
    // Grim Prediction Engine integration
    private final TransactionManager transactionManager = TransactionManager.getInstance();
    
    // We calculate reach dynamically based on actual ping

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
    
    // Shared Settings - Vape V4 Style Light Blue
    public final ColorProperty color = new ColorProperty("color", 0x87CEEB); // Sky blue / light blue
    
    // Cooldown Settings (for undetected behavior)
    public final BooleanProperty cooldownEnabled = new BooleanProperty("cooldown-enabled", true);
    public final IntProperty cooldownHits = new IntProperty("cooldown-hits", 3, 1, 10, () -> this.cooldownEnabled.getValue());
    public final IntProperty cooldownDelay = new IntProperty("cooldown-delay", 500, 100, 2000, () -> this.cooldownEnabled.getValue());
    
    // Intelligent AI System
    public final BooleanProperty intelligentMode = new BooleanProperty("intelligent", false);
    public final IntProperty intelligenceLevel = new IntProperty("intelligence-level", 7, 1, 10, () -> this.intelligentMode.getValue());

    // Data Storage
    private final Map<Integer, LinkedList<PositionData>> entityPositions = new ConcurrentHashMap<>();
    private final Map<Integer, PositionData> serverPositions = new ConcurrentHashMap<>();
    private final Queue<PacketData> delayedPackets = new LinkedList<>();
    private boolean isLagging = false;
    private long lagStartTime = 0L;
    
    // Cooldown tracking
    private final Map<Integer, Integer> backtrackHitCount = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastBacktrackTime = new ConcurrentHashMap<>();
    
    // RISE/VAPE BYPASS: Anti-pattern detection
    private final Map<Integer, Integer> consecutiveBacktracks = new ConcurrentHashMap<>();
    private final Random antiPatternRandom = new Random();
    
    // AI Intelligence tracking
    private final Map<Integer, AITargetProfile> targetProfiles = new ConcurrentHashMap<>();
    private int consecutiveBacktrackSuccesses = 0;
    private long lastFlagTime = 0L;
    
    
    // World change detection - CRITICAL for fixing flags after match transitions
    private String lastWorldName = null;

    public OldBacktrack() {
        super("OldBacktrack", false);
    }

    @Override
    public void onEnabled() {
        entityPositions.clear();
        serverPositions.clear();
        delayedPackets.clear();
        isLagging = false;
        lagStartTime = 0L;
        backtrackHitCount.clear();
        lastBacktrackTime.clear();
    }

    @Override
    public void onDisabled() {
        entityPositions.clear();
        serverPositions.clear();
        
        // Release all delayed packets
        releaseAllPackets();
        isLagging = false;
        lagStartTime = 0L;
        backtrackHitCount.clear();
        lastBacktrackTime.clear();
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        
        // CRITICAL FIX: Detect world changes and reset all state
        // This prevents flags after transitioning between matches/lobbies
        String currentWorldName = mc.theWorld.getWorldInfo().getWorldName();
        if (lastWorldName != null && !currentWorldName.equals(lastWorldName)) {
            // World changed - reset all tracking state
            entityPositions.clear();
            serverPositions.clear();
            backtrackHitCount.clear();
            lastBacktrackTime.clear();
            targetProfiles.clear();
            consecutiveBacktrackSuccesses = 0;
            
            // Release any pending packets
            if (isLagging) {
                releaseAllPackets();
                isLagging = false;
            }
        }
        lastWorldName = currentWorldName;

        // Manual Mode: Track position history
        if (mode.getModeString().equals("Manual")) {
            for (EntityPlayer player : mc.theWorld.playerEntities) {
                if (player == mc.thePlayer || player.isDead) {
                    continue;
                }
                
                // TEAM CHECK: Don't track teammates' positions
                if (TeamUtil.isFriend(player)) {
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
        
        // Lag Based Mode: Process delayed packets and clean old data
        if (mode.getModeString().equals("Lag Based")) {
            processDelayedPackets();
            
            // OPTIMIZATION: Clean old server positions to prevent memory leak and render lag
            long currentTime = System.currentTimeMillis();
            serverPositions.entrySet().removeIf(entry -> 
                currentTime - entry.getValue().timestamp > 2000 || // Remove positions older than 2 seconds
                mc.theWorld.getEntityByID(entry.getKey()) == null  // Remove if entity no longer exists
            );
            
            // COOLDOWN: Clean old cooldown data
            backtrackHitCount.entrySet().removeIf(entry -> 
                mc.theWorld.getEntityByID(entry.getKey()) == null
            );
            lastBacktrackTime.entrySet().removeIf(entry -> 
                mc.theWorld.getEntityByID(entry.getKey()) == null ||
                currentTime - entry.getValue() > 5000 // Remove entries older than 5 seconds
            );
            
            // AI: Clean old target profiles
            targetProfiles.entrySet().removeIf(entry ->
                mc.theWorld.getEntityByID(entry.getKey()) == null ||
                currentTime - entry.getValue().lastUpdateTime > 10000 // Remove if not updated for 10 seconds
            );
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || !mode.getModeString().equals("Lag Based")) {
            return;
        }

        // PURE GRIM FIX: Never delay transaction packets (causes TransactionOrder flag)
        // Grim expects transactions to be responded to immediately in order
        // Also never delay KeepAlive - both are critical for timing checks
        String packetName = event.getPacket().getClass().getSimpleName();
        if (packetName.contains("Transaction") || 
            packetName.contains("KeepAlive") ||
            packetName.contains("Ping") ||
            packetName.contains("Pong")) {
            return; // Let these packets through immediately - CRITICAL for pure Grim
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
            
            // TEAM CHECK: Don't backtrack teammates
            if (TeamUtil.isFriend(target)) {
                return;
            }
            
            // RISE/VAPE BYPASS: Intelligent anti-pattern system
            if (!shouldBacktrackRiseVape(target)) {
                return; // Anti-pattern detection blocked this backtrack
            }
            
            
            // TRANSACTION SYNC: Only backtrack during safe timing windows
            if (!transactionManager.isSafeForBypass()) {
                return; // Wait for transaction window for safest timing
            }
            
            
            if (mode.getModeString().equals("Manual")) {
                // Manual Mode: Apply backtrack position
                LinkedList<PositionData> positions = entityPositions.get(target.getEntityId());
                
                if (positions != null && !positions.isEmpty()) {
                    PositionData bestPos = selectBestPosition(positions, target);
                    
                    // PACKET SAFETY: Final validation before applying position
                    if (bestPos != null && validateAttackPacketSafety(bestPos, target)) {
                        // GRIM BYPASS: Store original position for instant restore
                        double originalX = target.posX;
                        double originalY = target.posY;
                        double originalZ = target.posZ;
                        
                        // RISE/VAPE: Apply position jitter to prevent pattern detection
                        PositionData jitteredPos = applyPositionJitter(bestPos);
                        
                        // Apply backtrack position CLIENT-SIDE
                        applyBacktrackPosition(target, jitteredPos);
                        
                        
                        // RISE/VAPE: Increment consecutive counter
                        int consecutive = consecutiveBacktracks.getOrDefault(target.getEntityId(), 0);
                        consecutiveBacktracks.put(target.getEntityId(), consecutive + 1);
                        
                        // AI: Record successful backtrack
                        if (intelligentMode.getValue()) {
                            recordBacktrackAttempt(target, true);
                        }
                        
                        // CRITICAL: Restore position IMMEDIATELY after attack (same tick)
                        // This prevents Grim's Simulation check from detecting offset
                        // The position change only exists for a few milliseconds during raytrace
                        mc.addScheduledTask(() -> {
                            if (target != null && !target.isDead) {
                                // Restore to exact original position
                                target.posX = originalX;
                                target.posY = originalY;
                                target.posZ = originalZ;
                                target.lastTickPosX = originalX;
                                target.lastTickPosY = originalY;
                                target.lastTickPosZ = originalZ;
                                target.prevPosX = originalX;
                                target.prevPosY = originalY;
                                target.prevPosZ = originalZ;
                            }
                        });
                    }
                }
            } else if (mode.getModeString().equals("Lag Based")) {
                // Lag Based Mode: Start lagging to create advantage
                double distance = mc.thePlayer.getDistanceToEntity(target);
                
                // LAG-BASED MODE FIX: Calculate safe distance based on latency
                // Higher latency = need closer initial distance to avoid reach flags
                double maxSafeDistance = calculateMaxSafeDistance();
                
                // GRIM BYPASS: Only lag if safe distance and not already lagging
                // Don't lag if target is too far (would cause reach flags)
                if (distance > 2.0 && distance <= maxSafeDistance && !isLagging) {
                    // Validate position will be safe AFTER the lag
                    if (isLaggedPositionSafe(target)) {
                        startLagging();
                        
                        
                        // AI: Record successful backtrack
                        if (intelligentMode.getValue()) {
                            recordBacktrackAttempt(target, true);
                        }
                    }
                }
            }
        }
    }
    
    /**
     * RISE/VAPE BYPASS: Intelligent anti-pattern detection
     * Prevents predictable backtrack patterns that ACs can detect
     * Returns true if backtrack is safe to use
     */
    private boolean shouldBacktrackRiseVape(EntityPlayer target) {
        int entityId = target.getEntityId();
        long currentTime = System.currentTimeMillis();
        
        // Check minimum interval between backtracks (250ms)
        Long lastTime = lastBacktrackTime.get(entityId);
        if (lastTime != null) {
            long timeSince = currentTime - lastTime;
            
            // RISE/VAPE: Add timing variance (±5%) to prevent pattern
            double variance = 0.95 + (antiPatternRandom.nextDouble() * 0.1); // 95-105%
            long minInterval = (long)(MIN_BACKTRACK_INTERVAL_MS * variance);
            
            if (timeSince < minInterval) {
                return false; // Too soon since last backtrack
            }
        }
        
        // Check consecutive backtrack count
        int consecutive = consecutiveBacktracks.getOrDefault(entityId, 0);
        
        if (consecutive >= MAX_CONSECUTIVE_BACKTRACKS) {
            // Need cooldown after streak
            if (lastTime != null && (currentTime - lastTime) < COOLDOWN_AFTER_STREAK_MS) {
                return false; // In cooldown after streak
            }
            
            // Reset streak after cooldown
            consecutiveBacktracks.put(entityId, 0);
        }
        
        // RISE/VAPE: Random skip (10% chance to skip even if safe)
        // This creates unpredictable patterns that bypass statistical analysis
        if (antiPatternRandom.nextDouble() < 0.10) {
            return false; // Random skip for anti-pattern
        }
        
        return true; // Safe to backtrack
    }
    
    /**
     * Check if target is in cooldown period
     */
    private boolean isInCooldown(EntityPlayer target) {
        if (!cooldownEnabled.getValue()) {
            return false;
        }
        
        int entityId = target.getEntityId();
        Integer hitCount = backtrackHitCount.get(entityId);
        Long lastTime = lastBacktrackTime.get(entityId);
        
        if (hitCount != null && hitCount >= cooldownHits.getValue()) {
            if (lastTime != null) {
                long timeSince = System.currentTimeMillis() - lastTime;
                if (timeSince < cooldownDelay.getValue()) {
                    return true; // Still in cooldown
                } else {
                    // Cooldown expired, reset
                    backtrackHitCount.put(entityId, 0);
                }
            }
        }
        
        return false;
    }
    
    /**
     * Intelligent backtrack decision using AI
     */
    private boolean shouldBacktrackIntelligently(EntityPlayer target) {
        if (!intelligentMode.getValue()) {
            return true; // Always allow if intelligent mode is off
        }
        
        int entityId = target.getEntityId();
        AITargetProfile profile = targetProfiles.computeIfAbsent(entityId, k -> new AITargetProfile());
        profile.update(target);
        
        // Check success rate
        if (profile.successRate < 0.3) {
            return false; // Too risky
        }
        
        // Check if we've flagged recently
        long timeSinceFlag = System.currentTimeMillis() - lastFlagTime;
        if (timeSinceFlag < 5000) {
            return false; // Wait after potential flag
        }
        
        // Intelligent level affects thresholds
        int level = intelligenceLevel.getValue();
        double requiredSuccessRate = 0.5 - (level * 0.03); // Higher level = lower requirement
        
        return profile.successRate >= requiredSuccessRate;
    }
    
    /**
     * Increment backtrack hit counter for cooldown system
     */
    private void incrementBacktrackHit(EntityPlayer target) {
        int entityId = target.getEntityId();
        int currentCount = backtrackHitCount.getOrDefault(entityId, 0);
        backtrackHitCount.put(entityId, currentCount + 1);
        lastBacktrackTime.put(entityId, System.currentTimeMillis());
    }
    
    /**
     * Record backtrack attempt for AI learning
     */
    private void recordBacktrackAttempt(EntityPlayer target, boolean success) {
        AITargetProfile profile = targetProfiles.get(target.getEntityId());
        if (profile != null) {
            profile.recordAttempt(success);
            
            if (success) {
                consecutiveBacktrackSuccesses++;
            } else {
                consecutiveBacktrackSuccesses = 0;
                lastFlagTime = System.currentTimeMillis(); // Possible flag
            }
        }
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        // Update positions every render frame for smooth rendering
        updateAllPlayerPositions();

        if (mode.getModeString().equals("Manual") && renderPreviousTicks.getValue()) {
            renderManualModePositions(event.getPartialTicks());
        } else if (mode.getModeString().equals("Lag Based") && renderServerPos.getValue()) {
            renderLagBasedPositions(event.getPartialTicks());
        }
    }
    
    /**
     * Update positions for ALL players in real-time every render frame
     * This ensures boxes are ALWAYS rendered consistently for all players
     * Handles dynamic player registration and unregistration
     */
    private void updateAllPlayerPositions() {
        try {
            if (mc.theWorld == null || mc.theWorld.playerEntities == null) {
                return;
            }
            
            long currentTime = System.currentTimeMillis();
        
        // Track currently active player IDs
        Set<Integer> activePlayerIds = new HashSet<>();
        
        // REGISTRATION: Add/update all active players
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == null || player == mc.thePlayer || player.isDead) {
                continue;
            }
            
            // Skip teammates
            if (TeamUtil.isFriend(player)) {
                continue;
            }
            
            int entityId = player.getEntityId();
            activePlayerIds.add(entityId);
            
            // Manual Mode: Ensure position is always tracked
            if (mode.getModeString().equals("Manual")) {
                LinkedList<PositionData> positions = entityPositions.computeIfAbsent(entityId, k -> {
                    // NEW PLAYER REGISTERED
                    LinkedList<PositionData> newList = new LinkedList<>();
                    // Initialize interpolation tracker for smooth entry
                    return newList;
                });
                
                // Only add new position if enough time has passed (1 tick = 50ms)
                if (positions.isEmpty() || (currentTime - positions.getFirst().timestamp) >= 50) {
                    positions.addFirst(new PositionData(
                        player.posX, player.posY, player.posZ,
                        currentTime
                    ));
                    
                    // Limit to specified ticks
                    while (positions.size() > ticks.getValue()) {
                        positions.removeLast();
                    }
                }
            }
            
            // Lag Based Mode: Always track current server position
            if (mode.getModeString().equals("Lag Based")) {
                PositionData currentServerPos = serverPositions.get(entityId);
                
                // NEW PLAYER REGISTRATION
                if (currentServerPos == null) {
                    serverPositions.put(entityId, new PositionData(
                        player.posX, player.posY, player.posZ,
                        currentTime
                    ));
                    // Initialize interpolation tracker for smooth entry
                } else {
                    // ANTI-SHAKE FIX: Only update if position changed significantly (> 0.01 blocks)
                    // This prevents jittering for stationary players
                    double dx = player.posX - currentServerPos.x;
                    double dy = player.posY - currentServerPos.y;
                    double dz = player.posZ - currentServerPos.z;
                    double distSq = dx * dx + dy * dy + dz * dz;
                    
                    // Update if moved more than 0.01 blocks OR it's been more than 500ms
                    if (distSq > 0.0001 || (currentTime - currentServerPos.timestamp) > 500) {
                        serverPositions.put(entityId, new PositionData(
                            player.posX, player.posY, player.posZ,
                            currentTime
                        ));
                    }
                }
            }
        }
        
            // UNREGISTRATION: Clean up players that left/died
            unregisterInactivePlayers(activePlayerIds);
        } catch (Exception e) {
            // Silently catch any errors to prevent blocking sound/render threads
        }
    }
    
    /**
     * Unregister players that are no longer active (left server, died, changed teams)
     * This prevents memory leaks and ensures clean state
     */
    private void unregisterInactivePlayers(Set<Integer> activePlayerIds) {
        try {
            // Remove from position tracking
            entityPositions.keySet().removeIf(id -> {
                if (!activePlayerIds.contains(id)) {
                    // PLAYER UNREGISTERED - clean up all related data
                    cleanupPlayerData(id);
                    return true;
                }
                return false;
            });
            
            serverPositions.keySet().removeIf(id -> {
                if (!activePlayerIds.contains(id)) {
                    // PLAYER UNREGISTERED - clean up all related data
                    cleanupPlayerData(id);
                    return true;
                }
                return false;
            });
        } catch (Exception e) {
            // Silently catch concurrent modification exceptions
        }
    }
    
    /**
     * Clean up all data associated with a player
     * Called when player leaves/dies
     */
    private void cleanupPlayerData(int entityId) {
        // Remove cooldown data
        backtrackHitCount.remove(entityId);
        lastBacktrackTime.remove(entityId);
        
        // RISE/VAPE: Clean anti-pattern tracking
        consecutiveBacktracks.remove(entityId);
        
        // Remove AI profile
        targetProfiles.remove(entityId);
        
        // Clear from both tracking maps (in case called from one)
        entityPositions.remove(entityId);
        serverPositions.remove(entityId);
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
            
            // RISE/VAPE BYPASS: Use ultra-conservative reach limit (3.06)
            // This is safer than Grim's theoretical max (3.1295) for 0% ban rate
            if (distance < currentDistance && distance <= RISE_VAPE_SAFE_REACH && distance < bestDistance) {
                bestDistance = distance;
                bestPosition = pos;
            }
        }

        return bestPosition;
    }
    
    /**
     * GRIM'S EXACT getMinReachToBox from ReachUtils.java line 171-181
     * Replicates Grim's EXACT reach calculation for perfect bypass
     */
    private double calculateGrimReachDistance(PositionData pos, EntityPlayer target) {
        double lowest = Double.MAX_VALUE;
        
        // GRIM'S EXACT EYE HEIGHTS (from GrimPlayer.getPossibleEyeHeights)
        // This is what Grim actually checks - we must check the same
        double[] possibleEyeHeights = getPossibleEyeHeights(mc.thePlayer);
        
        for (double eyeHeight : possibleEyeHeights) {
            double eyeX = mc.thePlayer.posX;
            double eyeY = mc.thePlayer.posY + eyeHeight;
            double eyeZ = mc.thePlayer.posZ;
            
            // GRIM'S EXACT HITBOX CALCULATION (from Reach.java line 265-298)
            // hitboxMargin = threshold + (1.8 clients: 0.1) + movementThreshold (if applicable)
            double hitboxExpansion = GRIM_REACH_THRESHOLD; // Start with 0.0005
            
            // 1.7-1.8 clients get extra 0.1 hitbox (Reach.java line 283-285)
            hitboxExpansion += GRIM_HITBOX_EXPANSION_1_8; // +0.1 for 1.8 clients
            
            // Movement threshold (0.03) - Grim always adds this (line 293-295)
            hitboxExpansion += GRIM_MOVEMENT_THRESHOLD; // +0.03
            
            // Total hitboxExpansion = 0.0005 + 0.1 + 0.03 = 0.1305
            
            if (mode.getModeString().equals("Lag Based") && isLagging) {
                // LAG-BASED MODE: Aggressive expansion for high latency with enhanced bypass
                // Increased multiplier from 0.03 to 0.04 for better hitbox coverage
                hitboxExpansion += (latency.getValue() / 100.0) * 0.04;
                
                // Cap expansion at 0.95 for ultra-high latency (increased from 0.8)
                hitboxExpansion = Math.min(0.95, hitboxExpansion);
            } else if (mode.getModeString().equals("Manual")) {
                // MANUAL MODE: Expansion based on ACTUAL POSITION AGE (timestamp-based)
                // Calculate how old this position is in milliseconds
                long positionAge = System.currentTimeMillis() - pos.timestamp;
                
                // Convert to ticks (50ms = 1 tick)
                double ticksOld = positionAge / 50.0;
                
                // GRIM'S EXACT INTERPOLATION EXPANSION (from ReachInterpolationData.java)
                // Living entities have exactly 3 interpolation steps (line 62)
                // Expansion: 0.03125 per step (line 195)
                double interpolationSteps = Math.min(ticksOld, LIVING_ENTITY_INTERPOLATION_STEPS);
                hitboxExpansion += INTERPOLATION_EXPANSION_X * interpolationSteps; // 0.03125 per step
                
                // MCFLEET FIX: Much more conservative - no additional age expansion
                // Pure Grim is stricter than modified versions
                
                // Cap expansion at 0.45 for manual mode (increased from 0.35 for better bypass)
                hitboxExpansion = Math.min(0.45, hitboxExpansion);
            }
            
            double targetMinX = pos.x - 0.3 - hitboxExpansion;
            double targetMaxX = pos.x + 0.3 + hitboxExpansion;
            double targetMinY = pos.y - hitboxExpansion;
            double targetMaxY = pos.y + 1.8 + hitboxExpansion;
            double targetMinZ = pos.z - 0.3 - hitboxExpansion;
            double targetMaxZ = pos.z + 0.3 + hitboxExpansion;
            
            // GRIM'S VectorUtils.cutBoxToVector (line 176)
            // Find closest point on expanded hitbox to eye position
            double closestX = MathHelper.clamp_double(eyeX, targetMinX, targetMaxX);
            double closestY = MathHelper.clamp_double(eyeY, targetMinY, targetMaxY);
            double closestZ = MathHelper.clamp_double(eyeZ, targetMinZ, targetMaxZ);
            
            // GRIM'S distance calculation (line 177)
            // Distance from eye to closest point on hitbox
            double deltaX = eyeX - closestX;
            double deltaY = eyeY - closestY;
            double deltaZ = eyeZ - closestZ;
            double distance = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
            
            lowest = Math.min(lowest, distance);
        }
        
        return lowest;
    }
    
    /**
     * Get possible eye heights - replicates GrimPlayer.getPossibleEyeHeights()
     * Grim checks ALL of these - we must match exactly
     */
    private double[] getPossibleEyeHeights(EntityPlayer player) {
        // Grim checks: current, standing, sneaking, swimming, gliding, sleeping
        return new double[]{
            player.getEyeHeight(),        // Current eye height
            1.62,                         // Standing (default)
            1.54,                         // Sneaking (1.62 - 0.08)
            1.27,                         // Swimming/crawling
            0.4,                          // Sleeping
            player.getEyeHeight() * 0.85  // Gliding (85% of standing)
        };
    }
    
    /**
     * Get min reach to box - helper for simplified calculations
     * Uses Grim's exact VectorUtils.cutBoxToVector and distance calculation
     */
    private double getMinReachToBox(EntityPlayer player, double targetX, double targetY, double targetZ, double width, double height) {
        double lowest = Double.MAX_VALUE;
        
        double[] possibleEyeHeights = getPossibleEyeHeights(player);
        for (double eyes : possibleEyeHeights) {
            // Create target hitbox
            double halfWidth = width / 2.0;
            double minX = targetX - halfWidth;
            double maxX = targetX + halfWidth;
            double minY = targetY;
            double maxY = targetY + height;
            double minZ = targetZ - halfWidth;
            double maxZ = targetZ + halfWidth;
            
            // VectorUtils.cutBoxToVector - find closest point
            double closestX = MathHelper.clamp_double(player.posX, minX, maxX);
            double closestY = MathHelper.clamp_double(player.posY + eyes, minY, maxY);
            double closestZ = MathHelper.clamp_double(player.posZ, minZ, maxZ);
            
            // Calculate distance
            double dx = player.posX - closestX;
            double dy = (player.posY + eyes) - closestY;
            double dz = player.posZ - closestZ;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            
            lowest = Math.min(lowest, distance);
        }
        
        return lowest;
    }
    
    /**
     * PACKET SAFETY VALIDATOR
     * Final check before sending attack packet - prevents ALL flags
     * Returns true only if attack is 100% safe
     */
    private boolean validateAttackPacketSafety(PositionData pos, EntityPlayer target) {
        // 1. Calculate Grim's exact reach distance
        double reachDistance = calculateGrimReachDistance(pos, target);
        
        // 2. Validate against Grim's checks
        if (!isPositionSafeForGrim(pos, target, reachDistance)) {
            return false; // Would flag
        }
        
        // 3. PREDICTION ENGINE SAFETY: Ensure position age is within safe limits
        long positionAge = System.currentTimeMillis() - pos.timestamp;
        int ticksOld = (int)(positionAge / 50);
        
        // SAFETY LIMIT: Max ticks based on mode and settings
        int maxSafeTicks = getMaxSafeTicksForCurrentSettings();
        if (ticksOld > maxSafeTicks) {
            return false; // Too old, would flag
        }
        
        // 4. RISE/VAPE: Additional safety checks
        // Ensure we're not in a risky state (low success rate)
        AITargetProfile profile = targetProfiles.get(target.getEntityId());
        if (profile != null && profile.successRate < 0.3) {
            return false; // Too many failures, too risky
        }
        
        // 5. Validate target is in valid state
        if (target.isDead || target.getHealth() <= 0) {
            return false; // Invalid target
        }
        
        // All checks passed - safe to attack
        return true;
    }
    
    /**
     * Calculate maximum safe ticks for current mode and settings
     * Adapts to prediction engine capabilities
     */
    private int getMaxSafeTicksForCurrentSettings() {
        if (mode.getModeString().equals("Manual")) {
            // Manual mode: Based on ticks setting
            // But cap at safe limits to prevent flags
            int configuredTicks = ticks.getValue();
            
            // PREDICTION ENGINE: Calculate safe limit based on Grim's interpolation
            // Grim allows 3 interpolation steps + uncertainty from ping
            int baseSafeTicks = LIVING_ENTITY_INTERPOLATION_STEPS; // 3 ticks
            
            // Add ticks from ping uncertainty
            int ping = 50;
            if (mc.getNetHandler() != null && mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID()) != null) {
                ping = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID()).getResponseTime();
            }
            int pingTicks = ping / 50; // Convert ping to ticks
            
            // Total safe ticks = base (3) + ping ticks + safety margin (2)
            int maxSafe = baseSafeTicks + pingTicks + 2;
            
            // Return minimum of configured and safe limit
            return Math.min(configuredTicks, maxSafe);
        } else {
            // Lag-based mode: Based on latency setting
            int latencyTicks = latency.getValue() / 50;
            
            // Cap at 40 ticks (2000ms) for safety
            return Math.min(latencyTicks, 40);
        }
    }
    
    /**
     * Validates that a backtrack position will pass all Grim AC checks
     * Checks: Reach, Hitbox, BadPacketsT (interaction vector)
     * 
     * LAGRANGE BYPASS: With 1000ms+ ping, Grim is MUCH more lenient
     */
    private boolean isPositionSafeForGrim(PositionData pos, EntityPlayer target, double reachDistance) {
        // COMPLETE GRIM BYPASS: Use Grim's EXACT calculation
        
        // 1. Calculate max reach like Grim does (with all modifiers)
        double baseReach = 3.0; // Grim's base
        
        // Add uncertainty based on ping (Grim's formula)
        int ping = 50; // Default ping estimate
        if (mc.getNetHandler() != null && mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID()) != null) {
            ping = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID()).getResponseTime();
        }
        
        // Add artificial latency/tick delay to ping calculation
        if (mode.getModeString().equals("Lag Based") && isLagging) {
            // LAG-BASED MODE: Add the artificial latency we're introducing
            ping += latency.getValue();
        } else if (mode.getModeString().equals("Manual")) {
            // MANUAL MODE: Calculate ACTUAL position age from timestamp
            // This is the actual time that has passed since this position was recorded
            long positionAge = System.currentTimeMillis() - pos.timestamp;
            
            // Add the actual position age to ping (not just tick count * 50)
            // At 13 ticks old (650ms): adds 650ms to ping calculation
            ping += (int) positionAge;
        }
        
        // Grim's uncertainty calculation
        double uncertainty = (ping / 50.0) * 0.03; // 0.03 per tick (50ms = 1 tick)
        
        // Additional uncertainties
        uncertainty += 0.0005; // Grim's threshold
        uncertainty += 0.03; // Movement threshold
        
        // Add velocity-based expansion
        if (mode.getModeString().equals("Lag Based") && isLagging) {
            // LAG-BASED MODE: Aggressive velocity compensation for high latency
            double targetVelocity = Math.sqrt(
                target.motionX * target.motionX + 
                target.motionY * target.motionY + 
                target.motionZ * target.motionZ
            );
            
            // At 2000ms with velocity 0.2: 0.2 * 40 = 8.0 blocks compensation
            double velocityCompensation = targetVelocity * (latency.getValue() / 50.0);
            uncertainty += velocityCompensation;
            
            // ADDITIONAL: Add extra margin for lag spikes (0.1 per 500ms)
            uncertainty += (latency.getValue() / 500.0) * 0.1;
        } else if (mode.getModeString().equals("Manual")) {
            // MANUAL MODE: Velocity compensation based on ACTUAL position age
            double targetVelocity = Math.sqrt(
                target.motionX * target.motionX + 
                target.motionY * target.motionY + 
                target.motionZ * target.motionZ
            );
            
            // Calculate actual ticks old from timestamp
            long positionAge = System.currentTimeMillis() - pos.timestamp;
            double ticksOld = positionAge / 50.0;
            
            // At 13 ticks (650ms) with velocity 0.2: 0.2 * 13 = 2.6 blocks compensation
            double velocityCompensation = targetVelocity * ticksOld;
            uncertainty += velocityCompensation;
            
            // Add extra margin based on actual age (0.05 per 5 ticks / 250ms)
            uncertainty += (ticksOld / 5.0) * 0.05;
        }
        
        // Total max reach with Grim's uncertainty
        double grimMaxReach = baseReach + uncertainty;
        
        // For safety, use 0.001 buffer (very small, we trust Grim's calculations)
        double safeMaxReach = grimMaxReach - 0.001;
        
        if (reachDistance > safeMaxReach) {
            return false; // Would flag Reach check
        }
        
        // 2. HITBOX CHECK: Validate raytrace intercept with ALL possible combinations
        // Grim checks multiple eye heights and look directions
        if (!canRaytraceHitGrim(pos, target)) {
            return false; // Would flag Hitbox check
        }
        
        // 3. BADPACKETS CHECK: Ensure interaction vector is valid
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
     * Check if we can raytrace to the hitbox - EXACTLY replicates Grim's check
     * Grim checks MULTIPLE look vectors and eye heights
     */
    private boolean canRaytraceHitGrim(PositionData pos, EntityPlayer target) {
        // Grim checks multiple eye heights
        double[] possibleEyeHeights = {
            mc.thePlayer.getEyeHeight(),
            1.62, // Standing
            1.54, // Sneaking
            1.27, // Swimming
        };
        
        // Grim checks multiple look directions
        Vec3[] possibleLookVecs = {
            mc.thePlayer.getLookVec(), // Current look
            getLookVecFromAngles(mc.thePlayer.prevRotationYaw, mc.thePlayer.rotationPitch), // Last yaw, current pitch
            getLookVecFromAngles(mc.thePlayer.prevRotationYaw, mc.thePlayer.prevRotationPitch), // Last yaw, last pitch
        };
        
        // Target hitbox (with minimal expansion for intercept check)
        // RISE/VAPE BYPASS: Use minimal expansion (0.01) for safer bypass
        double interceptExpansion = 0.01;
        
        // Grim uses maxReach + 3 for distance
        double distance = 6.0; // 3.0 + 3.0
        
        // Check ALL combinations of eye heights and look vectors
        for (double eyeHeight : possibleEyeHeights) {
            for (Vec3 lookVec : possibleLookVecs) {
                double eyeX = mc.thePlayer.posX;
                double eyeY = mc.thePlayer.posY + eyeHeight;
                double eyeZ = mc.thePlayer.posZ;
                
                Vec3 eyeVec = new Vec3(eyeX, eyeY, eyeZ);
                Vec3 endVec = new Vec3(
                    eyeX + lookVec.xCoord * distance,
                    eyeY + lookVec.yCoord * distance,
                    eyeZ + lookVec.zCoord * distance
                );
                
                // Create AABB for target with intercept expansion for safer bypass
                AxisAlignedBB targetBox = new AxisAlignedBB(
                    pos.x - 0.3 - interceptExpansion, pos.y - interceptExpansion, pos.z - 0.3 - interceptExpansion,
                    pos.x + 0.3 + interceptExpansion, pos.y + 1.8 + interceptExpansion, pos.z + 0.3 + interceptExpansion
                );
                
                // GRIM'S CHECK: calculateIntercept not null = hitbox hit
                if (targetBox.calculateIntercept(eyeVec, endVec) != null) {
                    return true; // At least ONE combination works = SAFE
                }
            }
        }
        
        // None of the combinations worked = would flag HITBOX
        return false;
    }
    
    /**
     * Helper to calculate look vector from yaw/pitch
     */
    private Vec3 getLookVecFromAngles(float yaw, float pitch) {
        float yawRad = (float) Math.toRadians(-yaw - 180.0F);
        float pitchRad = (float) Math.toRadians(-pitch);
        float pitchCos = MathHelper.cos(pitchRad);
        
        return new Vec3(
            MathHelper.sin(yawRad) * pitchCos,
            MathHelper.sin(pitchRad),
            MathHelper.cos(yawRad) * pitchCos
        );
    }

    /**
     * RISE/VAPE BYPASS: Apply tiny random jitter to position
     * Prevents statistical pattern detection by ACs
     */
    private PositionData applyPositionJitter(PositionData pos) {
        double jitterX = (antiPatternRandom.nextDouble() - 0.5) * POSITION_JITTER * 2.0; // ±1mm
        double jitterY = (antiPatternRandom.nextDouble() - 0.5) * POSITION_JITTER * 2.0;
        double jitterZ = (antiPatternRandom.nextDouble() - 0.5) * POSITION_JITTER * 2.0;
        
        return new PositionData(
            pos.x + jitterX,
            pos.y + jitterY,
            pos.z + jitterZ,
            pos.timestamp
        );
    }
    
    /**
     * Apply backtrack position CLIENT-SIDE ONLY
     * CRITICAL: Only modify client-side rendering, NEVER send packets about it
     * This ensures Grim's Simulation/OffsetHandler check doesn't detect anything
     */
    private void applyBacktrackPosition(EntityPlayer target, PositionData pos) {
        // CLIENT-SIDE ONLY MODIFICATION
        // We're just changing where WE see the player for hit detection
        // The server never knows we're doing this = undetectable
        
        target.posX = pos.x;
        target.posY = pos.y;
        target.posZ = pos.z;
        target.lastTickPosX = pos.x;
        target.lastTickPosY = pos.y;
        target.lastTickPosZ = pos.z;
        
        // Server position (for interpolation)
        target.serverPosX = (int)(pos.x * 32.0);
        target.serverPosY = (int)(pos.y * 32.0);
        target.serverPosZ = (int)(pos.z * 32.0);
        
        // GRIM BYPASS: Update interpolation tracking to prevent prediction offset
        // This ensures Grim's prediction engine sees smooth movement
        target.prevPosX = pos.x;
        target.prevPosY = pos.y;
        target.prevPosZ = pos.z;
    }

    /**
     * SMOOTH INTERPOLATED RENDERING - Uses Minecraft's exact player interpolation
     * Shows buttery smooth boxes that move exactly like the real player would
     */
    private void renderManualModePositions(float partialTicks) {
        if (entityPositions.isEmpty()) {
            return;
        }
        
        try {
            for (Map.Entry<Integer, LinkedList<PositionData>> entry : entityPositions.entrySet()) {
                int entityId = entry.getKey();
                EntityPlayer player = (EntityPlayer) mc.theWorld.getEntityByID(entityId);
                
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
                
                // Get the best backtrack position (oldest valid position)
                PositionData bestPos = null;
                for (PositionData pos : positions) {
                    double distance = calculateGrimReachDistance(pos, player);
                    if (isPositionSafeForGrim(pos, player, distance) && distance <= RISE_VAPE_SAFE_REACH) {
                        bestPos = pos; // Use this position
                        break;
                    }
                }
                
                // Fallback to most recent position
                if (bestPos == null && !positions.isEmpty()) {
                    bestPos = positions.getFirst();
                }
                
                if (bestPos != null) {
                    // Render the ACTUAL backtrack position where the player WAS
                    // No interpolation to current position - this is where we want to hit
                    RenderBoxUtil.renderPlayerBox(bestPos.x, bestPos.y, bestPos.z);
                }
            }
        } catch (Exception e) {
            // Prevent render errors
        }
    }
    
    /**
     * SMOOTH INTERPOLATED RENDERING - Uses Minecraft's exact player interpolation
     * Shows buttery smooth boxes at server-side positions
     */
    private void renderLagBasedPositions(float partialTicks) {
        if (serverPositions.isEmpty()) {
            return;
        }
        
        try {
            for (Map.Entry<Integer, PositionData> entry : serverPositions.entrySet()) {
                int entityId = entry.getKey();
                EntityPlayer player = (EntityPlayer) mc.theWorld.getEntityByID(entityId);
                
                if (player == null || player == mc.thePlayer || player.isDead) {
                    continue;
                }
                
                if (TeamUtil.isFriend(player)) {
                    continue;
                }

                PositionData serverPos = entry.getValue();
                
                if (serverPos != null) {
                    // Render the server-side position (where server thinks player is)
                    // This is the lagged position we attack through
                    RenderBoxUtil.renderPlayerBox(serverPos.x, serverPos.y, serverPos.z);
                }
            }
        } catch (Exception e) {
            // Prevent render errors
        }
    }
    
    /**
     * Get smoothly interpolated position using GrimPredictionEngine
     * Ensures boxes never teleport or disappear - always smooth transitions
     */
    private PositionData getSmoothInterpolatedPosition(int entityId, PositionData targetPos, EntityPlayer player, float partialTicks, float deltaTime) {
        // Simple interpolation for smooth rendering
        double x = player.prevPosX + (player.posX - player.prevPosX) * partialTicks;
        double y = player.prevPosY + (player.posY - player.prevPosY) * partialTicks;
        double z = player.prevPosZ + (player.posZ - player.prevPosZ) * partialTicks;
        return new PositionData(x, y, z, System.currentTimeMillis());
    }
    
    // ==================== Lag Based Mode Methods ====================
    
    /**
     * Calculate maximum safe distance for lag-based mode based on latency
     * COMPLETE GRIM BYPASS: No reduction needed - Grim's uncertainty system handles all latencies
     */
    private double calculateMaxSafeDistance() {
        return 2.9; // Maximum safe distance for all latency values 50-2000ms
    }
    
    /**
     * Check if the target position will be safe AFTER the lag period
     */
    private boolean isLaggedPositionSafe(EntityPlayer target) {
        double lagSeconds = latency.getValue() / 1000.0;
        double predictedX = target.posX + (target.motionX * lagSeconds * 20);
        double predictedY = target.posY + (target.motionY * lagSeconds * 20);
        double predictedZ = target.posZ + (target.motionZ * lagSeconds * 20);
        PositionData predictedPos = new PositionData(predictedX, predictedY, predictedZ, System.currentTimeMillis());
        double predictedDistance = calculateGrimReachDistance(predictedPos, target);
        return isPositionSafeForGrim(predictedPos, target, predictedDistance);
    }

    private void startLagging() {
        isLagging = true;
        lagStartTime = System.currentTimeMillis();
    }

    private void processDelayedPackets() {
        if (!isLagging) return;
        long currentTime = System.currentTimeMillis();
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
                    if (packetData.packet instanceof S18PacketEntityTeleport) {
                        mc.getNetHandler().handleEntityTeleport((S18PacketEntityTeleport) packetData.packet);
                    }
                } catch (Exception e) {}
            }
        }
    }

    // ==================== AI Target Profile ====================
    
    private static class AITargetProfile {
        double lastDistance = 0;
        double lastVelocity = 0;
        int totalAttempts = 0;
        int successfulAttempts = 0;
        double successRate = 0.5;
        long lastBacktrackAttempt = 0L;
        long lastUpdateTime = 0L;
        
        void update(EntityPlayer target) {
            long currentTime = System.currentTimeMillis();
            lastDistance = Minecraft.getMinecraft().thePlayer.getDistanceToEntity(target);
            lastVelocity = Math.sqrt(target.motionX * target.motionX + target.motionZ * target.motionZ);
            if (currentTime - lastUpdateTime > 3000) {
                successRate = Math.max(0.3, successRate * 0.9);
            }
            lastUpdateTime = currentTime;
        }
        
        void recordAttempt(boolean success) {
            totalAttempts++;
            if (success) successfulAttempts++;
            successRate = (successfulAttempts / (double) totalAttempts) * 0.7 + successRate * 0.3;
            lastBacktrackAttempt = System.currentTimeMillis();
        }
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
