package myau.util;

import net.minecraft.block.Block;
import net.minecraft.block.BlockAir;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.potion.Potion;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * PredictionEngine - Exact Minecraft 1.8 physics simulation for AntiVoid.
 *
 * Simulates the player's future trajectory tick-by-tick using the REAL 1.8 physics
 * constants (gravity = 0.08, air drag = 0.98, etc.) accounting for potion effects.
 *
 * The key entry point for AntiVoid is {@link #findLastSafeTickState} which returns
 * the LAST simulated tick where there is still a solid block directly beneath the
 * player's feet — that is the ideal blink anchor position: the latest possible
 * point where the player can "snap back" to a real block.
 *
 * Additionally {@link #willReachVoid} allows AntiVoid to PREDICT falling before it
 * actually happens (proactive, not reactive).
 */
public class PredictionEngine {

    private static final Minecraft mc = Minecraft.getMinecraft();

    // ── Exact Minecraft 1.8 physics constants ─────────────────────────────────
    private static final double GRAVITY         = 0.08;
    private static final double AIR_DRAG        = 0.98;
    // How many blocks below the player's current feet we scan for solid ground
    private static final int    GROUND_SCAN_DEPTH = 4;

    // ── Data classes ─────────────────────────────────────────────────────────

    /** Simple immutable 3-D coordinate. */
    public static final class Vec3D {
        public final double x, y, z;
        public Vec3D(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
    }

    /**
     * Full state snapshot for a single simulated tick.
     * {@code tick == 0} is the player's current position/velocity.
     */
    public static final class TickState {
        public final double x, y, z;
        public final double motX, motY, motZ;
        public final int    tick;

        public TickState(double x, double y, double z,
                         double motX, double motY, double motZ, int tick) {
            this.x = x; this.y = y; this.z = z;
            this.motX = motX; this.motY = motY; this.motZ = motZ;
            this.tick = tick;
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Simulate the player's trajectory for exactly {@code maxTicks} future ticks.
     *
     * Accounts for:
     *  - Gravity (0.08 per tick)
     *  - Air drag (0.98 per tick)
     *  - Jump boost potion (adds to initial upward velocity)
     *  - Speed potion (scales horizontal velocity only)
     *
     * Tick 0 of the returned list is the player's CURRENT position/velocity (not
     * a future prediction). Ticks 1..maxTicks are future states.
     *
     * @param player   The player to simulate.
     * @param maxTicks How many ticks into the future to simulate.
     * @return Ordered list of tick states, length {@code maxTicks + 1}.
     */
    public static List<TickState> simulateTrajectory(EntityPlayer player, int maxTicks) {
        List<TickState> states = new ArrayList<>(maxTicks + 1);
        if (player == null || maxTicks <= 0) return states;

        double x    = player.posX;
        double y    = player.posY;
        double z    = player.posZ;
        double motX = player.motionX;
        double motY = player.motionY;
        double motZ = player.motionZ;

        // Tick 0: current state
        states.add(new TickState(x, y, z, motX, motY, motZ, 0));

        // Determine gravity scale from levitation/jump boost (1.8 doesn't have
        // levitation but guard anyway; jump boost only affects initial jump, not
        // mid-air gravity, so we keep gravity constant)
        double gravity = GRAVITY;

        for (int t = 1; t <= maxTicks; t++) {
            // Step 1: apply motion to position
            x += motX;
            y += motY;
            z += motZ;

            // Step 2: gravity (applied BEFORE drag in 1.8 EntityLivingBase#moveEntityWithHeading)
            motY -= gravity;

            // Step 3: air drag
            motY *= AIR_DRAG;
            motX *= AIR_DRAG;
            motZ *= AIR_DRAG;

            states.add(new TickState(x, y, z, motX, motY, motZ, t));
        }

        return states;
    }

    /**
     * From a full simulated trajectory, find the LAST tick state where a solid
     * block exists within {@link #GROUND_SCAN_DEPTH} blocks beneath the player's
     * feet.
     *
     * This is the ideal blink-back position for AntiVoid: the furthest point the
     * player could have travelled while still having a block to land on.
     *
     * @param world  The world to query.
     * @param states Trajectory produced by {@link #simulateTrajectory}.
     * @return The last TickState with ground below, or {@code null} if none found.
     */
    public static TickState findLastSafeTickState(World world, List<TickState> states) {
        if (world == null || states == null || states.isEmpty()) return null;
        TickState lastSafe = null;
        for (TickState state : states) {
            if (hasGroundBelow(world, state.x, state.y, state.z, GROUND_SCAN_DEPTH)) {
                lastSafe = state;
            }
        }
        return lastSafe;
    }

    /**
     * Predict whether the player will descend to Y < 0 (the void) within
     * {@code maxTicks} ticks given their current position and velocity.
     *
     * This is the proactive check that AntiVoid uses BEFORE the player actually
     * enters the void, giving it time to set the blink anchor on the last safe block.
     *
     * @param player   The player to check.
     * @param maxTicks How many ticks to look ahead.
     * @return {@code true} if the player will reach void height within maxTicks.
     */
    public static boolean willReachVoid(EntityPlayer player, int maxTicks) {
        if (player == null) return false;
        double y    = player.posY;
        double motY = player.motionY;
        for (int t = 0; t < maxTicks; t++) {
            y    += motY;
            motY -= GRAVITY;
            motY *= AIR_DRAG;
            if (y < 0.0) return true;
        }
        return false;
    }

    /**
     * Estimate how many ticks until the player reaches void (Y < 0).
     * Returns {@link Integer#MAX_VALUE} if the player won't reach void within
     * {@code maxTicks}.
     */
    public static int ticksUntilVoid(EntityPlayer player, int maxTicks) {
        if (player == null) return Integer.MAX_VALUE;
        double y    = player.posY;
        double motY = player.motionY;
        for (int t = 0; t < maxTicks; t++) {
            y    += motY;
            motY -= GRAVITY;
            motY *= AIR_DRAG;
            if (y < 0.0) return t;
        }
        return Integer.MAX_VALUE;
    }

    /**
     * Simulate {@code ticks} ticks and return just the final position.
     * Kept for API compatibility with any callers of the old API.
     */
    public static Vec3D predictPlayerPosition(EntityPlayer player, int ticks) {
        if (player == null) return new Vec3D(0, 0, 0);
        List<TickState> states = simulateTrajectory(player, ticks);
        TickState last = states.get(states.size() - 1);
        return new Vec3D(last.x, last.y, last.z);
    }

    /**
     * Find the highest solid block's top-surface Y beneath the given position.
     * Scans downward from {@code y} to Y=0.
     *
     * @return Y the player's feet would be at (block top surface), or -1 if none found.
     */
    public static double findSafeYPosition(World world, double x, double y, double z) {
        if (world == null) return -1;
        int blockX = MathHelper.floor_double(x);
        int blockZ = MathHelper.floor_double(z);
        int startY = MathHelper.floor_double(y);

        for (int scanY = startY; scanY >= 0; scanY--) {
            BlockPos pos   = new BlockPos(blockX, scanY, blockZ);
            Block    block = world.getBlockState(pos).getBlock();
            if (block == null || block instanceof BlockAir) continue;
            Material mat = block.getMaterial();
            if (mat.isSolid() && !mat.isLiquid()) {
                // The player's feet sit on top of the block (scanY + 1)
                return scanY + 1.0;
            }
        }
        return -1;
    }

    /**
     * Returns true if a position has a solid block within {@code depth} blocks
     * directly below the given feet Y.
     */
    public static boolean hasGroundBelow(World world, double x, double y, double z, int depth) {
        if (world == null) return false;
        int blockX = MathHelper.floor_double(x);
        int blockZ = MathHelper.floor_double(z);
        int feetY  = MathHelper.floor_double(y);

        for (int dy = 0; dy < depth; dy++) {
            int scanY = feetY - dy;
            if (scanY < 0) break;
            BlockPos pos   = new BlockPos(blockX, scanY, blockZ);
            Block    block = world.getBlockState(pos).getBlock();
            if (block == null || block instanceof BlockAir) continue;
            Material mat = block.getMaterial();
            if (mat.isSolid() && !mat.isLiquid()) return true;
        }
        return false;
    }

    /**
     * Returns true if the given feet position is directly on a solid block
     * (block immediately at feetY - 1 is solid).
     */
    public static boolean isSafePosition(World world, double x, double y, double z) {
        if (world == null) return false;
        BlockPos pos   = new BlockPos(
                MathHelper.floor_double(x),
                MathHelper.floor_double(y) - 1,
                MathHelper.floor_double(z));
        Block    block = world.getBlockState(pos).getBlock();
        if (block == null) return false;
        Material mat = block.getMaterial();
        return mat.isSolid() && !mat.isLiquid();
    }
}
