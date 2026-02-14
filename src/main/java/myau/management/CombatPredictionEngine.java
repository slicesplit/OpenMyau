package myau.management;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.Vec3;

/**
 * Combat Prediction Engine - Advanced combat decision making for HitSelect
 * 
 * This engine uses machine learning-style prediction to determine:
 * - When to W-tap for maximum KB reduction
 * - When to block vs attack
 * - Optimal timing for 20 CPS god defeater combos
 * 
 * Works in tandem with GrimPredictionEngine for bypass safety
 */
public class CombatPredictionEngine {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // Combat state tracking
    private static final int HISTORY_SIZE = 20;
    private static final CombatState[] stateHistory = new CombatState[HISTORY_SIZE];
    private static int historyIndex = 0;
    
    // Prediction weights (tuned for optimal performance)
    private static final double VELOCITY_WEIGHT = 0.35;
    private static final double DISTANCE_WEIGHT = 0.25;
    private static final double HEALTH_WEIGHT = 0.20;
    private static final double MOMENTUM_WEIGHT = 0.20;
    
    // Combat thresholds
    private static final double WTAP_VELOCITY_THRESHOLD = 0.15;
    private static final double BLOCK_DAMAGE_THRESHOLD = 0.5;
    private static final double AGGRESSIVE_DISTANCE = 3.0;
    
    /**
     * Combat state snapshot for prediction analysis
     */
    public static class CombatState {
        public Vec3 playerVelocity;
        public Vec3 targetVelocity;
        public double distance;
        public float playerHealth;
        public float targetHealth;
        public boolean playerSprinting;
        public boolean targetSprinting;
        public int playerHurtTime;
        public int targetHurtTime;
        public long timestamp;
        public boolean didWTap;
        public boolean didBlock;
        public double kbReceived;
        
        public CombatState(EntityPlayer player, EntityPlayer target) {
            this.playerVelocity = new Vec3(player.motionX, player.motionY, player.motionZ);
            this.targetVelocity = new Vec3(target.motionX, target.motionY, target.motionZ);
            this.distance = player.getDistanceToEntity(target);
            this.playerHealth = player.getHealth();
            this.targetHealth = target.getHealth();
            this.playerSprinting = player.isSprinting();
            this.targetSprinting = target.isSprinting();
            this.playerHurtTime = player.hurtTime;
            this.targetHurtTime = target.hurtTime;
            this.timestamp = System.currentTimeMillis();
        }
    }
    
    /**
     * Combat decision recommendation
     */
    public static class CombatDecision {
        public boolean shouldWTap;
        public boolean shouldBlock;
        public int blockDuration; // milliseconds
        public double confidence; // 0.0 to 1.0
        public String reason;
        
        public CombatDecision(boolean shouldWTap, boolean shouldBlock, int blockDuration, double confidence, String reason) {
            this.shouldWTap = shouldWTap;
            this.shouldBlock = shouldBlock;
            this.blockDuration = blockDuration;
            this.confidence = confidence;
            this.reason = reason;
        }
    }
    
    /**
     * Predict optimal combat action using historical data and current state
     */
    public static CombatDecision predictOptimalAction(EntityPlayer player, EntityPlayer target) {
        if (player == null || target == null) {
            return new CombatDecision(false, false, 0, 0.0, "Invalid entities");
        }
        
        CombatState currentState = new CombatState(player, target);
        recordState(currentState);
        
        // Multi-factor analysis
        double wTapScore = calculateWTapScore(currentState);
        double blockScore = calculateBlockScore(currentState);
        
        // Determine actions
        boolean shouldWTap = wTapScore > 0.6;
        boolean shouldBlock = blockScore > 0.5;
        int blockDuration = calculateBlockDuration(currentState, blockScore);
        
        // Calculate confidence based on historical accuracy
        double confidence = calculateConfidence(currentState);
        
        // Generate reason
        String reason = generateReason(wTapScore, blockScore, currentState);
        
        return new CombatDecision(shouldWTap, shouldBlock, blockDuration, confidence, reason);
    }
    
