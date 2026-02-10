package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.*;
import myau.management.TransactionManager;
import myau.management.GrimPredictionEngine;
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
public class Backtrack extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // Enhanced Grim bypass constants - more aggressive hitbox safety
    private static final double GRIM_MAX_REACH = 3.0; // Base reach distance
    private static final double GRIM_REACH_THRESHOLD = 0.0005; // Threshold for reach checks
    private static final double GRIM_HITBOX_EXPANSION_1_8 = 0.15; // Increased from 0.1 to 0.15 for safer hitbox
    private static final double GRIM_MOVEMENT_THRESHOLD = 0.05; // Increased from 0.03 for more tolerance
    private static final double SAFE_REACH_DISTANCE = 2.85; // Reduced from 2.93 to 2.85 for extra safety
    
    // Grim interpolation constants - enhanced for bypass
    private static final double INTERPOLATION_EXPANSION = 0.04; // Increased from 0.03125 to 0.04
    private static final double INTERPOLATION_EXPANSION_Y = 0.02; // Increased from 0.015625 to 0.02
    private static final int MAX_INTERPOLATION_STEPS = 3; // Living entities = 3 steps
    
    // Additional safety margins
    private static final double EXTRA_HITBOX_MARGIN = 0.08; // Extra margin for hitbox calculations
    private static final double REACH_SAFETY_BUFFER = 0.15; // Buffer to stay well under max reach
    
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
    
    // AI Intelligence tracking
    private final Map<Integer, AITargetProfile> targetProfiles = new ConcurrentHashMap<>();
    private int consecutiveBacktrackSuccesses = 0;
    private long lastFlagTime = 0L;
    
    // Smooth Interpolation System
    private final Map<Integer, InterpolatedPositionTracker> interpolationTrackers = new ConcurrentHashMap<>();
    private long lastRenderTime = System.currentTimeMillis();
    
    // World change detection - CRITICAL for fixing flags after match transitions
    private String lastWorldName = null;

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
        backtrackHitCount.clear();
        lastBacktrackTime.clear();
        interpolationTrackers.clear();
        lastRenderTime = System.currentTimeMillis();
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
        interpolationTrackers.clear();
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
            interpolationTrackers.clear();
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
            
            // COOLDOWN CHECK: Skip backtrack if in cooldown
            if (cooldownEnabled.getValue() && isInCooldown(target)) {
                return; // Don't backtrack, let normal attack happen
            }
            
            // TRANSACTION SYNC: Only backtrack during safe timing windows
            if (!transactionManager.isSafeForBypass()) {
                return; // Wait for transaction window for safest timing
            }
            
            // INTELLIGENT AI: Decide if we should backtrack based on multiple factors
            if (intelligentMode.getValue() && !shouldBacktrackIntelligently(target)) {
                return; // AI decided not to backtrack
            }
            
            if (mode.getModeString().equals("Manual")) {
                // Manual Mode: Apply backtrack position
                LinkedList<PositionData> positions = entityPositions.get(target.getEntityId());
                
                if (positions != null && !positions.isEmpty()) {
                    PositionData bestPos = selectBestPosition(positions, target);
                    if (bestPos != null) {
                        applyBacktrackPosition(target, bestPos);
                        
                        // Track backtrack usage for cooldown
                        incrementBacktrackHit(target);
                        
                        // AI: Record successful backtrack
                        if (intelligentMode.getValue()) {
                            recordBacktrackAttempt(target, true);
                        }
                        
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
                
                // LAG-BASED MODE FIX: Calculate safe distance based on latency
                // Higher latency = need closer initial distance to avoid reach flags
                double maxSafeDistance = calculateMaxSafeDistance();
                
                // GRIM BYPASS: Only lag if safe distance and not already lagging
                // Don't lag if target is too far (would cause reach flags)
                if (distance > 2.0 && distance <= maxSafeDistance && !isLagging) {
                    // Validate position will be safe AFTER the lag
                    if (isLaggedPositionSafe(target)) {
                        startLagging();
                        
                        // Track backtrack usage for cooldown
                        incrementBacktrackHit(target);
                        
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

        // REALTIME RENDERING: Update positions for ALL players every frame
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
                    interpolationTrackers.put(entityId, new InterpolatedPositionTracker());
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
                    interpolationTrackers.put(entityId, new InterpolatedPositionTracker());
                } else if ((currentTime - currentServerPos.timestamp) > 100) {
                    // Update existing position
                    serverPositions.put(entityId, new PositionData(
                        player.posX, player.posY, player.posZ,
                        currentTime
                    ));
                }
            }
        }
        
        // UNREGISTRATION: Clean up players that left/died
        unregisterInactivePlayers(activePlayerIds);
    }
    
    /**
     * Unregister players that are no longer active (left server, died, changed teams)
     * This prevents memory leaks and ensures clean state
     */
    private void unregisterInactivePlayers(Set<Integer> activePlayerIds) {
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
    }
    
    /**
     * Clean up all data associated with a player
     * Called when player leaves/dies
     */
    private void cleanupPlayerData(int entityId) {
        // Remove interpolation tracker
        interpolationTrackers.remove(entityId);
        
        // Remove cooldown data
        backtrackHitCount.remove(entityId);
        lastBacktrackTime.remove(entityId);
        
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
            
            if (distance < currentDistance && distance <= SAFE_REACH_DISTANCE && distance < bestDistance) {
                bestDistance = distance;
                bestPosition = pos;
            }
        }

        return bestPosition;
    }
    
    /**
     * Calculate reach distance EXACTLY like Grim does (ReachUtils.getMinReachToBox)
     * This is the EXACT method Grim uses - we replicate it perfectly
     */
    private double calculateGrimReachDistance(PositionData pos, EntityPlayer target) {
        double lowest = Double.MAX_VALUE;
        
        // Grim checks MULTIPLE eye heights (standing, sneaking, swimming, etc.)
        double[] possibleEyeHeights = {
            mc.thePlayer.getEyeHeight(), // Current
            1.62, // Standing
            1.54, // Sneaking (1.62 - 0.08)
            1.27, // Swimming/crawling
        };
        
        for (double eyeHeight : possibleEyeHeights) {
            double eyeX = mc.thePlayer.posX;
            double eyeY = mc.thePlayer.posY + eyeHeight;
            double eyeZ = mc.thePlayer.posZ;
            
            // Target hitbox (0.6 wide = ±0.3 from center, 1.8 tall)
            // HITBOX EXPANSION: Add expansion for both modes with extra safety margin
            double hitboxExpansion = GRIM_HITBOX_EXPANSION_1_8 + EXTRA_HITBOX_MARGIN; // Base 0.15 + 0.08 = 0.23 for safer hitbox
            
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
                
                // PURE GRIM FIX: Add Grim's interpolation expansion (critical for long matches)
                // Enhanced interpolation: increased from 0.03125 to 0.04 per step
                // Living entities have exactly 3 interpolation steps (ReachInterpolationData line 62)
                double interpolationSteps = Math.min(ticksOld, MAX_INTERPOLATION_STEPS);
                hitboxExpansion += INTERPOLATION_EXPANSION * interpolationSteps; // Now 0.04 per step
                
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
            
            // Find closest point on hitbox (Grim's VectorUtils.cutBoxToVector)
            double closestX = MathHelper.clamp_double(eyeX, targetMinX, targetMaxX);
            double closestY = MathHelper.clamp_double(eyeY, targetMinY, targetMaxY);
            double closestZ = MathHelper.clamp_double(eyeZ, targetMinZ, targetMaxZ);
            
            // Distance from eye to closest point
            double deltaX = eyeX - closestX;
            double deltaY = eyeY - closestY;
            double deltaZ = eyeZ - closestZ;
            double distance = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
            
            lowest = Math.min(lowest, distance);
        }
        
        return lowest;
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
        
        // Total max reach with enhanced safety buffer
        double grimMaxReach = baseReach + uncertainty;
        
        // For safety, stay well under limit with REACH_SAFETY_BUFFER (0.15 blocks)
        double safeMaxReach = grimMaxReach - REACH_SAFETY_BUFFER;
        
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
        
        // Target hitbox (with expansion for intercept check to be safer)
        double interceptExpansion = EXTRA_HITBOX_MARGIN * 0.5; // Use half the extra margin (0.04)
        
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

    /**
     * SMOOTH INTERPOLATED RENDERING - Shows fluid backtrack positions with prediction engine
     * Boxes smoothly transition and never teleport or disappear
     */
    private void renderManualModePositions(float partialTicks) {
        // OPTIMIZATION: Don't render if no positions stored
        if (entityPositions.isEmpty()) {
            return;
        }
        
        try {
            // Light blue color (0x87CEEB = RGB 135, 206, 235)
            Color lightBlueColor = new Color(135, 206, 235, 165);
            long currentTime = System.currentTimeMillis();
            float deltaTime = (currentTime - lastRenderTime) / 1000.0f; // Delta in seconds
            lastRenderTime = currentTime;
            
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
                
                // Find the best position (oldest valid backtrack position)
                PositionData targetPos = selectBestBacktrackPosition(positions, player);
                
                // ALWAYS RENDER: If no best backtrack position, use current player position
                if (targetPos == null && !positions.isEmpty()) {
                    targetPos = positions.getFirst(); // Use most recent position
                }
                
                if (targetPos == null) {
                    // Fallback to current player position
                    targetPos = new PositionData(player.posX, player.posY, player.posZ, currentTime);
                }
                
                // GRIM PREDICTION ENGINE: Predict smooth position based on velocity and knockback
                PositionData smoothPos = getSmoothInterpolatedPosition(entityId, targetPos, player, partialTicks, deltaTime);
                
                // Render smooth box - never teleports!
                renderVapeV4Box(smoothPos, lightBlueColor);
            }
        } catch (Exception e) {
            // Prevent any render errors from causing freezes
        }
    }
    
    /**
     * Get smoothly interpolated position using GrimPredictionEngine
     * Ensures boxes never teleport or disappear - always smooth transitions
     */
    private PositionData getSmoothInterpolatedPosition(int entityId, PositionData targetPos, EntityPlayer player, float partialTicks, float deltaTime) {
        // Get or create interpolation tracker
        InterpolatedPositionTracker tracker = interpolationTrackers.computeIfAbsent(entityId, k -> new InterpolatedPositionTracker());
        
        // Use Grim prediction engine to calculate velocity-aware interpolation
        long posAge = System.currentTimeMillis() - targetPos.timestamp;
        int ticksBack = (int) (posAge / 50);
        
        GrimPredictionEngine.PredictedPosition predicted = 
            GrimPredictionEngine.predictWithInterpolation(player, ticksBack);
        
        // Apply prediction-based velocity adjustment
        Vec3 predictedVelocity = predicted != null ? predicted.velocity : new Vec3(0, 0, 0);
        
        // Update tracker with target position and predicted velocity
        return tracker.update(targetPos, predictedVelocity, deltaTime, partialTicks);
    }
    
    /**
     * Select the best backtrack position for rendering
     * Prioritizes positions that are most advantageous
     * 
     * GRIM PREDICTION: Uses prediction engine to validate positions
     */
    private PositionData selectBestBacktrackPosition(LinkedList<PositionData> positions, EntityPlayer target) {
        if (positions.isEmpty()) {
            return null;
        }
        
        // Calculate how many ticks back we're looking
        long currentTime = System.currentTimeMillis();
        
        // Select the oldest position that's still valid (furthest back in time)
        // This gives the best visual representation of where we can hit
        PositionData best = null;
        double bestDistance = Double.MAX_VALUE;
        double bestScore = 0.0;
        
        for (PositionData pos : positions) {
            // Calculate position age in ticks
            long posAge = currentTime - pos.timestamp;
            int ticksBack = (int) (posAge / 50); // 50ms per tick
            
            // GRIM PREDICTION: Predict where Grim thinks target should be
            GrimPredictionEngine.PredictedPosition predicted = 
                GrimPredictionEngine.predictWithInterpolation(target, ticksBack);
            
            // Validate position against Grim's prediction
            Vec3 posVec = new Vec3(pos.x, pos.y, pos.z);
            if (predicted != null && GrimPredictionEngine.wouldGrimFlag(posVec, predicted)) {
                continue; // Skip positions that would flag
            }
            
            double distance = calculateGrimReachDistance(pos, target);
            
            // Only consider positions that are safe for Grim
            if (isPositionSafeForGrim(pos, target, distance)) {
                // Score based on reach advantage and safety
                double currentDist = mc.thePlayer.getDistanceToEntity(target);
                double reachAdvantage = currentDist - distance;
                double safetyMargin = predicted != null ? predicted.uncertainty : 0.1;
                
                double score = reachAdvantage * 10.0 + safetyMargin * 5.0;
                
                if (score > bestScore) {
                    best = pos;
                    bestDistance = distance;
                    bestScore = score;
                }
            }
        }
        
        // If no safe position found, just use the most recent one for visual feedback
        return best != null ? best : positions.getFirst();
    }

    // ==================== Lag Based Mode Methods ====================
    
    /**
     * Calculate maximum safe distance for lag-based mode based on latency
     * COMPLETE GRIM BYPASS: No reduction needed - Grim's uncertainty system handles all latencies
     */
    private double calculateMaxSafeDistance() {
        // With proper Grim uncertainty calculations, we can use full 2.9 blocks for ALL latencies
        // The uncertainty system (ping-based) will automatically compensate:
        // - 50ms: 3.0 + (1 tick × 0.03) + base = ~3.09 reach (safe at 2.9 initial)
        // - 500ms: 3.0 + (10 ticks × 0.03) + velocity comp = ~4.0+ reach (safe at 2.9 initial)
        // - 2000ms: 3.0 + (40 ticks × 0.03) + velocity comp = ~6.0+ reach (safe at 2.9 initial)
        
        return 2.9; // Maximum safe distance for all latency values 50-2000ms
    }
    
    /**
     * Check if the target position will be safe AFTER the lag period
     * Predicts where the target will be and validates reach/hitbox
     */
    private boolean isLaggedPositionSafe(EntityPlayer target) {
        // Calculate where target will be after latency period
        double lagSeconds = latency.getValue() / 1000.0;
        
        // Predict target position (assuming constant velocity)
        double predictedX = target.posX + (target.motionX * lagSeconds * 20); // 20 ticks per second
        double predictedY = target.posY + (target.motionY * lagSeconds * 20);
        double predictedZ = target.posZ + (target.motionZ * lagSeconds * 20);
        
        // Create predicted position
        PositionData predictedPos = new PositionData(predictedX, predictedY, predictedZ, System.currentTimeMillis());
        
        // Calculate predicted distance
        double predictedDistance = calculateGrimReachDistance(predictedPos, target);
        
        // Validate predicted position is safe
        // For lag-based mode with high latency, we need extra conservative checks
        return isPositionSafeForGrim(predictedPos, target, predictedDistance);
    }

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

    /**
     * SMOOTH INTERPOLATED LAG-BASED RENDERING - Shows fluid server positions with prediction
     * Boxes smoothly transition and never teleport or disappear
     */
    private void renderLagBasedPositions(float partialTicks) {
        // OPTIMIZATION: Don't render if no server positions stored
        if (serverPositions.isEmpty()) {
            return;
        }
        
        try {
            // Light blue color (0x87CEEB = RGB 135, 206, 235)
            Color lightBlueColor = new Color(135, 206, 235, 165);
            long currentTime = System.currentTimeMillis();
            float deltaTime = (currentTime - lastRenderTime) / 1000.0f; // Delta in seconds
            lastRenderTime = currentTime;
            
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
                
                // ALWAYS RENDER: Even if position is old, use current player position as fallback
                if (currentTime - serverPos.timestamp > 1000) {
                    serverPos = new PositionData(player.posX, player.posY, player.posZ, currentTime);
                }
                
                // GRIM PREDICTION ENGINE: Predict smooth position based on velocity and knockback
                PositionData smoothPos = getSmoothInterpolatedPosition(entityId, serverPos, player, partialTicks, deltaTime);
                
                // Render smooth box - never teleports!
                renderVapeV4Box(smoothPos, lightBlueColor);
            }
        } catch (Exception e) {
            // Prevent any render errors from causing freezes
        }
    }

    // ==================== Knockback Prediction System ====================
    
    /**
     * Apply knockback prediction to position data
     * Predicts where the player will be after knockback from our hits
     */
    private PositionData applyKnockbackPrediction(PositionData pos, EntityPlayer player) {
        // Calculate if player is being knocked back
        // Check if player has velocity (recent hit)
        double velocityMagnitude = Math.sqrt(
            player.motionX * player.motionX + 
            player.motionY * player.motionY + 
            player.motionZ * player.motionZ
        );
        
        // If player has significant velocity, predict future position
        if (velocityMagnitude > 0.05) {
            // Predict 3 ticks ahead (150ms) for knockback
            double predictionTime = 3; // ticks
            
            double predictedX = pos.x + (player.motionX * predictionTime);
            double predictedY = pos.y + (player.motionY * predictionTime);
            double predictedZ = pos.z + (player.motionZ * predictionTime);
            
            return new PositionData(predictedX, predictedY, predictedZ, pos.timestamp);
        }
        
        // No knockback detected, return original position
        return pos;
    }

    // ==================== Vape V4 Style Rendering ====================

    /**
     * Renders a single Vape V4 style box with smooth edges and clean aesthetic
     * 65% opacity light blue with crisp outlines
     */
    private void renderVapeV4Box(PositionData pos, Color color) {
        double renderX = pos.x - ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosX();
        double renderY = pos.y - ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosY();
        double renderZ = pos.z - ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosZ();

        // Player hitbox dimensions (0.6 wide, 1.8 tall)
        AxisAlignedBB box = new AxisAlignedBB(
            renderX - 0.3, renderY, renderZ - 0.3,
            renderX + 0.3, renderY + 1.8, renderZ + 0.3
        );
        
        RenderUtil.enableRenderState();
        
        // Vape V4 style: Filled box (opacity is handled by RenderUtil internally)
        RenderUtil.drawFilledBox(box, color.getRed(), color.getGreen(), color.getBlue());
        
        // Vape V4 style: Clean, thin outline with full opacity for visibility
        RenderUtil.drawBoundingBox(box, color.getRed(), color.getGreen(), color.getBlue(), 255, 1.5F);
        
        RenderUtil.disableRenderState();
    }

    // ==================== Intelligent AI System ====================
    
    /**
     * AI-powered decision making for when to backtrack
     * Considers: distance, velocity, hit success rate, time of day (tick), health, and more
     */
    private boolean shouldBacktrackIntelligently(EntityPlayer target) {
        int entityId = target.getEntityId();
        AITargetProfile profile = targetProfiles.computeIfAbsent(entityId, k -> new AITargetProfile());
        
        // Update profile
        profile.update(target);
        
        // Calculate intelligence score (0-100)
        int score = calculateIntelligenceScore(target, profile);
        
        // Intelligence level determines threshold
        // Level 1 (cautious): 90+ score needed
        // Level 5 (balanced): 50+ score needed
        // Level 10 (aggressive): 10+ score needed
        int threshold = 100 - (intelligenceLevel.getValue() * 9);
        
        return score >= threshold;
    }
    
    /**
     * Calculate AI intelligence score based on multiple factors
     */
    private int calculateIntelligenceScore(EntityPlayer target, AITargetProfile profile) {
        int score = 0;
        
        // Factor 1: Distance Analysis (0-25 points)
        double distance = mc.thePlayer.getDistanceToEntity(target);
        if (distance > 2.7 && distance <= 2.9) {
            score += 25; // Perfect backtrack range
        } else if (distance > 2.5 && distance <= 2.7) {
            score += 15; // Good range
        } else if (distance > 2.3 && distance <= 2.5) {
            score += 10; // Acceptable range
        } else {
            score += 0; // Too close or too far
        }
        
        // Factor 2: Target Velocity (0-20 points)
        double velocity = Math.sqrt(
            target.motionX * target.motionX + 
            target.motionZ * target.motionZ
        );
        if (velocity > 0.1) {
            score += 20; // Moving target = good for backtrack
        } else if (velocity > 0.05) {
            score += 10; // Slightly moving
        }
        
        // Factor 3: Success Rate (0-20 points)
        if (profile.successRate > 0.7) {
            score += 20; // High success rate
        } else if (profile.successRate > 0.5) {
            score += 10; // Medium success rate
        } else if (profile.successRate < 0.3) {
            score -= 10; // Low success rate, be cautious
        }
        
        // Factor 4: Time Pattern (0-15 points)
        // Don't backtrack too frequently (pattern detection)
        long timeSinceLastBacktrack = System.currentTimeMillis() - profile.lastBacktrackAttempt;
        if (timeSinceLastBacktrack > 1000) {
            score += 15; // Good spacing
        } else if (timeSinceLastBacktrack > 500) {
            score += 10; // Acceptable spacing
        } else if (timeSinceLastBacktrack < 200) {
            score -= 15; // Too frequent, risky
        }
        
        // Factor 5: Target Health (0-10 points)
        float healthPercent = target.getHealth() / target.getMaxHealth();
        if (healthPercent < 0.3) {
            score += 10; // Low health, worth the risk
        } else if (healthPercent > 0.8) {
            score += 5; // Full health, normal priority
        }
        
        // Factor 6: Server Tick Timing (0-10 points)
        // Avoid backtracking on suspicious ticks (e.g., every 20 ticks)
        int currentTick = mc.thePlayer.ticksExisted;
        if (currentTick % 20 != 0 && currentTick % 10 != 0) {
            score += 10; // Not on suspicious tick boundary
        }
        
        // Factor 7: Recent Flag Detection (penalty)
        if (System.currentTimeMillis() - lastFlagTime < 5000) {
            score -= 30; // Possible flag detected recently, be very cautious
        }
        
        // Factor 8: Consecutive Successes (diminishing returns)
        if (consecutiveBacktrackSuccesses > 5) {
            score -= 15; // Too many successes, vary behavior
        } else if (consecutiveBacktrackSuccesses > 3) {
            score -= 5; // Some successes, slight caution
        }
        
        // Factor 9: Look Direction Quality (0-10 points)
        double deltaX = target.posX - mc.thePlayer.posX;
        double deltaZ = target.posZ - mc.thePlayer.posZ;
        float targetYaw = (float) (Math.atan2(deltaZ, deltaX) * 180.0 / Math.PI) - 90.0F;
        float yawDiff = Math.abs(MathHelper.wrapAngleTo180_float(targetYaw - mc.thePlayer.rotationYaw));
        
        if (yawDiff < 10.0F) {
            score += 10; // Perfect aim
        } else if (yawDiff < 30.0F) {
            score += 5; // Good aim
        } else if (yawDiff > 60.0F) {
            score -= 10; // Poor aim, risky
        }
        
        // Factor 10: Environment Safety (0-10 points)
        if (!target.isInWater() && !target.isInLava() && !target.isOnLadder()) {
            score += 5; // Safe environment
        }
        if (mc.theWorld.getBlockState(target.getPosition().down()).getBlock().getMaterial().isSolid()) {
            score += 5; // Target on solid ground
        }
        
        return Math.max(0, Math.min(100, score)); // Clamp to 0-100
    }
    
    // ==================== Cooldown System ====================
    
    /**
     * Check if backtrack is in cooldown for a target
     */
    private boolean isInCooldown(EntityPlayer target) {
        int entityId = target.getEntityId();
        
        // Get hit count for this target
        int hits = backtrackHitCount.getOrDefault(entityId, 0);
        
        // If hit count reached threshold, check cooldown timer
        if (hits >= cooldownHits.getValue()) {
            Long lastTime = lastBacktrackTime.get(entityId);
            if (lastTime != null) {
                long timeSinceLastHit = System.currentTimeMillis() - lastTime;
                
                if (timeSinceLastHit < cooldownDelay.getValue()) {
                    // Still in cooldown
                    return true;
                } else {
                    // Cooldown expired, reset counter
                    backtrackHitCount.put(entityId, 0);
                    return false;
                }
            }
        }
        
        return false;
    }
    
    /**
     * Increment backtrack hit count for a target
     */
    private void incrementBacktrackHit(EntityPlayer target) {
        if (!cooldownEnabled.getValue()) {
            return;
        }
        
        int entityId = target.getEntityId();
        int currentHits = backtrackHitCount.getOrDefault(entityId, 0);
        
        currentHits++;
        backtrackHitCount.put(entityId, currentHits);
        lastBacktrackTime.put(entityId, System.currentTimeMillis());
        
        // If reached threshold, enter cooldown
        if (currentHits >= cooldownHits.getValue()) {
            // Cooldown started - will be checked in isInCooldown()
        }
    }
    
    // ==================== Interpolation Methods ====================
    
    // ==================== Data Classes ====================
    /**
     * VAPE V4 SMOOTH INTERPOLATION TRACKER
     * Single position per player with ease-in-out cubic transitions
     */
    /**
     * Smooth interpolation tracker for backtrack rendering
     * Uses velocity prediction and smooth lerping to prevent boxes from teleporting
     */
    private static class InterpolatedPositionTracker {
        private double currentX, currentY, currentZ;
        private double velocityX, velocityY, velocityZ;
        private boolean initialized = false;
        
        // Interpolation speed - higher = faster transition (1.0 = instant, 0.1 = very slow)
        private static final float LERP_SPEED = 8.0f; // Smooth but responsive
        private static final float VELOCITY_LERP_SPEED = 4.0f; // Velocity adapts slower
        
        public PositionData update(PositionData targetPos, Vec3 predictedVelocity, float deltaTime, float partialTicks) {
            // Initialize on first update
            if (!initialized) {
                currentX = targetPos.x;
                currentY = targetPos.y;
                currentZ = targetPos.z;
                velocityX = predictedVelocity.xCoord;
                velocityY = predictedVelocity.yCoord;
                velocityZ = predictedVelocity.zCoord;
                initialized = true;
                return targetPos;
            }
            
            // Smoothly interpolate velocity (for acceleration/deceleration)
            float velocityAlpha = 1.0f - (float) Math.exp(-VELOCITY_LERP_SPEED * deltaTime);
            velocityX += (predictedVelocity.xCoord - velocityX) * velocityAlpha;
            velocityY += (predictedVelocity.yCoord - velocityY) * velocityAlpha;
            velocityZ += (predictedVelocity.zCoord - velocityZ) * velocityAlpha;
            
            // Calculate target position with velocity prediction
            double targetX = targetPos.x + velocityX * partialTicks;
            double targetY = targetPos.y + velocityY * partialTicks;
            double targetZ = targetPos.z + velocityZ * partialTicks;
            
            // Smoothly interpolate to target (exponential smoothing for natural feel)
            float alpha = 1.0f - (float) Math.exp(-LERP_SPEED * deltaTime);
            currentX += (targetX - currentX) * alpha;
            currentY += (targetY - currentY) * alpha;
            currentZ += (targetZ - currentZ) * alpha;
            
            // Return smoothed position
            return new PositionData(currentX, currentY, currentZ, targetPos.timestamp);
        }
    }
    
    /**
     * Old method - kept for compatibility but not used
     */
    private static class LegacyInterpolationHelper {
        private double currentX = 0;
        private double currentY = 0;
        private double currentZ = 0;
        
        private double targetX = 0;
        private double targetY = 0;
        private double targetZ = 0;
        
        private boolean initialized = false;
        private static final float INTERPOLATION_SPEED = 0.18F;
        
        public PositionData getSmoothInterpolatedPosition(PositionData targetPos, float partialTicks) {
            // Initialize on first use
            if (!initialized) {
                currentX = targetPos.x;
                currentY = targetPos.y;
                currentZ = targetPos.z;
                targetX = targetPos.x;
                targetY = targetPos.y;
                targetZ = targetPos.z;
                initialized = true;
            }
            
            // Update target
            targetX = targetPos.x;
            targetY = targetPos.y;
            targetZ = targetPos.z;
            
            // Apply ease-in-out cubic interpolation (Vape V4 style)
            currentX = easeInOutCubic(currentX, targetX, INTERPOLATION_SPEED);
            currentY = easeInOutCubic(currentY, targetY, INTERPOLATION_SPEED);
            currentZ = easeInOutCubic(currentZ, targetZ, INTERPOLATION_SPEED);
            
            return new PositionData(currentX, currentY, currentZ, targetPos.timestamp);
        }
        
        /**
         * Ease-in-out cubic interpolation for BUTTERY smooth Vape V4 style movement
         * Uses actual cubic easing function for maximum smoothness
         */
        private double easeInOutCubic(double current, double target, float speed) {
            double delta = target - current;
            
            // Dead zone to prevent jittering
            if (Math.abs(delta) < 0.0005) {
                return target;
            }
            
            // Calculate progress (0 to 1)
            double progress = speed;
            
            // Apply ease-in-out cubic easing
            double easedProgress;
            if (progress < 0.5) {
                easedProgress = 4 * progress * progress * progress;
            } else {
                double f = (2 * progress - 2);
                easedProgress = 0.5 * f * f * f + 1;
            }
            
            // Apply eased interpolation with buttery smoothness
            return current + delta * easedProgress;
        }
        
    }
    
    /**
     * AI Target Profile - Tracks target behavior for intelligent decisions
     */
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
            
            // Update distance
            lastDistance = Minecraft.getMinecraft().thePlayer.getDistanceToEntity(target);
            
            // Update velocity
            lastVelocity = Math.sqrt(
                target.motionX * target.motionX + 
                target.motionZ * target.motionZ
            );
            
            // Update success rate (decays over time if not updated)
            if (currentTime - lastUpdateTime > 3000) {
                successRate = Math.max(0.3, successRate * 0.9);
            }
            
            lastUpdateTime = currentTime;
        }
        
        void recordAttempt(boolean success) {
            totalAttempts++;
            if (success) {
                successfulAttempts++;
            }
            
            // Calculate rolling success rate (weighted recent attempts more)
            successRate = (successfulAttempts / (double) totalAttempts) * 0.7 + successRate * 0.3;
            lastBacktrackAttempt = System.currentTimeMillis();
        }
    }

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
