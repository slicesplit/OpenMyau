package myau.util;

import myau.management.GrimPredictionEngine;
import myau.management.TransactionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Combat Timing Optimizer - Microsecond precision for 20 CPS god defeater
 * 
 * Optimizes hit timing based on:
 * - Server TPS (timing variance)
 * - Player ping (transaction timing)
 * - Grim prediction windows
 * - Attack cooldown mechanics
 */
public class CombatTimingOptimizer {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // Timing constants
    private static final long OPTIMAL_HIT_WINDOW = 50L; // 50ms = 20 CPS
    private static final long MIN_HIT_WINDOW = 41L; // 24 CPS max (unrealistic)
    private static final long MAX_HIT_WINDOW = 83L; // 12 CPS min
    
    // Server TPS tracking
    private static final int TPS_SAMPLES = 20;
    private static final long[] tpsTimestamps = new long[TPS_SAMPLES];
    private static int tpsIndex = 0;
    private static double averageTPS = 20.0;
    
    // Hit timing tracking
    private static long lastHitTime = 0L;
    private static long[] hitDelays = new long[10];
    private static int hitDelayIndex = 0;
    
    /**
     * Calculate optimal hit delay for current conditions
     * Returns delay in milliseconds
     */
    public static long calculateOptimalDelay(EntityPlayer player, EntityPlayer target, int targetCPS) {
        // Base delay from CPS
        long baseDelay = 1000L / targetCPS;
        
        // Factor 1: Server TPS compensation
        double tpsMultiplier = getTpsMultiplier();
        long tpsAdjusted = (long) (baseDelay * tpsMultiplier);
        
        // Factor 2: Ping compensation
        int ping = TransactionManager.getInstance().getPing();
        long pingAdjustment = calculatePingAdjustment(ping);
        
        // Factor 3: Grim prediction window
        long grimWindow = calculateGrimWindow(player, target);
        
        // Factor 4: Jitter reduction (smooth out timing)
        long jitterReduction = calculateJitterReduction();
        
        // Combine all factors
        long optimalDelay = tpsAdjusted + pingAdjustment - jitterReduction;
        
        // Ensure within Grim's safe window
        optimalDelay = Math.max(grimWindow, optimalDelay);
        
        // Clamp to realistic bounds
        return Math.max(MIN_HIT_WINDOW, Math.min(MAX_HIT_WINDOW, optimalDelay));
    }
    
    /**
     * Get TPS multiplier for timing adjustment
     * Lower TPS = longer delays needed
     */
    private static double getTpsMultiplier() {
        updateTPS();
        
        if (averageTPS >= 19.5) {
            return 1.0; // Perfect TPS, no adjustment
        } else if (averageTPS >= 18.0) {
            return 1.05; // Slight lag, 5% slower
        } else if (averageTPS >= 16.0) {
            return 1.10; // Moderate lag, 10% slower
        } else if (averageTPS >= 14.0) {
            return 1.15; // Heavy lag, 15% slower
        } else {
            return 1.20; // Severe lag, 20% slower
        }
    }
    
    /**
     * Calculate ping-based adjustment
     * Higher ping = need to lead attacks more
     */
    private static long calculatePingAdjustment(int ping) {
        if (ping < 30) {
            return 0L; // No adjustment needed
        } else if (ping < 60) {
            return -2L; // Slight lead
        } else if (ping < 100) {
            return -5L; // Moderate lead
        } else if (ping < 150) {
            return -8L; // Heavy lead
        } else {
            return -10L; // Maximum lead
        }
    }
    
    /**
     * Calculate Grim's safe hit window
     * Based on Grim's transaction timing
     */
    private static long calculateGrimWindow(EntityPlayer player, EntityPlayer target) {
        if (player == null || target == null) return OPTIMAL_HIT_WINDOW;
        
        // Grim validates hits within transaction windows
        // Minimum safe delay is based on last transaction
        TransactionManager tm = TransactionManager.getInstance();
        
        // If we're in a transaction window, we need to wait
        if (tm != null && tm.isInTransactionWindow()) {
            return OPTIMAL_HIT_WINDOW + 10L; // Add 10ms safety margin
        }
        
        return OPTIMAL_HIT_WINDOW;
    }
    