    /**
     * Calculate W-tap score (0.0 to 1.0)
     * Higher score = more beneficial to W-tap
     */
    private static double calculateWTapScore(CombatState state) {
        double score = 0.0;
        
        // Factor 1: Target velocity (higher = more beneficial)
        double targetSpeed = Math.sqrt(
            state.targetVelocity.xCoord * state.targetVelocity.xCoord +
            state.targetVelocity.zCoord * state.targetVelocity.zCoord
        );
        double velocityScore = Math.min(1.0, targetSpeed / WTAP_VELOCITY_THRESHOLD);
        score += velocityScore * VELOCITY_WEIGHT;
        
        // Factor 2: Distance (closer = more beneficial)
        double distanceScore = 1.0 - Math.min(1.0, state.distance / AGGRESSIVE_DISTANCE);
        score += distanceScore * DISTANCE_WEIGHT;
        
        // Factor 3: Target sprinting (if sprinting, W-tap to match)
        if (state.targetSprinting) {
            score += 0.20;
        }
        
        // Factor 4: Player hurt (if hurt, W-tap to reduce KB)
        if (state.playerHurtTime > 0 && state.playerHurtTime < 5) {
            score += 0.15;
        }
        
        // Factor 5: Momentum advantage (if we have velocity, W-tap to maintain)
        double playerSpeed = Math.sqrt(
            state.playerVelocity.xCoord * state.playerVelocity.xCoord +
            state.playerVelocity.zCoord * state.playerVelocity.zCoord
        );
        if (playerSpeed > targetSpeed) {
            score += 0.10;
        }
        
        return Math.min(1.0, score);
    }
    
    /**
     * Calculate block score (0.0 to 1.0)
     * Higher score = more beneficial to block
     */
    private static double calculateBlockScore(CombatState state) {
        double score = 0.0;
        
        // Factor 1: Health disadvantage (lower health = more blocking)
        double healthRatio = state.playerHealth / Math.max(1.0f, state.targetHealth);
        if (healthRatio < 0.7) {
            score += (1.0 - healthRatio) * HEALTH_WEIGHT;
        }
        
        // Factor 2: Target attacking (if target just hit us, block)
        if (state.playerHurtTime > 0) {
            score += 0.30;
        }
        
        // Factor 3: Distance (at optimal range, less blocking needed)
        if (state.distance < 2.5) {
            score += 0.15;
        }
        
        // Factor 4: Taking damage prediction (velocity toward target = likely hit)
        double approachSpeed = -state.playerVelocity.dotProduct(
            new Vec3(
                state.targetVelocity.xCoord - state.playerVelocity.xCoord,
                0,
                state.targetVelocity.zCoord - state.playerVelocity.zCoord
            ).normalize()
        );
        if (approachSpeed > 0) {
            score += approachSpeed * 0.20;
        }
        
        return Math.min(1.0, score);
    }
    
    /**
     * Calculate optimal block duration based on state
     */
    private static int calculateBlockDuration(CombatState state, double blockScore) {
        if (blockScore < 0.5) return 0;
        
        // Base duration: 50-150ms
        int baseDuration = 50;
        
        // Extend if health is low
        if (state.playerHealth < 6.0f) {
            baseDuration += 50;
        }
        
        // Extend if just took damage
        if (state.playerHurtTime > 0) {
            baseDuration += 30;
        }
        
        // Shorten if we have momentum advantage
        double playerSpeed = Math.sqrt(
            state.playerVelocity.xCoord * state.playerVelocity.xCoord +
            state.playerVelocity.zCoord * state.playerVelocity.zCoord
        );
        if (playerSpeed > 0.2) {
            baseDuration -= 20;
        }
        
        return Math.max(30, Math.min(150, baseDuration));
    }
    
    /**
     * Calculate confidence in prediction based on historical accuracy
     */
    private static double calculateConfidence(CombatState state) {
        // Analyze last 5 similar states
        int similarStates = 0;
        int successfulPredictions = 0;
        
        for (int i = 0; i < HISTORY_SIZE; i++) {
            CombatState historical = stateHistory[i];
            if (historical == null) continue;
            
            // Check similarity (distance and health within 20%)
            if (Math.abs(historical.distance - state.distance) < 0.6 &&
                Math.abs(historical.playerHealth - state.playerHealth) < 4.0f) {
                
                similarStates++;
                
                // Check if historical decision reduced KB
                if (historical.didWTap && historical.kbReceived < 0.3) {
                    successfulPredictions++;
                }
            }
        }
        
        if (similarStates == 0) return 0.5; // Medium confidence
        
        return (double) successfulPredictions / similarStates;
    }
    
