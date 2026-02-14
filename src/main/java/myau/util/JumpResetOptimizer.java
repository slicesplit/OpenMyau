package myau.util;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.Vec3;

/**
 * Jump Reset Optimizer - Brutal 1.8 Knockback Prediction
 * 
 * Advanced prediction system specifically for 1.8 combat mechanics.
 * Analyzes attack patterns, player movement, and timing to predict
 * the exact moment to jump for maximum KB reduction.
 * 
 * 1.8 Knockback Formula:
 * - Base knockback applied to current velocity
 * - Jumping resets Y velocity to 0.42
 * - If jumped right before hit, horizontal velocity also minimized
 * - Result: Dramatically reduced knockback
 */
public class JumpResetOptimizer {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // Attack pattern tracking
    private static final int PATTERN_HISTORY_SIZE = 20;
    private static final AttackPattern[] attackHistory = new AttackPattern[PATTERN_HISTORY_SIZE];
    private static int historyIndex = 0;
    
    // Timing constants (1.8 specific)
    private static final double PLAYER_REACH = 3.0;
    private static final double SPRINT_REACH_BONUS = 0.5;
    private static final double JUMP_Y_VELOCITY = 0.42;
    private static final int PERFECT_TIMING_WINDOW = 50; // 50ms = 1 tick
    
    /**
     * Attack pattern data
     */
    public static class AttackPattern {
        public double distance;
        public double approachSpeed;
        public boolean attackerSprinting;
        public long timeBetweenHits;
        public int ticksBeforeHit;
        public boolean wasSuccessful;
        public long timestamp;
        
        public AttackPattern(double distance, double approachSpeed, boolean sprinting, int ticksBeforeHit) {
            this.distance = distance;
            this.approachSpeed = approachSpeed;
            this.attackerSprinting = sprinting;
            this.ticksBeforeHit = ticksBeforeHit;
            this.timestamp = System.currentTimeMillis();
        }
    }
    
    /**
     * Prediction result with confidence score
     */
    public static class JumpPrediction {
        public int ticksUntilJump;
        public double confidence; // 0.0 to 1.0
        public String reason;
        public boolean shouldJump;
        
        public JumpPrediction(int ticks, double confidence, String reason, boolean shouldJump) {
            this.ticksUntilJump = ticks;
            this.confidence = confidence;
            this.reason = reason;
            this.shouldJump = shouldJump;
        }
    }
    
    /**
     * MAIN PREDICTION: Calculate optimal jump timing
     */
    public static JumpPrediction predictOptimalJumpTiming(EntityPlayer attacker, EntityPlayer player) {
        if (attacker == null || player == null) {
            return new JumpPrediction(0, 0.0, "Invalid entities", false);
        }
        
        // Calculate current combat state
        double distance = player.getDistanceToEntity(attacker);
        Vec3 attackerVelocity = new Vec3(attacker.motionX, attacker.motionY, attacker.motionZ);
        Vec3 playerVelocity = new Vec3(player.motionX, player.motionY, player.motionZ);
        
        // Calculate approach vector
        Vec3 toPlayer = new Vec3(
            player.posX - attacker.posX,
            player.posY - attacker.posY,
            player.posZ - attacker.posZ
        ).normalize();
        
        // Calculate approach speed (horizontal only for 1.8)
        double horizontalSpeed = Math.sqrt(
            attackerVelocity.xCoord * attackerVelocity.xCoord +
            attackerVelocity.zCoord * attackerVelocity.zCoord
        );
        
        // Determine effective reach
        double effectiveReach = PLAYER_REACH;
        if (attacker.isSprinting()) {
            effectiveReach += SPRINT_REACH_BONUS;
            horizontalSpeed *= 1.3; // Sprint multiplier
        }
        
        // BRUTAL PREDICTION: Multi-method analysis
        
        // Method 1: Physics-based prediction
        int physicsTicks = predictByPhysics(distance, horizontalSpeed, effectiveReach);
        
        // Method 2: Pattern recognition
        int patternTicks = predictByPattern(distance, horizontalSpeed, attacker.isSprinting());
        
        // Method 3: Attack cooldown analysis
        int cooldownTicks = predictByAttackCooldown(attacker);
        
        // Method 4: Player behavior analysis
        int behaviorTicks = predictByBehavior(attacker, distance);
        
        // Combine predictions with weighted average
        double confidence = calculateCombinedConfidence(physicsTicks, patternTicks, cooldownTicks, behaviorTicks);
        int finalTicks = calculateWeightedTiming(physicsTicks, patternTicks, cooldownTicks, behaviorTicks, confidence);
        
        // Validate prediction
        boolean shouldJump = validatePrediction(finalTicks, distance, confidence);
        
        String reason = generateReason(finalTicks, confidence, physicsTicks, patternTicks);
        
        return new JumpPrediction(finalTicks, confidence, reason, shouldJump);
    }
    
