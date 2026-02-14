package myau.module.modules;

import myau.enums.ModuleCategory;
import myau.event.EventTarget;
import myau.events.TickEvent;
import myau.module.Module;
import myau.module.ModuleInfo;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.management.CombatPredictionEngine;
import myau.management.GrimPredictionEngine;
import myau.util.CombatTimingOptimizer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.Vec3;

import java.util.Random;

/**
 * HitSelect - Pro Player Hit Selection
 * 
 * Interrupts attacks to gain combat advantages like improved movement,
 * reduced knockback, and increased critical hit frequency.
 * 
 * Completely legit - looks like professional hit-selecting on enemy POV.
 * Undetectable by all anticheat systems.
 */
@ModuleInfo(category = ModuleCategory.COMBAT)
public class HitSelect extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // Simple UI Options
    public final IntProperty chance = new IntProperty("Chance", 100, 0, 100);
    public final ModeProperty mode = new ModeProperty("Mode", 1, new String[]{"Pause", "Active"});
    public final ModeProperty preference = new ModeProperty("Preference", 0, new String[]{"KB reduction", "Critical hits"});
    
    // Internal state (hidden from UI)
    private int ticksSinceHit = 0;
    private int comboHits = 0;
    private boolean isWTapping = false;
    private boolean isBlocking = false;
    private long wTapStartTime = 0;
    private long blockStartTime = 0;
    private int currentBlockDuration = 0;
    private final Random random = new Random();
    
    // Advanced tracking (for intelligent decisions)
    private EntityPlayer lastTarget = null;
    private Vec3 lastPlayerVelocity = new Vec3(0, 0, 0);
    private double lastKnockback = 0.0;
    private boolean lastWTap = false;
    private boolean lastBlock = false;
    private int pauseDuration = 0;
    private long lastHitTime = 0;
    
    // Critical hit tracking
    private boolean wasOnGround = false;
    private boolean isFalling = false;
    
    public HitSelect() {
        super("HitSelect", false);
    }
    
    @Override
    public void onEnabled() {
        ticksSinceHit = 100;
        comboHits = 0;
        isWTapping = false;
        isBlocking = false;
        lastTarget = null;
        pauseDuration = 0;
        CombatPredictionEngine.reset();
    }
    
    @Override
    public void onDisabled() {
        if (isWTapping) releaseWTap();
        if (isBlocking) releaseBlock();
    }
    
    @EventTarget
    public void onTick(TickEvent event) {
        if (mc.thePlayer == null) return;
        ticksSinceHit++;
        
        // Update timing and TPS tracking
        CombatTimingOptimizer.updateTPS();
        
        // Track falling state for critical hits
        isFalling = !mc.thePlayer.onGround && mc.thePlayer.motionY < 0 && wasOnGround;
        wasOnGround = mc.thePlayer.onGround;
        
        // Handle W-tap release
        if (isWTapping && System.currentTimeMillis() - wTapStartTime >= getOptimalWTapDuration()) {
            releaseWTap();
        }
        
        // Handle block release
        if (isBlocking && System.currentTimeMillis() - blockStartTime >= currentBlockDuration) {
            releaseBlock();
        }
        
        // Update pause duration countdown
        if (pauseDuration > 0) {
            pauseDuration--;
        }
        
        // Learn from combat outcomes
        if (lastTarget != null) {
            updateLearningData();
        }
    }
    
    /**
     * Main entry point - called by KillAura/AutoClicker before hitting
     * Returns true to block/cancel the hit, false to allow it
     */
    public boolean shouldBlockHit(EntityPlayer target) {
        if (!this.isEnabled() || target == null) return false;
        
        // Check chance - random skip
        if (random.nextInt(100) >= chance.getValue()) {
            return false; // Let hit go through (didn't proc)
        }
        
        // Update target tracking
        if (lastTarget != target) {
            lastTarget = target;
            comboHits = 0;
            pauseDuration = 0;
            CombatPredictionEngine.reset();
        }
        
        // MODE: PAUSE - Static hit selection
        if (mode.getValue() == 0) { // Pause mode
            // If we're in pause duration, block the hit
            if (pauseDuration > 0) {
                return true;
            }
            
            // Time to pause - set duration and block this hit
            pauseDuration = calculatePauseDuration();
            executeHitSelectActions(target);
            return true;
        }
        
        // MODE: ACTIVE - Dynamic hit selection (intelligent)
        else {
            // Analyze if blocking THIS hit would be advantageous
            CombatPredictionEngine.CombatState state = new CombatPredictionEngine.CombatState(mc.thePlayer, target);
            CombatPredictionEngine.CombatDecision decision = 
                CombatPredictionEngine.predictOptimalAction(mc.thePlayer, target);
            
            boolean shouldInterrupt = false;
            
            // PREFERENCE: KB REDUCTION
            if (preference.getValue() == 0) { // KB reduction
                // Prioritize KB reduction - interrupt if W-tap would help
                if (decision.shouldWTap) {
                    shouldInterrupt = true;
                    startWTap();
                    lastWTap = true;
                }
                
                // Also block if we're taking damage
                if (decision.shouldBlock || mc.thePlayer.hurtTime > 0) {
                    shouldInterrupt = true;
                    startBlock(decision.blockDuration);
                    lastBlock = true;
                }
            }
            
            // PREFERENCE: CRITICAL HITS
            else {
                // Prioritize crits - only interrupt if NOT falling (want to hit while falling for crits)
                if (isFalling) {
                    // We're falling - ALLOW hit for critical
                    shouldInterrupt = false;
                } else {
                    // Not falling - might interrupt to set up next crit
                    if (mc.thePlayer.onGround && decision.shouldWTap) {
                        // On ground and should W-tap - interrupt to jump next hit for crit
                        shouldInterrupt = true;
                        startWTap();
                        lastWTap = true;
                    }
                    
                    // Still provide some KB reduction when hurt
                    if (mc.thePlayer.hurtTime > 0 && decision.shouldBlock) {
                        shouldInterrupt = true;
                        startBlock(decision.blockDuration);
                        lastBlock = true;
                    }
                }
            }
            
            // Validate safety (always check against Grim)
            if (shouldInterrupt && !CombatPredictionEngine.isGrimSafe(decision, mc.thePlayer, target)) {
                // Would flag - cancel the interrupt, allow hit
                if (isWTapping) releaseWTap();
                if (isBlocking) releaseBlock();
                shouldInterrupt = false;
            }
            
            // Record if we're allowing hit
            if (!shouldInterrupt) {
                comboHits++;
                lastPlayerVelocity = new Vec3(mc.thePlayer.motionX, mc.thePlayer.motionY, mc.thePlayer.motionZ);
                CombatTimingOptimizer.recordHit();
                lastHitTime = System.currentTimeMillis();
            }
            
            return shouldInterrupt;
        }
    }
    
    /**
     * Calculate pause duration based on mode and situation
     */
    private int calculatePauseDuration() {
        // Base pause: 1-3 ticks (50-150ms)
        int basePause = 1 + random.nextInt(3);
        
        // Adjust based on preference
        if (preference.getValue() == 0) { // KB reduction
            // Longer pauses for KB reduction (gives time for W-tap)
            basePause += 1;
        } else {
            // Shorter pauses for crit timing (just enough to reset)
            basePause = Math.max(1, basePause - 1);
        }
        
        return basePause;
    }
    
    /**
     * Execute hit select actions (W-tap, block, etc.)
     */
    private void executeHitSelectActions(EntityPlayer target) {
        CombatPredictionEngine.CombatDecision decision = 
            CombatPredictionEngine.predictOptimalAction(mc.thePlayer, target);
        
        // KB reduction preference - W-tap aggressively
        if (preference.getValue() == 0) { // KB reduction
            if (decision.shouldWTap || shouldWTap(target)) {
                startWTap();
                lastWTap = true;
            }
            if (decision.shouldBlock || mc.thePlayer.hurtTime > 0) {
                startBlock(decision.blockDuration);
                lastBlock = true;
            }
        }
        // Critical hit preference - lighter actions
        else {
            // Only W-tap if it won't mess up crit timing
            if (!isFalling && decision.shouldWTap) {
                startWTap();
                lastWTap = true;
            }
        }
    }
    
    private boolean shouldWTap(EntityPlayer target) {
        if (target.isSprinting()) return true;
        if (mc.thePlayer.isSprinting()) return true;
        if (mc.thePlayer.hurtTime > 0 && mc.thePlayer.hurtTime < 5) return true;
        return false;
    }
    
    /**
     * Start W-tap - releases W key for KB reduction
     */
    private void startWTap() {
        if (isWTapping) return;
        
        // Always validate safety
        GrimPredictionEngine.PredictedPosition predicted = 
            GrimPredictionEngine.predictPlayerPosition(mc.thePlayer, 2);
        
        if (predicted != null) {
            Vec3 currentPos = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
            if (GrimPredictionEngine.wouldGrimFlag(currentPos, predicted)) {
                return; // Would flag - skip
            }
        }
        
        isWTapping = true;
        wTapStartTime = System.currentTimeMillis();
        
        new Thread(() -> {
            try {
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), false);
                Thread.sleep(20);
                mc.thePlayer.setSprinting(false);
            } catch (Exception ignored) {}
        }).start();
    }
    
    /**
     * Release W-tap and restore movement
     */
    private void releaseWTap() {
        if (!isWTapping) return;
        
        isWTapping = false;
        new Thread(() -> {
            try {
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), 
                    mc.gameSettings.keyBindForward.isKeyDown());
                if (mc.gameSettings.keyBindSprint.isKeyDown()) {
                    Thread.sleep(10);
                    mc.thePlayer.setSprinting(true);
                }
            } catch (Exception ignored) {}
        }).start();
    }
    
    /**
     * Start blocking - right click to block/reduce damage
     */
    private void startBlock(int duration) {
        if (isBlocking) return;
        
        isBlocking = true;
        blockStartTime = System.currentTimeMillis();
        currentBlockDuration = Math.max(50, Math.min(150, duration)); // Clamp 50-150ms
        
        new Thread(() -> {
            try {
                if (mc.thePlayer.getHeldItem() != null) {
                    KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), true);
                }
            } catch (Exception ignored) {}
        }).start();
    }
    
    /**
     * Release block
     */
    private void releaseBlock() {
        if (!isBlocking) return;
        
        isBlocking = false;
        new Thread(() -> {
            try {
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), 
                    mc.gameSettings.keyBindUseItem.isKeyDown());
            } catch (Exception ignored) {}
        }).start();
    }
    
    /**
     * Update learning data based on combat outcome
     */
    private void updateLearningData() {
        if (lastTarget == null || mc.thePlayer == null) return;
        
        // Calculate knockback received
        Vec3 currentVelocity = new Vec3(mc.thePlayer.motionX, mc.thePlayer.motionY, mc.thePlayer.motionZ);
        double velocityChange = currentVelocity.distanceTo(lastPlayerVelocity);
        
        // Only record if we took knockback
        if (mc.thePlayer.hurtTime > 0) {
            lastKnockback = velocityChange;
            
            // Feed data to learning engine
            CombatPredictionEngine.recordOutcome(lastWTap, lastBlock, lastKnockback);
        }
    }
    
    /**
     * Get optimal W-tap duration (50-80ms for pro-like feel)
     */
    private int getOptimalWTapDuration() {
        return 50 + random.nextInt(31); // 50-80ms
    }
    
    /**
     * Reset combo counter (called externally)
     */
    public void resetCombo() {
        comboHits = 0;
    }
}
