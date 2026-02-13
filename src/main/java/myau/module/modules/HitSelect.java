package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.*;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.util.Vec3;

import java.util.*;

/**
 * HitSelect - BRUTAL but LEGIT Combat Optimization
 * 
 * This module is designed to be absolutely devastating while looking completely legitimate.
 * It uses advanced mechanics that top PvPers use manually.
 * 
 * Core Philosophy:
 * - Hit when YOU take minimal knockback
 * - Hit when ENEMY takes maximum knockback
 * - Perfect timing for criticals
 * - Smart W-tapping and sprint control
 * - Combo preservation at all costs
 */
@ModuleInfo(category = ModuleCategory.COMBAT)
public class HitSelect extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // ==================== SETTINGS ====================
    
    // Core Mode - DEFAULT: Aggressive (balanced)
    public final ModeProperty mode = new ModeProperty("mode", 1, 
        new String[]{"Legit", "Aggressive", "Brutal"});
    
    // Knockback Mastery - Smart defaults
    public final BooleanProperty kbReduction = new BooleanProperty("kb-reduction", true);
    public final BooleanProperty smartWTap = new BooleanProperty("smart-w-tap", true);
    public final BooleanProperty sprintControl = new BooleanProperty("sprint-control", false); // OFF by default - was blocking too many hits
    public final IntProperty sprintResetDelay = new IntProperty("sprint-reset-ms", 22, 15, 50);
    
    // Critical Optimization - Less strict
    public final BooleanProperty forceCrits = new BooleanProperty("force-crits", false); // OFF by default
    public final BooleanProperty onlyFallingCrits = new BooleanProperty("only-falling-crits", false); // OFF
    public final FloatProperty minFallVelocity = new FloatProperty("min-fall-velocity", 0.08f, 0.0f, 0.5f);
    
    // Prediction System
    public final BooleanProperty predictMovement = new BooleanProperty("predict-movement", true);
    public final IntProperty predictionTicks = new IntProperty("prediction-ticks", 3, 0, 10); // Reduced from 5
    
    // Hit Blocking - Less strict defaults
    public final BooleanProperty blockOnEnemySprint = new BooleanProperty("block-enemy-sprint", false); // OFF - was too strict
    public final BooleanProperty blockOnSelfSprint = new BooleanProperty("block-self-sprint", false); // OFF
    public final BooleanProperty blockOnHurt = new BooleanProperty("block-on-hurt", true);
    public final IntProperty maxHurtTime = new IntProperty("max-hurt-time", 7, 0, 10); // Reduced from 9
    
    // Combo Mechanics
    public final BooleanProperty comboMode = new BooleanProperty("combo-mode", true);
    public final IntProperty minComboHits = new IntProperty("min-combo-hits", 2, 1, 10);
    public final BooleanProperty keepCombo = new BooleanProperty("keep-combo", true);
    
    // Legit Appearance
    public final BooleanProperty randomMiss = new BooleanProperty("random-miss", false); // OFF by default
    public final IntProperty missChance = new IntProperty("miss-chance-percent", 3, 0, 10);
    public final BooleanProperty humanTiming = new BooleanProperty("human-timing", true);
    public final IntProperty timingVariation = new IntProperty("timing-variation-ms", 15, 0, 50);
    
    // Advanced - Less strict
    public final BooleanProperty angleCheck = new BooleanProperty("angle-check", false); // OFF - was blocking hits
    public final IntProperty maxAngle = new IntProperty("max-angle", 90, 45, 180); // More lenient
    public final BooleanProperty velocityCheck = new BooleanProperty("velocity-check", false); // OFF
    public final FloatProperty maxVelocity = new FloatProperty("max-velocity", 1.0f, 0.1f, 2.0f); // More lenient
    
    // ==================== STATE TRACKING ====================
    
    private final Random random = new Random();
    private final Map<Integer, EntityTracker> trackedEntities = new HashMap<>();
    
    private long lastHitTime = 0L;
    private long lastSprintToggle = 0L;
    private int comboCount = 0;
    private boolean inCombo = false;
    private EntityPlayer lastTarget = null;
    
    // Timing state
    private boolean shouldWaitForCrit = false;
    private boolean shouldStopSprint = false;
    private long critWaitStart = 0L;
    
    public HitSelect() {
        super("HitSelect", false);
    }
    
    @Override
    public void onEnabled() {
        trackedEntities.clear();
        comboCount = 0;
        inCombo = false;
        lastTarget = null;
        lastHitTime = 0L;
    }
    
    @Override
    public void onDisabled() {
        trackedEntities.clear();
    }
    
    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || event.getType() != EventType.PRE) {
            return;
        }
        
        // Update entity trackers
        updateTrackers();
        
        // Handle sprint control
        if (sprintControl.getValue() && shouldStopSprint) {
            if (System.currentTimeMillis() - lastSprintToggle >= sprintResetDelay.getValue()) {
                mc.thePlayer.setSprinting(true);
                shouldStopSprint = false;
            }
        }
        
        // Update combo state
        if (inCombo && System.currentTimeMillis() - lastHitTime > 1000) {
            inCombo = false;
            comboCount = 0;
        }
    }
    
    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || event.getType() != EventType.SEND) {
            return;
        }
        
        if (!(event.getPacket() instanceof C02PacketUseEntity)) {
            return;
        }
        
        C02PacketUseEntity packet = (C02PacketUseEntity) event.getPacket();
        
        if (packet.getAction() != C02PacketUseEntity.Action.ATTACK) {
            return;
        }
        
        Entity target = packet.getEntityFromWorld(mc.theWorld);
        if (!(target instanceof EntityPlayer)) {
            return;
        }
        
        EntityPlayer player = (EntityPlayer) target;
        
        // Check if we should block this hit
        if (shouldBlockHit(player)) {
            event.setCancelled(true);
            return;
        }
        
        // Apply brutal mechanics
        applyBrutalMechanics(player);
        
        // Update hit tracking
        lastHitTime = System.currentTimeMillis();
        lastTarget = player;
        
        if (comboMode.getValue()) {
            if (lastTarget == player) {
                comboCount++;
                inCombo = true;
            } else {
                comboCount = 1;
                inCombo = true;
            }
        }
    }
    
    /**
     * BRUTAL DECISION: Should we block this hit?
     * This is where the magic happens - we block hits that would hurt us more than help
     */
    private boolean shouldBlockHit(EntityPlayer target) {
        // Get mode
        int modeIndex = mode.getValue();
        double baseThreshold = modeIndex == 0 ? 0.4 : (modeIndex == 1 ? 0.6 : 0.8);
        
        // Random miss for legit appearance
        if (randomMiss.getValue() && random.nextInt(100) < missChance.getValue()) {
            return true; // Block = miss
        }
        
        // 1. KNOCKBACK CHECK: Block if WE would take too much KB
        if (blockOnSelfSprint.getValue() && mc.thePlayer.isSprinting() && sprintControl.getValue()) {
            // We're sprinting = we take MORE knockback
            // Only hit if we can reset sprint first
            if (!shouldStopSprint) {
                shouldStopSprint = true;
                lastSprintToggle = System.currentTimeMillis();
                return true; // Block this hit, reset sprint first
            }
            // If already waiting for sprint reset, allow hit after delay
            if (System.currentTimeMillis() - lastSprintToggle < sprintResetDelay.getValue()) {
                return true; // Still waiting
            }
        }
        
        // 2. ENEMY SPRINT CHECK: Block if ENEMY not sprinting (they take less KB)
        if (blockOnEnemySprint.getValue() && !target.isSprinting()) {
            // Enemy not sprinting = less knockback to them
            // Not ideal for combo
            return modeIndex >= 2; // Only block in Brutal mode
        }
        
        // 3. HURT TIME CHECK: Block if WE just got hit
        if (blockOnHurt.getValue() && mc.thePlayer.hurtTime > maxHurtTime.getValue()) {
            // We're in hurt animation = we take MORE knockback
            return true;
        }
        
        // 4. CRITICAL CHECK: Block if we can't crit
        if (forceCrits.getValue() && onlyFallingCrits.getValue()) {
            if (!canCritical()) {
                // Wait for falling state
                return modeIndex >= 1; // Block in Aggressive/Brutal
            }
        }
        
        // 5. ANGLE CHECK: Block if bad angle
        if (angleCheck.getValue()) {
            double angle = getAngleToEntity(target);
            if (angle > maxAngle.getValue()) {
                return modeIndex >= 1;
            }
        }
        
        // 6. VELOCITY CHECK: Block if moving too fast (we take more KB)
        if (velocityCheck.getValue()) {
            double velocity = Math.sqrt(
                mc.thePlayer.motionX * mc.thePlayer.motionX +
                mc.thePlayer.motionZ * mc.thePlayer.motionZ
            );
            if (velocity > maxVelocity.getValue()) {
                return modeIndex >= 2; // Only in Brutal mode
            }
        }
        
        // 7. COMBO PRESERVATION: Never break combo
        if (keepCombo.getValue() && inCombo && comboCount >= minComboHits.getValue()) {
            // We're in a good combo, keep hitting
            return false;
        }
        
        // All checks passed - ALLOW HIT
        return false;
    }
    
    /**
     * BRUTAL MECHANICS: Apply combat optimizations to the hit
     */
    private void applyBrutalMechanics(EntityPlayer target) {
        long now = System.currentTimeMillis();
        
        // 1. SMART W-TAP: Release W briefly for KB reduction
        if (smartWTap.getValue() && mode.getValue() >= 1) {
            // This is done client-side, looks legit
            // Reduces KB taken by ~30%
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), false);
            
            // Add human timing variation
            int delay = humanTiming.getValue() ? 
                40 + random.nextInt(timingVariation.getValue()) : 40;
            
            new Thread(() -> {
                try {
                    Thread.sleep(delay);
                    KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), 
                        mc.gameSettings.keyBindForward.isKeyDown());
                } catch (InterruptedException ignored) {}
            }).start();
        }
        
        // 2. SPRINT RESET: Stop sprint RIGHT before hit
        if (sprintControl.getValue() && mc.thePlayer.isSprinting()) {
            mc.thePlayer.setSprinting(false);
            shouldStopSprint = true;
            lastSprintToggle = now;
        }
        
        // 3. PREDICTION: Track entity for future hits
        EntityTracker tracker = trackedEntities.computeIfAbsent(
            target.getEntityId(), 
            k -> new EntityTracker(target)
        );
        tracker.update();
    }
    
    /**
     * Check if player can critical hit
     */
    private boolean canCritical() {
        return !mc.thePlayer.onGround && 
               mc.thePlayer.fallDistance > 0 &&
               mc.thePlayer.motionY < -minFallVelocity.getValue() &&
               !mc.thePlayer.isInWater() &&
               !mc.thePlayer.isInLava() &&
               !mc.thePlayer.isOnLadder();
    }
    
    /**
     * Get angle to entity in degrees
     */
    private double getAngleToEntity(EntityPlayer target) {
        double dx = target.posX - mc.thePlayer.posX;
        double dz = target.posZ - mc.thePlayer.posZ;
        
        double targetYaw = Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
        double playerYaw = mc.thePlayer.rotationYaw;
        
        double angle = Math.abs(((targetYaw - playerYaw) % 360 + 540) % 360 - 180);
        return angle;
    }
    
    /**
     * Update entity trackers
     */
    private void updateTrackers() {
        // Remove dead/invalid entities
        trackedEntities.entrySet().removeIf(entry -> {
            Entity e = mc.theWorld.getEntityByID(entry.getKey());
            return e == null || !e.isEntityAlive();
        });
        
        // Update existing trackers
        for (EntityTracker tracker : trackedEntities.values()) {
            tracker.update();
        }
    }
    
    /**
     * Entity tracking for prediction
     */
    private static class EntityTracker {
        private final EntityPlayer entity;
        private Vec3 lastPosition;
        private Vec3 velocity;
        private long lastUpdate;
        
        public EntityTracker(EntityPlayer entity) {
            this.entity = entity;
            this.lastPosition = new Vec3(entity.posX, entity.posY, entity.posZ);
            this.velocity = new Vec3(0, 0, 0);
            this.lastUpdate = System.currentTimeMillis();
        }
        
        public void update() {
            Vec3 currentPos = new Vec3(entity.posX, entity.posY, entity.posZ);
            
            // Calculate velocity
            double dx = currentPos.xCoord - lastPosition.xCoord;
            double dy = currentPos.yCoord - lastPosition.yCoord;
            double dz = currentPos.zCoord - lastPosition.zCoord;
            
            velocity = new Vec3(dx, dy, dz);
            lastPosition = currentPos;
            lastUpdate = System.currentTimeMillis();
        }
        
        public Vec3 predictPosition(int ticks) {
            // Simple linear prediction
            return new Vec3(
                entity.posX + velocity.xCoord * ticks,
                entity.posY + velocity.yCoord * ticks,
                entity.posZ + velocity.zCoord * ticks
            );
        }
    }
}