    /**
     * Calculate jitter reduction
     * Smooths out timing variance for consistency
     */
    private static long calculateJitterReduction() {
        if (hitDelayIndex < 3) return 0L; // Need samples first
        
        // Calculate average of last 3 hits
        long total = 0;
        int count = Math.min(3, hitDelayIndex);
        for (int i = 0; i < count; i++) {
            int index = (hitDelayIndex - 1 - i + hitDelays.length) % hitDelays.length;
            total += hitDelays[index];
        }
        long average = total / count;
        
        // If we're consistently faster than target, slow down slightly
        if (average < OPTIMAL_HIT_WINDOW - 5) {
            return -3L; // Add 3ms to smooth out
        } else if (average > OPTIMAL_HIT_WINDOW + 5) {
            return 3L; // Remove 3ms to speed up
        }
        
        return 0L;
    }
    
    /**
     * Record a hit for timing analysis
     */
    public static void recordHit() {
        long now = System.currentTimeMillis();
        
        if (lastHitTime > 0) {
            long delay = now - lastHitTime;
            hitDelays[hitDelayIndex] = delay;
            hitDelayIndex = (hitDelayIndex + 1) % hitDelays.length;
        }
        
        lastHitTime = now;
    }
    
    /**
     * Update TPS estimation
     * Called every tick
     */
    public static void updateTPS() {
        long now = System.currentTimeMillis();
        tpsTimestamps[tpsIndex] = now;
        
        // Calculate TPS from last 20 ticks
        int prevIndex = (tpsIndex - 19 + TPS_SAMPLES) % TPS_SAMPLES;
        long timeDiff = now - tpsTimestamps[prevIndex];
        
        if (timeDiff > 0 && tpsTimestamps[prevIndex] > 0) {
            // 20 ticks in timeDiff milliseconds
            // TPS = 20 / (timeDiff / 1000)
            averageTPS = 20000.0 / timeDiff;
            averageTPS = Math.min(20.0, Math.max(10.0, averageTPS));
        }
        
        tpsIndex = (tpsIndex + 1) % TPS_SAMPLES;
    }
    
    /**
     * Get current estimated TPS
     */
    public static double getCurrentTPS() {
        return averageTPS;
    }
    
    /**
     * Get average hit delay (for display)
     */
    public static long getAverageHitDelay() {
        if (hitDelayIndex == 0) return OPTIMAL_HIT_WINDOW;
        
        long total = 0;
        int count = Math.min(hitDelayIndex, hitDelays.length);
        for (int i = 0; i < count; i++) {
            total += hitDelays[i];
        }
        return total / count;
    }
    
    /**
     * Get current effective CPS
     */
    public static double getCurrentCPS() {
        long avgDelay = getAverageHitDelay();
        if (avgDelay == 0) return 0.0;
        return 1000.0 / avgDelay;
    }
    
    /**
     * Check if we should hit now based on optimal timing
     */
    public static boolean shouldHitNow(int targetCPS) {
        if (lastHitTime == 0) return true; // First hit
        
        long timeSinceLastHit = System.currentTimeMillis() - lastHitTime;
        long optimalDelay = calculateOptimalDelay(mc.thePlayer, null, targetCPS);
        
        return timeSinceLastHit >= optimalDelay;
    }
    
    /**
     * Calculate burst timing for combo attacks
     * Returns optimal delays for next 3 hits
     */
    public static long[] calculateBurstTiming(int targetCPS) {
        long baseDelay = 1000L / targetCPS;
        long[] timings = new long[3];
        
        // First hit: normal timing
        timings[0] = baseDelay;
        
        // Second hit: slightly faster (combo acceleration)
        timings[1] = Math.max(MIN_HIT_WINDOW, baseDelay - 5);
        
        // Third hit: return to normal
        timings[2] = baseDelay;
        
        return timings;
    }
    
    /**
     * Reset timing data
     */
    public static void reset() {
        lastHitTime = 0L;
        hitDelayIndex = 0;
        for (int i = 0; i < hitDelays.length; i++) {
            hitDelays[i] = 0L;
        }
    }
}
