package myau.management;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

/**
 * Grim Prediction Engine - Predicts Grim's movement predictions
 * 
 * This engine replicates Grim's exact prediction logic to determine where
 * Grim THINKS entities should be. By matching our predictions to Grim's,
 * we can avoid offset flags.
 * 
 * Based on Grim's PredictionEngine.java and SetbackTeleportUtil.java
 */
public class GrimPredictionEngine {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // Grim's constants (from PredictionEngine.java)
    private static final double GRAVITY = 0.08;
    private static final double AIR_FRICTION = 0.98;
    private static final double GROUND_FRICTION = 0.91;
    private static final double WATER_FRICTION = 0.8;
    private static final double LAVA_FRICTION = 0.5;
    
    // Movement thresholds (from Grim's checks)
    private static final double POSITION_THRESHOLD = 0.03; // Movement uncertainty
    private static final double VELOCITY_THRESHOLD = 0.03; // Velocity uncertainty
    
    /**
     * Predicted position data matching Grim's structure
     */
    public static class PredictedPosition {
        public Vec3 position;
        public Vec3 velocity;
        public boolean onGround;
        public double uncertainty;
        public long timestamp;
        
        public PredictedPosition(Vec3 position, Vec3 velocity, boolean onGround, double uncertainty) {
            this.position = position;
            this.velocity = velocity;
            this.onGround = onGround;
            this.uncertainty = uncertainty;
            this.timestamp = System.currentTimeMillis();
        }
    }
    
    /**
     * Predict where Grim thinks a player SHOULD be after N ticks
     * This matches Grim's exact prediction algorithm
     */
    public static PredictedPosition predictPlayerPosition(EntityPlayer player, int ticksAhead) {
        if (player == null) return null;
        
        // Start with current position and velocity
        Vec3 position = new Vec3(player.posX, player.posY, player.posZ);
        Vec3 velocity = new Vec3(player.motionX, player.motionY, player.motionZ);
        boolean onGround = player.onGround;
        boolean isSprinting = player.isSprinting();
        double uncertainty = 0.0;
        
        // Simulate Grim's prediction for each tick
        for (int tick = 0; tick < ticksAhead; tick++) {
            // Apply current velocity to position
            position = position.addVector(velocity.xCoord, velocity.yCoord, velocity.zCoord);
            
            // Check collision with ground (simplified)
            boolean willBeOnGround = isOnGround(position, player);
            
            // Apply friction based on environment
            double friction = getFriction(position, player, willBeOnGround);
            
            // FIX: Apply friction to Y component only when appropriate (not always)
            velocity = new Vec3(
                velocity.xCoord * friction,
                willBeOnGround ? velocity.yCoord * 0.0 : velocity.yCoord * AIR_FRICTION,
                velocity.zCoord * friction
            );
            
            // Apply gravity if in air (Grim's exact formula)
            if (!willBeOnGround) {
                velocity = velocity.addVector(0, -GRAVITY, 0);
            }
            
            // FIX: Account for sprint state changes affecting friction
            // Sprint momentum affects Grim's predictions
            if (isSprinting && willBeOnGround) {
                // Sprinting on ground has slightly different friction
                velocity = new Vec3(
                    velocity.xCoord * 1.0,
                    velocity.yCoord,
                    velocity.zCoord * 1.0
                );
            }
            
            // Add uncertainty based on Grim's movement threshold
            // Uncertainty compounds each tick
            uncertainty += POSITION_THRESHOLD;
            
            // Update ground state
            onGround = willBeOnGround;
        }
        
        return new PredictedPosition(position, velocity, onGround, uncertainty);
    }
    
    /**
     * Predict target position with interpolation (for Backtrack)
     * Matches Grim's ReachInterpolationData calculation
     */
    public static PredictedPosition predictWithInterpolation(EntityPlayer target, int interpolationSteps) {
        if (target == null) return null;
        
        Vec3 currentPos = new Vec3(target.posX, target.posY, target.posZ);
        Vec3 velocity = new Vec3(target.motionX, target.motionY, target.motionZ);
        
        // Grim's interpolation expansion (from ReachInterpolationData.java)
        double expansionX = 0.03125 * Math.min(interpolationSteps, 3); // Max 3 steps
        double expansionY = 0.015625 * Math.min(interpolationSteps, 3);
        double expansionZ = 0.03125 * Math.min(interpolationSteps, 3);
        
        // Apply interpolation to position
        Vec3 interpolatedPos = currentPos.addVector(
            velocity.xCoord * interpolationSteps,
            velocity.yCoord * interpolationSteps,
            velocity.zCoord * interpolationSteps
        );
        
        // Calculate total uncertainty (interpolation expansion + movement threshold)
        double uncertainty = Math.max(expansionX, Math.max(expansionY, expansionZ)) + POSITION_THRESHOLD;
        
        return new PredictedPosition(interpolatedPos, velocity, target.onGround, uncertainty);
    }
    
