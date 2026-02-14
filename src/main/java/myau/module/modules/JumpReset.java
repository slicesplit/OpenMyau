package myau.module.modules;

import myau.enums.ModuleCategory;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.module.ModuleInfo;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.management.CombatPredictionEngine;
import myau.management.GrimPredictionEngine;
import myau.util.CombatTimingOptimizer;
import myau.util.JumpResetOptimizer;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.util.Vec3;

import java.util.Random;

/**
 * JumpReset - Brutal 1.8 Knockback Reduction
 * 
 * Abuses vanilla MC knockback mechanics by timing jumps perfectly
 * before being hit. When timed correctly, dramatically reduces KB.
 * 
 * 1.8 Mechanic: Jumping right before taking a hit resets your velocity,
 * causing the knockback to be applied to near-zero base velocity instead
 * of your current velocity, resulting in minimal KB taken.
 * 
 * Uses prediction engines to anticipate hits with brutal accuracy.
 */
@ModuleInfo(category = ModuleCategory.COMBAT)
public class JumpReset extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // UI Settings
    public final IntProperty chance = new IntProperty("Chance", 100, 0, 100);
    public final IntProperty accuracy = new IntProperty("Accuracy", 100, 0, 100);
    public final BooleanProperty onlyWhenTargeting = new BooleanProperty("Only When Targeting", false);
    public final BooleanProperty waterCheck = new BooleanProperty("Water Check", true);
    
    // Internal state
    private final Random random = new Random();
    private EntityPlayer lastAttacker = null;
    private long lastHitTime = 0;
    private long lastJumpTime = 0;
    private boolean expectingHit = false;
    private int ticksUntilHit = 0;
    
    // Prediction tracking
    private Vec3 lastAttackerPosition = null;
    private Vec3 lastAttackerVelocity = null;
    private double lastDistance = 0.0;
    private int consecutiveHits = 0;
    
    // Brutal timing constants (1.8 specific)
    private static final int PERFECT_JUMP_WINDOW = 1; // 1 tick = 50ms perfect window
    private static final int EARLY_JUMP_WINDOW = 2; // 2 ticks = 100ms acceptable window
    private static final int MIN_JUMP_COOLDOWN = 3; // Minimum ticks between jumps
    private static final double MAX_PREDICTION_DISTANCE = 6.0; // Max distance to predict
    
    public JumpReset() {
        super("JumpReset", false);
    }
    
    @Override
    public void onEnabled() {
        lastAttacker = null;
        lastHitTime = 0;
        lastJumpTime = 0;
        expectingHit = false;
        ticksUntilHit = 0;
        consecutiveHits = 0;
        JumpResetOptimizer.reset();
    }
    
    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE || mc.thePlayer == null) return;
        
        // Countdown expected hit timer
        if (expectingHit && ticksUntilHit > 0) {
            ticksUntilHit--;
            
            // Execute jump at predicted time
            if (ticksUntilHit == 0) {
                executeJumpReset();
                expectingHit = false;
            }
        }
        
        // Brutal prediction: Analyze all nearby players for potential attacks
        if (mc.theWorld != null) {
            predictIncomingAttacks();
        }
    }
    
    @EventTarget
    public void onPacket(PacketEvent event) {
        if (event.getType() != EventType.RECEIVE || mc.thePlayer == null) return;
        
        // Detect velocity packet (hit confirmation)
        if (event.getPacket() instanceof S12PacketEntityVelocity) {
            S12PacketEntityVelocity packet = (S12PacketEntityVelocity) event.getPacket();
            
            // Check if velocity is for our player
            if (packet.getEntityID() == mc.thePlayer.getEntityId()) {
                onHitReceived(packet);
            }
        }
    }
    
    /**
     * BRUTAL PREDICTION: Analyze all nearby players for attack patterns
     */
    private void predictIncomingAttacks() {
        if (mc.theWorld == null || mc.thePlayer == null) return;
        
        EntityPlayer closestThreat = null;
        double closestDistance = MAX_PREDICTION_DISTANCE;
        double highestThreatLevel = 0.0;
        
        // Scan all players
        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (!(entity instanceof EntityPlayer)) continue;
            
            EntityPlayer player = (EntityPlayer) entity;
            if (player == mc.thePlayer || player.isDead) continue;
            
            double distance = mc.thePlayer.getDistanceToEntity(player);
            if (distance > MAX_PREDICTION_DISTANCE) continue;
            
            // Calculate threat level using prediction engine
            double threatLevel = calculateThreatLevel(player, distance);
            
            if (threatLevel > highestThreatLevel) {
                highestThreatLevel = threatLevel;
                closestThreat = player;
                closestDistance = distance;
            }
        }
        
        // If high threat detected, prepare jump reset
        if (closestThreat != null && highestThreatLevel > 0.7) {
            prepareJumpReset(closestThreat, closestDistance, highestThreatLevel);
        }
    }
    
    /**
     * Calculate threat level (0.0-1.0) based on multiple factors
     */
    private double calculateThreatLevel(EntityPlayer player, double distance) {
        double threat = 0.0;
        
        // Factor 1: Distance (closer = higher threat)
        if (distance < 3.0) {
            threat += 0.3 * (1.0 - (distance / 3.0));
        }
        
        // Factor 2: Player looking at us
        if (isLookingAtMe(player)) {
            threat += 0.3;
        }
        
        // Factor 3: Player approaching (velocity toward us)
        Vec3 playerVelocity = new Vec3(player.motionX, player.motionY, player.motionZ);
        Vec3 directionToUs = new Vec3(
            mc.thePlayer.posX - player.posX,
            mc.thePlayer.posY - player.posY,
            mc.thePlayer.posZ - player.posZ
        ).normalize();
        
        double approachSpeed = playerVelocity.dotProduct(directionToUs);
        if (approachSpeed > 0) {
            threat += 0.2 * Math.min(1.0, approachSpeed / 0.5);
        }
        
        // Factor 4: Player sprinting (more likely to attack)
        if (player.isSprinting()) {
            threat += 0.2;
        }
        
        // Factor 5: Recently hit us (pattern recognition)
        if (player == lastAttacker) {
            long timeSinceLastHit = System.currentTimeMillis() - lastHitTime;
            if (timeSinceLastHit < 1000) { // Within 1 second
                threat += 0.3;
            }
        }
        
        return Math.min(1.0, threat);
    }
    
    /**
     * Check if player is looking at us
     */
    private boolean isLookingAtMe(EntityPlayer player) {
        if (onlyWhenTargeting.getValue()) {
            // Strict check: Must be directly looking at us
            Vec3 lookVec = player.getLook(1.0F);
            Vec3 toUs = new Vec3(
                mc.thePlayer.posX - player.posX,
                mc.thePlayer.posY + mc.thePlayer.getEyeHeight() - (player.posY + player.getEyeHeight()),
                mc.thePlayer.posZ - player.posZ
            ).normalize();
            
            double dotProduct = lookVec.dotProduct(toUs);
            return dotProduct > 0.95; // Very tight angle (18 degrees)
        } else {
            // Lenient check: General direction
            Vec3 lookVec = player.getLook(1.0F);
            Vec3 toUs = new Vec3(
                mc.thePlayer.posX - player.posX,
                mc.thePlayer.posY - player.posY,
                mc.thePlayer.posZ - player.posZ
            ).normalize();
            
            double dotProduct = lookVec.dotProduct(toUs);
            return dotProduct > 0.7; // Wider angle (~45 degrees)
        }
    }
    
    /**
     * Prepare jump reset with BRUTAL prediction engine
     */
    private void prepareJumpReset(EntityPlayer attacker, double distance, double threatLevel) {
        // Check chance
        if (random.nextInt(100) >= chance.getValue()) {
            return;
        }
        
        // Check water
        if (waterCheck.getValue() && (mc.thePlayer.isInWater() || mc.thePlayer.isInLava())) {
            return;
        }
        
        // Don't spam jumps
        long timeSinceLastJump = (System.currentTimeMillis() - lastJumpTime) / 50; // Convert to ticks
        if (timeSinceLastJump < MIN_JUMP_COOLDOWN) {
            return;
        }
        
        // BRUTAL PREDICTION: Use advanced optimizer with 4 prediction methods
        JumpResetOptimizer.JumpPrediction prediction = 
            JumpResetOptimizer.predictOptimalJumpTiming(attacker, mc.thePlayer);
        
        if (!prediction.shouldJump) {
            return; // Prediction says don't jump
        }
        
        // Check confidence threshold
        if (prediction.confidence < 0.6) {
            return; // Not confident enough
        }
        
        // Apply accuracy setting
        if (!shouldExecuteAccurately()) {
            // Intentionally mistimed jump (for legit look)
            int mistiming = random.nextInt(3) - 1; // -1, 0, or +1 ticks
            prediction.ticksUntilJump += mistiming;
            
            if (prediction.ticksUntilJump < 0) {
                executeJumpReset(); // Jump too early
                return;
            }
        }
        
        // Schedule jump at predicted time
        if (prediction.ticksUntilJump == 0) {
            executeJumpReset(); // Jump immediately
        } else if (prediction.ticksUntilJump <= EARLY_JUMP_WINDOW) {
            expectingHit = true;
            ticksUntilHit = prediction.ticksUntilJump;
            lastAttacker = attacker;
        }
    }
    
    /**
     * Predict ticks until we get hit based on attacker movement
     */
    private int predictTicksUntilHit(EntityPlayer attacker, double distance) {
        // Calculate attacker's effective reach (3.0 base + sprint bonus)
        double effectiveReach = attacker.isSprinting() ? 3.5 : 3.0;
        
        // If already in range, hit is imminent (0-1 ticks)
        if (distance <= effectiveReach) {
            return 0;
        }
        
        // Calculate approach speed
        Vec3 attackerVelocity = new Vec3(attacker.motionX, attacker.motionY, attacker.motionZ);
        double approachSpeed = Math.sqrt(
            attackerVelocity.xCoord * attackerVelocity.xCoord +
            attackerVelocity.zCoord * attackerVelocity.zCoord
        );
        
        // Account for sprint multiplier
        if (attacker.isSprinting()) {
            approachSpeed *= 1.3;
        }
        
        if (approachSpeed < 0.05) {
            return -1; // Not approaching
        }
        
        // Calculate ticks to reach us
        double distanceToClose = distance - effectiveReach;
        double ticksToReach = distanceToClose / (approachSpeed * 20.0); // Convert to ticks
        
        // Add network delay compensation
        int ping = getPingEstimate();
        int pingTicks = Math.max(0, (ping / 50) - 1); // Convert ping to ticks, subtract 1 for prediction
        
        return (int) Math.max(0, ticksToReach - pingTicks);
    }
    
    /**
     * Get ping estimate from transaction manager or default
     */
    private int getPingEstimate() {
        try {
            return myau.management.TransactionManager.getInstance().getPing();
        } catch (Exception e) {
            return 50; // Default to 50ms if unavailable
        }
    }
    
    /**
     * Check if we should execute accurately based on accuracy setting
     */
    private boolean shouldExecuteAccurately() {
        return random.nextInt(100) < accuracy.getValue();
    }
    
    /**
     * Execute the jump reset with maximum effectiveness
     */
    private void executeJumpReset() {
        if (mc.thePlayer == null) return;
        
        // Double check water
        if (waterCheck.getValue() && (mc.thePlayer.isInWater() || mc.thePlayer.isInLava())) {
            return;
        }
        
        // Only jump if on ground (critical for 1.8 KB reset)
        if (mc.thePlayer.onGround) {
            // Validate with Grim prediction engine
            GrimPredictionEngine.PredictedPosition predicted = 
                GrimPredictionEngine.predictPlayerPosition(mc.thePlayer, 2);
            
            if (predicted != null) {
                Vec3 currentPos = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
                if (GrimPredictionEngine.wouldGrimFlag(currentPos, predicted)) {
                    return; // Would flag - skip
                }
            }
            
            // EXECUTE JUMP - 1.8 velocity reset mechanic
            mc.thePlayer.jump();
            lastJumpTime = System.currentTimeMillis();
            
            // Record jump timing for pattern learning
            if (lastAttacker != null) {
                double distance = mc.thePlayer.getDistanceToEntity(lastAttacker);
                double approachSpeed = Math.sqrt(
                    lastAttacker.motionX * lastAttacker.motionX +
                    lastAttacker.motionZ * lastAttacker.motionZ
                );
                
                JumpResetOptimizer.recordAttack(
                    distance,
                    approachSpeed,
                    lastAttacker.isSprinting(),
                    0, // Will be updated when hit received
                    false // Will be updated on hit
                );
                
                recordJumpResetAttempt(lastAttacker);
            }
        }
    }
    
    /**
     * Called when we receive a hit (velocity packet)
     * BRUTAL LEARNING: Analyze effectiveness and update patterns
     */
    private void onHitReceived(S12PacketEntityVelocity packet) {
        long currentTime = System.currentTimeMillis();
        long timeSinceJump = currentTime - lastJumpTime;
        
        // Calculate KB reduction effectiveness
        double kbReduction = JumpResetOptimizer.calculateKBReduction(timeSinceJump);
        
        // Check if our jump reset was successful
        boolean wasSuccessful = kbReduction >= 0.7; // 70% effectiveness threshold
        
        if (wasSuccessful) {
            consecutiveHits++;
            
            // Record successful pattern for learning
            if (lastAttacker != null) {
                double distance = lastDistance;
                double approachSpeed = lastAttackerVelocity != null ? 
                    Math.sqrt(lastAttackerVelocity.xCoord * lastAttackerVelocity.xCoord +
                             lastAttackerVelocity.zCoord * lastAttackerVelocity.zCoord) : 0.0;
                
                JumpResetOptimizer.recordAttack(
                    distance,
                    approachSpeed,
                    lastAttacker.isSprinting(),
                    (int) (timeSinceJump / 50), // Convert MS to ticks
                    true // Successful
                );
                
                recordSuccessfulJumpReset(lastAttacker);
            }
        } else {
            consecutiveHits = 0;
            
            // Record failed attempt for learning (to avoid repeating)
            if (lastAttacker != null) {
                double distance = lastDistance;
                double approachSpeed = lastAttackerVelocity != null ? 
                    Math.sqrt(lastAttackerVelocity.xCoord * lastAttackerVelocity.xCoord +
                             lastAttackerVelocity.zCoord * lastAttackerVelocity.zCoord) : 0.0;
                
                JumpResetOptimizer.recordAttack(
                    distance,
                    approachSpeed,
                    lastAttacker.isSprinting(),
                    (int) (timeSinceJump / 50),
                    false // Failed
                );
            }
        }
        
        lastHitTime = currentTime;
        expectingHit = false;
    }
    
    /**
     * Record jump reset attempt for learning
     */
    private void recordJumpResetAttempt(EntityPlayer attacker) {
        if (attacker == null) return;
        
        // Store attacker data for pattern recognition
        lastAttackerPosition = new Vec3(attacker.posX, attacker.posY, attacker.posZ);
        lastAttackerVelocity = new Vec3(attacker.motionX, attacker.motionY, attacker.motionZ);
        lastDistance = mc.thePlayer.getDistanceToEntity(attacker);
    }
    
    /**
     * Record successful jump reset for learning
     */
    private void recordSuccessfulJumpReset(EntityPlayer attacker) {
        if (attacker == null) return;
        
        // Create combat state for learning
        CombatPredictionEngine.CombatState state = 
            new CombatPredictionEngine.CombatState(mc.thePlayer, attacker);
        
        // Record low knockback as successful outcome
        CombatPredictionEngine.recordOutcome(true, false, 0.1); // Low KB
    }
    
    /**
     * Get module suffix for display
     */
    @Override
    public String[] getSuffix() {
        if (consecutiveHits > 0) {
            return new String[]{consecutiveHits + " hits"};
        }
        return new String[]{chance.getValue() + "%"};
    }
}