    /**
     * Method 1: Physics-based prediction
     */
    private static int predictByPhysics(double distance, double approachSpeed, double effectiveReach) {
        if (distance <= effectiveReach) {
            return 0; // Already in range - immediate
        }
        
        if (approachSpeed < 0.05) {
            return -1; // Not approaching
        }
        
        // Calculate time to reach attack range
        double distanceToClose = distance - effectiveReach;
        double timeInSeconds = distanceToClose / (approachSpeed * 20.0); // MC ticks per second
        
        // Convert to ticks and subtract reaction time
        int ticks = (int) Math.ceil(timeInSeconds);
        
        // Account for attack wind-up (1 tick in 1.8)
        return Math.max(0, ticks - 1);
    }
    
    /**
     * Method 2: Pattern recognition from history
     */
    private static int predictByPattern(double distance, double approachSpeed, boolean sprinting) {
        // Find similar historical patterns
        int matchCount = 0;
        int totalTicks = 0;
        
        for (int i = 0; i < PATTERN_HISTORY_SIZE; i++) {
            AttackPattern pattern = attackHistory[i];
            if (pattern == null || !pattern.wasSuccessful) continue;
            
            // Check similarity
            if (Math.abs(pattern.distance - distance) < 1.0 &&
                Math.abs(pattern.approachSpeed - approachSpeed) < 0.2 &&
                pattern.attackerSprinting == sprinting) {
                
                totalTicks += pattern.ticksBeforeHit;
                matchCount++;
            }
        }
        
        if (matchCount == 0) return -1; // No pattern found
        
        return totalTicks / matchCount; // Average
    }
    
    /**
     * Method 3: Attack cooldown prediction (1.8 has no cooldown, but track rhythm)
     */
    private static int predictByAttackCooldown(EntityPlayer attacker) {
        // In 1.8, analyze attack rhythm from history
        long currentTime = System.currentTimeMillis();
        
        // Find last attack from this player
        for (int i = 0; i < PATTERN_HISTORY_SIZE; i++) {
            AttackPattern pattern = attackHistory[i];
            if (pattern == null) continue;
            
            long timeSinceAttack = currentTime - pattern.timestamp;
            
            // If recent attack (< 1 second), predict based on typical CPS
            if (timeSinceAttack < 1000) {
                // Assume 12-16 CPS average, so ~60-80ms between attacks
                long avgTimeBetweenHits = 70; // 14 CPS
                int ticksSinceLastHit = (int) (timeSinceAttack / 50);
                int ticksPerAttack = (int) (avgTimeBetweenHits / 50);
                
                return Math.max(0, ticksPerAttack - ticksSinceLastHit);
            }
        }
        
        return -1; // No cooldown data
    }
    
    /**
     * Method 4: Behavior-based prediction
     */
    private static int predictByBehavior(EntityPlayer attacker, double distance) {
        // Analyze attacker's current actions
        int ticks = 2; // Default: assume attack in 2 ticks
        
        // If sprinting toward us, faster attack
        if (attacker.isSprinting() && distance < 4.0) {
            ticks = 1;
        }
        
        // If very close, immediate
        if (distance < 3.0) {
            ticks = 0;
        }
        
        // If moving away, no attack expected
        Vec3 attackerVelocity = new Vec3(attacker.motionX, attacker.motionY, attacker.motionZ);
        Vec3 toUs = new Vec3(
            mc.thePlayer.posX - attacker.posX,
            mc.thePlayer.posY - attacker.posY,
            mc.thePlayer.posZ - attacker.posZ
        ).normalize();
        
        double movementToward = attackerVelocity.dotProduct(toUs);
        if (movementToward < 0) {
            return -1; // Moving away
        }
        
        return ticks;
    }
    
