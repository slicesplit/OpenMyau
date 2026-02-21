package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.*;
import myau.module.Module;
import myau.util.KeyBindUtil;
import myau.util.MoveUtil;
import myau.util.ChatUtil;
import myau.util.PacketUtil;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLadder;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S23PacketBlockChange;
import net.minecraft.network.play.server.S22PacketMultiBlockChange;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

@ModuleInfo(category = ModuleCategory.MOVEMENT)
public class Fly extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private double verticalMotion = 0.0;

    public final ModeProperty mode = new ModeProperty("mode", 0,
            new String[]{"VANILLA", "VULCAN_GHOST", "GHOST_BLOCK"});

    // Vanilla
    public final FloatProperty hSpeed = new FloatProperty("horizontal-speed", 1.0F, 0.0F, 100.0F,
            () -> this.mode.getValue() == 0);
    public final FloatProperty vSpeed = new FloatProperty("vertical-speed", 1.0F, 0.0F, 100.0F,
            () -> this.mode.getValue() == 0);

    // Vulcan Ghost
    public final FloatProperty ghostSpeed = new FloatProperty("ghost-speed", 0.3F, 0.1F, 1.0F,
            () -> this.mode.getValue() == 1);
    public final BooleanProperty grimBypass = new BooleanProperty("grim-bypass", true,
            () -> this.mode.getValue() == 1);
    public final FloatProperty grimMaxDistance = new FloatProperty("grim-max-distance", 3.5F, 2.0F, 6.0F,
            () -> this.mode.getValue() == 1 && this.grimBypass.getValue());

    // Ghost Block mode
    public final FloatProperty gbSpeed = new FloatProperty("gb-speed", 1.0F, 0.1F, 3.0F,
            () -> this.mode.getValue() == 2);
    public final IntProperty gbRadius = new IntProperty("gb-radius", 1, 0, 3,
            () -> this.mode.getValue() == 2);
    public final IntProperty gbTrailLength = new IntProperty("gb-trail-length", 20, 5, 100,
            () -> this.mode.getValue() == 2);
    public final BooleanProperty gbAutoJump = new BooleanProperty("gb-auto-jump", false,
            () -> this.mode.getValue() == 2);
    public final BooleanProperty gbCancelServerBlocks = new BooleanProperty("gb-cancel-placement", true,
            () -> this.mode.getValue() == 2);

    // Vulcan Ghost state
    private boolean ghostModeActive = false;
    private long lastGroundTime = 0L;

    // Ghost Block state
    private final Set<BlockPos> ghostBlocks = new HashSet<>();
    private final Set<BlockPos> originalAirBlocks = new HashSet<>();
    private double gbPlaceY = -1;
    private boolean gbActive = false;

    public Fly() {
        super("Fly", false);
    }

    @Override
    public void onEnabled() {
        ghostModeActive = false;

        if (this.mode.getValue() == 1) {
            ChatUtil.sendFormatted("&7Ensure that you sneak on landing.");
            ChatUtil.sendFormatted("&7After landing, go backward (Air) and go forward to landing location, then sneak again.");
            ChatUtil.sendFormatted("&7And then you can turn off fly.");
        }

        if (this.mode.getValue() == 2) {
            ghostBlocks.clear();
            originalAirBlocks.clear();
            gbActive = false;
            gbPlaceY = -1;
        }
    }

    @Override
    public void onDisabled() {
        if (this.mode.getValue() == 0) {
            mc.thePlayer.motionY = 0.0;
            MoveUtil.setSpeed(0.0);
            KeyBindUtil.updateKeyState(mc.gameSettings.keyBindSneak.getKeyCode());
        }

        ghostModeActive = false;

        // Clean up all ghost blocks
        if (this.mode.getValue() == 2) {
            removeAllGhostBlocks();
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  UPDATE
    // ═══════════════════════════════════════════════════════════

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;

        if (this.mode.getValue() == 0) {
            // Vanilla fly
            this.verticalMotion = 0.0;
            if (mc.currentScreen == null) {
                if (KeyBindUtil.isKeyDown(mc.gameSettings.keyBindJump.getKeyCode())) {
                    this.verticalMotion += this.vSpeed.getValue().doubleValue() * 0.42F;
                }
                if (KeyBindUtil.isKeyDown(mc.gameSettings.keyBindSneak.getKeyCode())) {
                    this.verticalMotion -= this.vSpeed.getValue().doubleValue() * 0.42F;
                }
                KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), false);
            }
        } else if (this.mode.getValue() == 1) {
            // Vulcan ghost
            if (this.grimBypass.getValue()) {
                boolean jumpPressed = KeyBindUtil.isKeyDown(mc.gameSettings.keyBindJump.getKeyCode());
                boolean sneakPressed = KeyBindUtil.isKeyDown(mc.gameSettings.keyBindSneak.getKeyCode());
                if (jumpPressed && !sneakPressed) {
                    if (mc.thePlayer.motionY < 0) {
                        mc.thePlayer.motionY *= 0.98;
                    }
                }
            }
        } else if (this.mode.getValue() == 2) {
            // Ghost Block fly
            tickGhostBlockFly();
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  GHOST BLOCK FLY — Core Logic
    //
    //  How it works:
    //  1. When you jump or walk off an edge, we detect you're in air
    //  2. We place ghost blocks (client-side only) at your feet level
    //  3. Minecraft's physics engine sees solid blocks → you walk normally
    //  4. You can jump, sprint, sneak — everything works because the
    //     client genuinely thinks there are blocks there
    //  5. Server sees air → other players see you walking on nothing
    //  6. Old ghost blocks behind you get cleaned up to avoid lag
    //
    //  Why this works:
    //  - Client collision is based on client-side block state
    //  - Server doesn't check client block state, it checks ITS blocks
    //  - Generic prediction ACs predict your movement based on what
    //    blocks the SERVER has — they see air, so they predict you fall
    //  - But since we never send invalid position packets (we actually
    //    DO fall from the server's perspective), the AC can't flag us
    //  - The "fly" is purely visual on client — server sees you falling
    //
    //  IMPORTANT: This WILL rubberband you eventually because the server
    //  knows you're in air. The point is it looks and feels like flying
    //  on your screen. Combine with Velocity/NoFall for best results.
    // ═══════════════════════════════════════════════════════════

    private void tickGhostBlockFly() {
        boolean sneakPressed = KeyBindUtil.isKeyDown(mc.gameSettings.keyBindSneak.getKeyCode());

        // Sneak = descend (remove ghost blocks below, let gravity work)
        if (sneakPressed) {
            gbActive = false;
            gbPlaceY = -1;
            return;
        }

        // Determine the Y level to place ghost blocks
        // Lock to the Y level where we first started flying
        if (!gbActive) {
            // Start ghost block fly when:
            // 1. Player jumps (motionY > 0 and not on ground)
            // 2. Or player walks off edge (was on ground, now not)
            // 3. Or player is in air and presses jump

            boolean jumpPressed = KeyBindUtil.isKeyDown(mc.gameSettings.keyBindJump.getKeyCode());

            if (mc.thePlayer.onGround) {
                // On ground — set the base Y for when we leave
                gbPlaceY = Math.floor(mc.thePlayer.posY) - 1;
            } else if (gbPlaceY < 0) {
                // In air without a base — use current feet position
                gbPlaceY = Math.floor(mc.thePlayer.posY) - 1;
                gbActive = true;
            } else {
                // We've left the ground — activate
                gbActive = true;
            }

            // Jump to go up a level
            if (jumpPressed && gbActive) {
                gbPlaceY = Math.floor(mc.thePlayer.posY);
            }
        } else {
            // Active ghost block flying
            boolean jumpPressed = KeyBindUtil.isKeyDown(mc.gameSettings.keyBindJump.getKeyCode());

            // Jump = go up one block
            if (jumpPressed && mc.thePlayer.onGround) {
                gbPlaceY = Math.floor(mc.thePlayer.posY);
            }

            // Auto-jump: if we hit a wall, place blocks one level higher
            if (gbAutoJump.getValue() && mc.thePlayer.isCollidedHorizontally && !mc.thePlayer.onGround) {
                gbPlaceY = Math.floor(mc.thePlayer.posY);
            }
        }

        if (!gbActive) return;

        // Place ghost blocks around player's current XZ position at the locked Y level
        int radius = gbRadius.getValue();
        int playerBlockX = (int) Math.floor(mc.thePlayer.posX);
        int playerBlockZ = (int) Math.floor(mc.thePlayer.posZ);
        int placeY = (int) gbPlaceY;

        // Place blocks in a radius around player feet
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos pos = new BlockPos(playerBlockX + dx, placeY, playerBlockZ + dz);
                placeGhostBlock(pos);
            }
        }

        // Also place one block ahead of movement direction for smoother walking
        if (mc.thePlayer.moveForward != 0 || mc.thePlayer.moveStrafing != 0) {
            double yawRad = Math.toRadians(mc.thePlayer.rotationYaw);
            int aheadX = playerBlockX + (int) Math.round(-Math.sin(yawRad) * (radius + 1));
            int aheadZ = playerBlockZ + (int) Math.round(Math.cos(yawRad) * (radius + 1));

            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = new BlockPos(aheadX + dx, placeY, aheadZ + dz);
                    placeGhostBlock(pos);
                }
            }
        }

        // Clean up old ghost blocks that are far behind us
        cleanupTrail();
    }

    /**
     * Places a single ghost block at the given position.
     * Only places if the position is currently air and isn't already a ghost block.
     */
    private void placeGhostBlock(BlockPos pos) {
        if (mc.theWorld == null) return;

        // Don't place inside the player
        AxisAlignedBB playerBox = mc.thePlayer.getEntityBoundingBox();
        AxisAlignedBB blockBox = new AxisAlignedBB(pos, pos.add(1, 1, 1));
        if (playerBox.intersectsWith(blockBox)) return;

        // Only place in air
        IBlockState current = mc.theWorld.getBlockState(pos);
        if (current == null) return;
        if (current.getBlock() != Blocks.air) return;

        // Already tracked
        if (ghostBlocks.contains(pos)) return;

        // Place glass (visible, lets you see through)
        mc.theWorld.setBlockState(pos, Blocks.glass.getDefaultState());
        ghostBlocks.add(pos);
        originalAirBlocks.add(pos);
    }

    /**
     * Removes ghost blocks that are too far from the player.
     * Keeps a trail of gbTrailLength blocks max.
     */
    private void cleanupTrail() {
        if (ghostBlocks.size() <= gbTrailLength.getValue()) return;

        double maxDistSq = (gbTrailLength.getValue() / 2.0 + 3) * (gbTrailLength.getValue() / 2.0 + 3);

        Iterator<BlockPos> it = ghostBlocks.iterator();
        while (it.hasNext()) {
            BlockPos pos = it.next();
            double dx = mc.thePlayer.posX - (pos.getX() + 0.5);
            double dz = mc.thePlayer.posZ - (pos.getZ() + 0.5);
            double distSq = dx * dx + dz * dz;

            if (distSq > maxDistSq) {
                // Check it's still our ghost block before removing
                IBlockState state = mc.theWorld.getBlockState(pos);
                if (state != null && state.getBlock() == Blocks.glass) {
                    mc.theWorld.setBlockToAir(pos);
                }
                originalAirBlocks.remove(pos);
                it.remove();
            }
        }

        // Hard cap: if still too many, remove oldest (furthest)
        while (ghostBlocks.size() > gbTrailLength.getValue() * 3) {
            Iterator<BlockPos> trimIt = ghostBlocks.iterator();
            if (trimIt.hasNext()) {
                BlockPos pos = trimIt.next();
                IBlockState state = mc.theWorld.getBlockState(pos);
                if (state != null && state.getBlock() == Blocks.glass) {
                    mc.theWorld.setBlockToAir(pos);
                }
                originalAirBlocks.remove(pos);
                trimIt.remove();
            }
        }
    }

    /**
     * Removes ALL ghost blocks from the world.
     * Called on module disable.
     */
    private void removeAllGhostBlocks() {
        if (mc.theWorld == null) {
            ghostBlocks.clear();
            originalAirBlocks.clear();
            return;
        }

        for (BlockPos pos : ghostBlocks) {
            IBlockState state = mc.theWorld.getBlockState(pos);
            if (state != null && state.getBlock() == Blocks.glass) {
                mc.theWorld.setBlockToAir(pos);
            }
        }

        ghostBlocks.clear();
        originalAirBlocks.clear();
        gbActive = false;
        gbPlaceY = -1;
    }

    // ═══════════════════════════════════════════════════════════
    //  STRAFE
    // ═══════════════════════════════════════════════════════════

    @EventTarget
    public void onStrafe(StrafeEvent event) {
        if (!this.isEnabled()) return;

        if (this.mode.getValue() == 0) {
            if (mc.thePlayer.posY % 1.0 != 0.0) {
                mc.thePlayer.motionY = this.verticalMotion;
            }
            MoveUtil.setSpeed(0.0);
            event.setFriction((float) MoveUtil.getBaseMoveSpeed() * this.hSpeed.getValue());
        } else if (this.mode.getValue() == 1) {
            boolean jumpPressed = KeyBindUtil.isKeyDown(mc.gameSettings.keyBindJump.getKeyCode());
            boolean sneakPressed = KeyBindUtil.isKeyDown(mc.gameSettings.keyBindSneak.getKeyCode());
            if (jumpPressed && !sneakPressed) {
                MoveUtil.setSpeed(0.0);
                event.setFriction((float) MoveUtil.getBaseMoveSpeed() * this.ghostSpeed.getValue());
            }
        } else if (this.mode.getValue() == 2) {
            // Ghost block mode: ONLY modify speed if multiplier != 1.0
            // At 1.0, vanilla movement is already perfect — don't touch it
            if (gbActive && mc.thePlayer.onGround && Math.abs(gbSpeed.getValue() - 1.0F) > 0.01F) {
                event.setFriction((float) MoveUtil.getBaseMoveSpeed() * this.gbSpeed.getValue());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  PACKET HANDLING
    // ═══════════════════════════════════════════════════════════

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;

        // Vulcan Ghost: cancel teleport
        if (this.mode.getValue() == 1) {
            if (event.getType() == EventType.RECEIVE && event.getPacket() instanceof S08PacketPlayerPosLook) {
                event.setCancelled(true);
                ghostModeActive = false;
            }
        }

        // Ghost Block mode
        if (this.mode.getValue() == 2) {

            // Cancel outgoing block placement packets if enabled
            // This prevents the server from knowing we tried to place blocks
            if (event.getType() == EventType.SEND && gbCancelServerBlocks.getValue()) {
                if (event.getPacket() instanceof C08PacketPlayerBlockPlacement) {
                    // Only cancel if we're actively ghost-flying
                    // Let normal placements through when not active
                    if (gbActive) {
                        C08PacketPlayerBlockPlacement pkt = (C08PacketPlayerBlockPlacement) event.getPacket();
                        BlockPos pos = pkt.getPosition();
                        // Only cancel if targeting a ghost block position
                        if (pos != null && !(pos.getX() == -1 && pos.getY() == -1 && pos.getZ() == -1)) {
                            int face = pkt.getPlacedBlockDirection();
                            EnumFacing facing;
                            switch (face) {
                                case 0: facing = EnumFacing.DOWN; break;
                                case 1: facing = EnumFacing.UP; break;
                                case 2: facing = EnumFacing.NORTH; break;
                                case 3: facing = EnumFacing.SOUTH; break;
                                case 4: facing = EnumFacing.WEST; break;
                                case 5: facing = EnumFacing.EAST; break;
                                default: return;
                            }
                            BlockPos placePos = pos.offset(facing);
                            if (ghostBlocks.contains(pos) || ghostBlocks.contains(placePos)) {
                                event.setCancelled(true);
                            }
                        }
                    }
                }
            }

            // Handle server block updates that overwrite our ghost blocks
            if (event.getType() == EventType.RECEIVE) {
                if (event.getPacket() instanceof S23PacketBlockChange) {
                    S23PacketBlockChange pkt = (S23PacketBlockChange) event.getPacket();
                    BlockPos pos = pkt.getBlockPosition();
                    if (pos != null && ghostBlocks.contains(pos)) {
                        // Server is telling us this position is something else (probably air)
                        // Re-place our ghost block to maintain the illusion
                        if (gbActive) {
                            // Ignore the server's update — keep our ghost block
                            event.setCancelled(true);
                        } else {
                            // We're not active anymore, let the server state through
                            ghostBlocks.remove(pos);
                            originalAirBlocks.remove(pos);
                        }
                    }
                }

                if (event.getPacket() instanceof S22PacketMultiBlockChange) {
                    S22PacketMultiBlockChange pkt = (S22PacketMultiBlockChange) event.getPacket();
                    if (gbActive) {
                        for (S22PacketMultiBlockChange.BlockUpdateData data : pkt.getChangedBlocks()) {
                            BlockPos pos = data.getPos();
                            if (pos != null && ghostBlocks.contains(pos)) {
                                // Can't selectively cancel multi-block changes,
                                // so we'll re-place after processing
                                // Schedule a re-place on next tick
                            }
                        }
                    }
                }

                // Server teleport while ghost block flying = disable
                if (event.getPacket() instanceof S08PacketPlayerPosLook) {
                    if (gbActive) {
                        // Server corrected our position — we got caught
                        // Clean up and deactivate
                        gbActive = false;
                        gbPlaceY = -1;
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  BLOCK BB (Vulcan Ghost mode only)
    // ═══════════════════════════════════════════════════════════

    @EventTarget
    public void onBlockBB(BlockBBEvent event) {
        if (!this.isEnabled() || this.mode.getValue() != 1) return;

        boolean jumpPressed = KeyBindUtil.isKeyDown(mc.gameSettings.keyBindJump.getKeyCode());
        boolean sneakPressed = KeyBindUtil.isKeyDown(mc.gameSettings.keyBindSneak.getKeyCode());

        if (!jumpPressed && sneakPressed) return;

        if (mc.thePlayer.onGround) {
            lastGroundTime = System.currentTimeMillis();
        }

        if (this.grimBypass.getValue()) {
            long timeSinceGround = System.currentTimeMillis() - lastGroundTime;
            if (timeSinceGround < 500) return;
        }

        Block block = event.getBlock();
        Material material = block.getMaterial();

        if (!material.blocksMovement() &&
                material != Material.carpet &&
                material != Material.vine &&
                material != Material.snow &&
                !(block instanceof BlockLadder)) {

            BlockPos pos = event.getPos();

            double distanceToBlock = Math.sqrt(
                    Math.pow(pos.getX() + 0.5 - mc.thePlayer.posX, 2) +
                            Math.pow(pos.getY() - mc.thePlayer.posY, 2) +
                            Math.pow(pos.getZ() + 0.5 - mc.thePlayer.posZ, 2)
            );

            double maxDistance = this.grimBypass.getValue() ? this.grimMaxDistance.getValue() : 5.0;

            if (distanceToBlock < maxDistance) {
                double size = this.grimBypass.getValue() ? 1.5 : 2.0;
                double height = this.grimBypass.getValue() ? 1.5 : 2.0;

                AxisAlignedBB expandedBox = new AxisAlignedBB(
                        pos.getX() - size, pos.getY() - 1.0, pos.getZ() - size,
                        pos.getX() + size + 1.0, pos.getY() + height, pos.getZ() + size + 1.0
                );

                event.setBoundingBox(expandedBox);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  PUBLIC API
    // ═══════════════════════════════════════════════════════════

    /**
     * Check if a position is a ghost block placed by this module
     */
    public boolean isGhostBlock(BlockPos pos) {
        return ghostBlocks.contains(pos);
    }

    public boolean isGhostBlockActive() {
        return gbActive;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }
}