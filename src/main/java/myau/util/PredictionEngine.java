package myau.util;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

/**
 * Prediction Engine - Predicts player movement for AntiVoid
 * 
 * This utility class provides methods to:
 * - Predict future player positions based on current velocity
 * - Find safe block positions to teleport to
 * 
 * Used primarily by AntiVoid module to prevent falling into void
 */
public class PredictionEngine {
    
    /**
     * Simple 3D vector class for position representation
     */
    public static class Vec3D {
        public final double x;
        public final double y;
        public final double z;
        
        public Vec3D(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
    
    /**
     * Predicts player position after a certain number of ticks
     * Takes into account current velocity and movement physics
     * 
     * @param player The player to predict position for
     * @param ticks Number of ticks to predict ahead
     * @return Predicted position as Vec3D
     */
    public static Vec3D predictPlayerPosition(EntityPlayer player, int ticks) {
        if (player == null || ticks <= 0) {
            return new Vec3D(player.posX, player.posY, player.posZ);
        }
        
        // Get current position and velocity
        double predX = player.posX;
        double predY = player.posY;
        double predZ = player.posZ;
        
        double motionX = player.motionX;
        double motionY = player.motionY;
        double motionZ = player.motionZ;
        
        // Simulate movement for each tick
        for (int i = 0; i < ticks; i++) {
            // Apply motion
            predX += motionX;
            predY += motionY;
            predZ += motionZ;
            
            // Apply gravity (0.08 blocks per tick squared)
            motionY -= 0.08;
            
            // Apply air resistance (0.98 multiplier)
            motionX *= 0.98;
            motionY *= 0.98;
            motionZ *= 0.98;
            
            // If player is on ground, apply ground friction
            if (player.onGround && i == 0) {
                motionX *= 0.6;
                motionZ *= 0.6;
            }
        }
        
        return new Vec3D(predX, predY, predZ);
    }
    
    /**
     * Finds the nearest safe Y position below the given coordinates
     * A safe position is one that has a solid block
     * 
     * @param world The world to search in
     * @param x X coordinate
     * @param y Y coordinate to start searching from
     * @param z Z coordinate
     * @return Safe Y position, or -1 if no safe position found
     */
    public static double findSafeYPosition(World world, double x, double y, double z) {
        if (world == null) {
            return -1;
        }
        
        // Search downwards from current position
        int startY = (int) Math.floor(y);
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        
        // Search up to 256 blocks down
        for (int searchY = startY; searchY >= Math.max(0, startY - 256); searchY--) {
            BlockPos pos = new BlockPos(blockX, searchY, blockZ);
            Block block = world.getBlockState(pos).getBlock();
            
            // Check if block is solid (not air, not water, not lava)
            if (block != null && block.getMaterial() != Material.air) {
                Material material = block.getMaterial();
                
                // Only return position if it's a solid, safe block
                if (material.isSolid() && !material.isLiquid()) {
                    // Return the position ON TOP of the block
                    return searchY + 1.0;
                }
            }
        }
        
        // No safe position found
        return -1;
    }
    
    /**
     * Checks if a position is safe to stand on
     * 
     * @param world The world
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @return true if position is safe, false otherwise
     */
    public static boolean isSafePosition(World world, double x, double y, double z) {
        if (world == null) {
            return false;
        }
        
        BlockPos pos = new BlockPos((int) Math.floor(x), (int) Math.floor(y - 1), (int) Math.floor(z));
        Block block = world.getBlockState(pos).getBlock();
        
        if (block == null) {
            return false;
        }
        
        Material material = block.getMaterial();
        return material.isSolid() && !material.isLiquid();
    }
}