    /**
     * Check if Grim would flag our position vs predicted position
     */
    public static boolean wouldGrimFlag(Vec3 actualPos, PredictedPosition predicted) {
        if (predicted == null) return true;
        
        double offset = actualPos.distanceTo(predicted.position);
        
        // Grim flags if offset > uncertainty
        // Add small safety margin
        return offset > (predicted.uncertainty + 0.01);
    }
    
    /**
     * Calculate the maximum safe distance for backtrack
     * Based on Grim's reach calculation with prediction
     */
    public static double getMaxSafeBacktrackDistance(EntityPlayer target, int ticksBack) {
        if (target == null) return 0.0;
        
        // Base reach (from Grim's Reach.java)
        double baseReach = 3.0;
        
        // Add movement threshold (line 294 in Reach.java)
        double movementThreshold = 0.03;
        
        // Add interpolation expansion based on ticks
        int interpolationSteps = Math.min(ticksBack, 3);
        double interpolationExpansion = 0.03125 * interpolationSteps;
        
        // Add ping-based expansion
        int ping = TransactionManager.getInstance().getPing();
        double pingExpansion = (ping / 50.0) * 0.03; // 0.03 per tick
        
        // Calculate total safe reach
        double maxSafeReach = baseReach + movementThreshold + interpolationExpansion + pingExpansion;
        
        // Subtract safety margin for MCFleet
        maxSafeReach -= 0.07;
        
        return Math.min(maxSafeReach, 3.5); // Never exceed 3.5
    }
    
    /**
     * Get friction value based on environment
     * Matches Grim's friction calculation
     */
    private static double getFriction(Vec3 position, EntityPlayer player, boolean onGround) {
        if (onGround) {
            // Ground friction varies by block type
            BlockPos blockBelow = new BlockPos(position.xCoord, position.yCoord - 1, position.zCoord);
            
            // Simplified - just use standard ground friction
            return GROUND_FRICTION;
        } else if (isInWater(position, player)) {
            return WATER_FRICTION;
        } else if (isInLava(position, player)) {
            return LAVA_FRICTION;
        } else {
            return AIR_FRICTION;
        }
    }
    
    /**
     * Check if position is on ground
     * Simplified version of Grim's collision check
     */
    private static boolean isOnGround(Vec3 position, EntityPlayer player) {
        if (mc.theWorld == null) return false;
        
        // Check block below feet
        BlockPos blockBelow = new BlockPos(position.xCoord, position.yCoord - 0.1, position.zCoord);
        return !mc.theWorld.isAirBlock(blockBelow);
    }
    
    /**
     * Check if position is in water
     */
    private static boolean isInWater(Vec3 position, EntityPlayer player) {
        if (mc.theWorld == null) return false;
        
        AxisAlignedBB box = new AxisAlignedBB(
            position.xCoord - 0.3, position.yCoord, position.zCoord - 0.3,
            position.xCoord + 0.3, position.yCoord + 1.8, position.zCoord + 0.3
        );
        
        return mc.theWorld.isAABBInMaterial(box, net.minecraft.block.material.Material.water);
    }
    
    /**
     * Check if position is in lava
     */
    private static boolean isInLava(Vec3 position, EntityPlayer player) {
        if (mc.theWorld == null) return false;
        
        AxisAlignedBB box = new AxisAlignedBB(
            position.xCoord - 0.3, position.yCoord, position.zCoord - 0.3,
            position.xCoord + 0.3, position.yCoord + 1.8, position.zCoord + 0.3
        );
        
        return mc.theWorld.isAABBInMaterial(box, net.minecraft.block.material.Material.lava);
    }
    
    /**
     * Calculate velocity threshold based on Grim's logic
     * Used for Velocity bypass
     */
    public static Vec3 calculateSafeVelocity(Vec3 originalVelocity, double reductionPercent) {
        // Grim allows up to VELOCITY_THRESHOLD offset
        // We can reduce velocity as long as we stay under this
        
        double safeReduction = Math.min(reductionPercent, 0.8); // Max 80% safe
        
        return new Vec3(
            originalVelocity.xCoord * (1.0 - safeReduction),
            originalVelocity.yCoord * (1.0 - safeReduction),
            originalVelocity.zCoord * (1.0 - safeReduction)
        );
    }
}