    /**
     * Generate human-readable reason for decision
     */
    private static String generateReason(double wTapScore, double blockScore, CombatState state) {
        StringBuilder reason = new StringBuilder();
        
        if (wTapScore > 0.6) {
            reason.append("W-tap: ");
            if (state.targetSprinting) reason.append("Target sprinting, ");
            if (state.playerHurtTime > 0) reason.append("Reduce KB, ");
            if (state.distance < 3.0) reason.append("Close range, ");
            reason.append(String.format("Score: %.2f", wTapScore));
        }
        
        if (blockScore > 0.5) {
            if (reason.length() > 0) reason.append(" | ");
            reason.append("Block: ");
            if (state.playerHurtTime > 0) reason.append("Taking damage, ");
            if (state.playerHealth < state.targetHealth * 0.7) reason.append("Health disadvantage, ");
            reason.append(String.format("Score: %.2f", blockScore));
        }
        
        if (reason.length() == 0) {
            reason.append("No action needed");
        }
        
        return reason.toString();
    }
    
    /**
     * Record combat state for learning
     */
    private static void recordState(CombatState state) {
        stateHistory[historyIndex] = state;
        historyIndex = (historyIndex + 1) % HISTORY_SIZE;
    }
    
    /**
     * Update combat outcome for learning
     * Call this after a hit to record KB received
     */
    public static void recordOutcome(boolean didWTap, boolean didBlock, double kbReceived) {
        // Update most recent state
        int lastIndex = (historyIndex - 1 + HISTORY_SIZE) % HISTORY_SIZE;
        CombatState lastState = stateHistory[lastIndex];
        
        if (lastState != null) {
            lastState.didWTap = didWTap;
            lastState.didBlock = didBlock;
            lastState.kbReceived = kbReceived;
        }
    }
    
    /**
     * Calculate optimal CPS for current combat situation
     * Returns: Recommended clicks per second (12-20)
     */
    public static int calculateOptimalCPS(CombatState state) {
        // Base CPS: 16 (good balance)
        int baseCPS = 16;
        
        // Increase CPS if:
        // - Close range (< 3 blocks)
        if (state.distance < 3.0) {
            baseCPS += 2;
        }
        
        // - Target low health (finish quickly)
        if (state.targetHealth < 6.0f) {
            baseCPS += 2;
        }
        
        // - We have momentum advantage
        double playerSpeed = Math.sqrt(
            state.playerVelocity.xCoord * state.playerVelocity.xCoord +
            state.playerVelocity.zCoord * state.playerVelocity.zCoord
        );
        if (playerSpeed > 0.2) {
            baseCPS += 1;
        }
        
        // Decrease CPS if:
        // - We're blocking (can't attack as fast)
        if (state.playerHealth < state.targetHealth * 0.7) {
            baseCPS -= 2;
        }
        
        // Clamp to 12-20 range
        return Math.max(12, Math.min(20, baseCPS));
    }
    
    /**
     * Validate decision against GrimPredictionEngine
     * Ensures our combat actions won't trigger Grim flags
     */
    public static boolean isGrimSafe(CombatDecision decision, EntityPlayer player, EntityPlayer target) {
        if (!decision.shouldWTap && !decision.shouldBlock) return true;
        
        // Predict position after W-tap
        if (decision.shouldWTap) {
            GrimPredictionEngine.PredictedPosition predicted = 
                GrimPredictionEngine.predictPlayerPosition(player, 2);
            
            if (predicted != null) {
                // Check if W-tap will cause movement offset
                Vec3 currentPos = new Vec3(player.posX, player.posY, player.posZ);
                if (GrimPredictionEngine.wouldGrimFlag(currentPos, predicted)) {
                    return false; // W-tap would flag
                }
            }
        }
        
        // Blocking is always safe (no movement)
        return true;
    }
    
    /**
     * Reset learning data (useful when switching targets)
     */
    public static void reset() {
        for (int i = 0; i < HISTORY_SIZE; i++) {
            stateHistory[i] = null;
        }
        historyIndex = 0;
    }
}