    /**
     * Calculate combined confidence from all methods
     */
    private static double calculateCombinedConfidence(int physics, int pattern, int cooldown, int behavior) {
        double confidence = 0.0;
        int methodsAgreeing = 0;
        
        // If multiple methods agree (within 1 tick), high confidence
        if (physics >= 0) {
            confidence += 0.3;
            if (pattern >= 0 && Math.abs(physics - pattern) <= 1) methodsAgreeing++;
            if (behavior >= 0 && Math.abs(physics - behavior) <= 1) methodsAgreeing++;
        }
        
        if (pattern >= 0) {
            confidence += 0.4; // Pattern recognition most reliable
        }
        
        if (cooldown >= 0) {
            confidence += 0.15;
        }
        
        if (behavior >= 0) {
            confidence += 0.15;
        }
        
        // Boost confidence if methods agree
        if (methodsAgreeing >= 2) {
            confidence = Math.min(1.0, confidence + 0.3);
        }
        
        return confidence;
    }
    
    /**
     * Calculate weighted timing from all methods
     */
    private static int calculateWeightedTiming(int physics, int pattern, int cooldown, int behavior, double confidence) {
        double totalWeight = 0.0;
        double weightedSum = 0.0;
        
        // Weight each method by reliability
        if (physics >= 0) {
            double weight = 0.3;
            weightedSum += physics * weight;
            totalWeight += weight;
        }
        
        if (pattern >= 0) {
            double weight = 0.4; // Pattern most important
            weightedSum += pattern * weight;
            totalWeight += weight;
        }
        
        if (cooldown >= 0) {
            double weight = 0.15;
            weightedSum += cooldown * weight;
            totalWeight += weight;
        }
        
        if (behavior >= 0) {
            double weight = 0.15;
            weightedSum += behavior * weight;
            totalWeight += weight;
        }
        
        if (totalWeight == 0.0) return 0;
        
        return (int) Math.round(weightedSum / totalWeight);
    }
    
    /**
     * Validate prediction is reasonable
     */
    private static boolean validatePrediction(int ticks, double distance, double confidence) {
        // Don't jump if prediction unreliable
        if (confidence < 0.5) return false;
        
        // Don't jump if too far away
        if (distance > 6.0) return false;
        
        // Don't jump if timing is too far out (>5 ticks = 250ms)
        if (ticks > 5) return false;
        
        // Don't jump if player not on ground (checked in main module)
        if (!mc.thePlayer.onGround) return false;
        
        return true;
    }
    
    /**
     * Generate reason string for debugging
     */
    private static String generateReason(int finalTicks, double confidence, int physicsTicks, int patternTicks) {
        StringBuilder reason = new StringBuilder();
        reason.append(finalTicks).append(" ticks, ");
        reason.append(String.format("%.0f%% conf", confidence * 100));
        
        if (physicsTicks >= 0) {
            reason.append(", Physics: ").append(physicsTicks);
        }
        if (patternTicks >= 0) {
            reason.append(", Pattern: ").append(patternTicks);
        }
        
        return reason.toString();
    }
    
    /**
     * Record attack for pattern learning
     */
    public static void recordAttack(double distance, double approachSpeed, boolean sprinting, int ticksBeforeHit, boolean successful) {
        AttackPattern pattern = new AttackPattern(distance, approachSpeed, sprinting, ticksBeforeHit);
        pattern.wasSuccessful = successful;
        
        attackHistory[historyIndex] = pattern;
        historyIndex = (historyIndex + 1) % PATTERN_HISTORY_SIZE;
    }
    
    /**
     * Calculate KB reduction effectiveness
     * Returns 0.0-1.0 (1.0 = perfect KB reduction)
     */
    public static double calculateKBReduction(long timeSinceJump) {
        // Perfect window: 0-50ms (1 tick)
        if (timeSinceJump <= PERFECT_TIMING_WINDOW) {
            return 1.0; // Perfect timing
        }
        
        // Acceptable window: 50-100ms (2 ticks)
        if (timeSinceJump <= PERFECT_TIMING_WINDOW * 2) {
            return 0.7; // Good timing
        }
        
        // Early jump: 100-150ms (3 ticks)
        if (timeSinceJump <= PERFECT_TIMING_WINDOW * 3) {
            return 0.4; // Partial effectiveness
        }
        
        // Too early or too late
        return 0.0;
    }
    
    /**
     * Reset pattern history
     */
    public static void reset() {
        for (int i = 0; i < PATTERN_HISTORY_SIZE; i++) {
            attackHistory[i] = null;
        }
        historyIndex = 0;
    }
}
