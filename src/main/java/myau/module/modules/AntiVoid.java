package myau.module.modules;

import com.google.common.base.CaseFormat;
import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.KeyEvent;
import myau.events.PacketEvent;
import myau.events.PlayerUpdateEvent;
import myau.module.Module;
import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;
import myau.util.PacketUtil;
import myau.property.properties.FloatProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.BooleanProperty;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemEnderPearl;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C00PacketKeepAlive;
import net.minecraft.network.play.client.C01PacketChatMessage;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C03PacketPlayer.C04PacketPlayerPosition;
import net.minecraft.network.play.client.C03PacketPlayer.C06PacketPlayerPosLook;
import net.minecraft.network.play.client.C0FPacketConfirmTransaction;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

@ModuleInfo(category = ModuleCategory.MOVEMENT)
public class AntiVoid extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    // Maximum ticks we hold packets before force-flushing to prevent kick
    private static final int MAX_BLINK_TICKS = 40;
    // Minimum Y before we consider it void-danger zone
    private static final double VOID_Y_THRESHOLD = 0.5;
    // Minimum distance between safe positions to avoid spam
    private static final double MIN_SAFE_POS_DISTANCE_SQ = 0.01; // 0.1 blocks squared

    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"BLINK"});
    public final FloatProperty distance = new FloatProperty("distance", 5.0F, 1.0F, 16.0F);
    public final BooleanProperty checkHorizontal = new BooleanProperty("check-horizontal", true);

    // Safe position: the last known good position on solid ground
    private double safeX = 0.0;
    private double safeY = 0.0;
    private double safeZ = 0.0;
    private float safeYaw = 0.0F;
    private float safePitch = 0.0F;
    private boolean hasSafePos = false;

    // Blink state
    private boolean blinking = false;
    private final Deque<Packet<?>> queue = new ConcurrentLinkedDeque<>();
    private int blinkTicks = 0;

    // Trigger state
    private boolean triggered = false;
    private int cooldownTicks = 0;

    // Track last teleport position to avoid spamming same coords
    private double lastTpX = Double.NaN;
    private double lastTpY = Double.NaN;
    private double lastTpZ = Double.NaN;
    private int ticksSinceLastTp = 0;

    // Track server teleports to avoid fighting the server
    private boolean serverTeleported = false;
    private int serverTpCooldown = 0;

    // Track if player was on ground last tick (for edge detection)
    private boolean wasOnGround = false;
    private int airTicks = 0;
    private double lastGroundY = 0.0;

    // Track if other modules are doing things that conflict
    private boolean scaffoldWasActive = false;

    public AntiVoid() {
        super("AntiVoid", false);
    }

    @Override
    public void onEnabled() {
        resetState();
        if (mc.thePlayer != null && mc.thePlayer.onGround) {
            recordSafePosition();
        }
    }

    @Override
    public void onDisabled() {
        flush();
        resetState();
    }

    private void resetState() {
        hasSafePos = false;
        blinking = false;
        triggered = false;
        cooldownTicks = 0;
        blinkTicks = 0;
        queue.clear();
        lastTpX = Double.NaN;
        lastTpY = Double.NaN;
        lastTpZ = Double.NaN;
        ticksSinceLastTp = 0;
        serverTeleported = false;
        serverTpCooldown = 0;
        wasOnGround = false;
        airTicks = 0;
        lastGroundY = 0.0;
        scaffoldWasActive = false;
    }

    // ========================
    // MAIN LOGIC
    // ========================

    @EventTarget(Priority.LOWEST)
    public void onUpdate(PlayerUpdateEvent event) {
        if (!this.isEnabled()) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;

        // Creative/spectator - no void death possible
        if (mc.thePlayer.capabilities.allowFlying) {
            if (blinking) flush();
            return;
        }

        // Tick counters
        ticksSinceLastTp++;
        if (cooldownTicks > 0) cooldownTicks--;
        if (serverTpCooldown > 0) serverTpCooldown--;

        // ── Module conflict checks ──────────────────────────────────────────
        boolean scaffoldActive = isScaffoldActive();
        boolean longJumpActive = isLongJumpActive();
        boolean flyActive = isFlyActive();

        // If scaffold just became active while we're blinking, abort blink
        if (scaffoldActive && blinking) {
            abortBlink();
            return;
        }
        scaffoldWasActive = scaffoldActive;

        // Don't interfere with these modules at all
        if (longJumpActive || flyActive) {
            if (blinking) abortBlink();
            return;
        }

        // Don't act during server teleport cooldown
        if (serverTpCooldown > 0) {
            if (blinking) abortBlink();
            return;
        }

        // ── Ground state tracking ───────────────────────────────────────────
        if (mc.thePlayer.onGround) {
            airTicks = 0;

            if (!blinking && mc.thePlayer.posY > VOID_Y_THRESHOLD) {
                // Validate this is a real solid ground position
                if (isPositionSafe(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ)) {
                    // Only record if meaningfully different from current safe pos
                    if (!hasSafePos || !isSamePosition(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ)) {
                        recordSafePosition();
                    }
                    triggered = false;
                }
            }

            // If we land while blinking (caught a block on the way down), cancel blink
            if (blinking && !triggered) {
                abortBlink();
            }

            lastGroundY = mc.thePlayer.posY;
            wasOnGround = true;
        } else {
            airTicks++;
            wasOnGround = false;
        }

        // ── Void detection ──────────────────────────────────────────────────
        if (!mc.thePlayer.onGround
                && mc.thePlayer.motionY < 0
                && hasSafePos
                && !triggered
                && !blinking
                && cooldownTicks <= 0
                && !scaffoldActive) {

            double dropDistance = safeY - mc.thePlayer.posY;

            if (dropDistance >= distance.getValue()) {
                // Confirm actual void fall - check multiple columns for horizontal movement
                if (!hasSolidBlockBelowPlayer(16)) {
                    // Additional check: are we near Y=0?
                    // If player Y is still high but there's no blocks, it's likely
                    // a skyblock/void map scenario
                    if (mc.thePlayer.posY < safeY - 2.0 || mc.thePlayer.posY < 10.0) {
                        triggered = true;
                        blinkTicks = 0;
                        startBlink();
                    }
                }
            }
        }

        // ── Edge case: Y < 0 emergency ──────────────────────────────────────
        // If we somehow got below Y=0 without triggering, emergency teleport
        if (mc.thePlayer.posY < VOID_Y_THRESHOLD
                && hasSafePos
                && !blinking
                && !triggered
                && cooldownTicks <= 0
                && !scaffoldActive) {
            triggered = true;
            blinkTicks = 0;
            teleportBack();
            return;
        }

        // ── While blinking ──────────────────────────────────────────────────
        if (blinking && triggered) {
            blinkTicks++;

            // Safety: don't hold packets too long or server kicks us
            if (blinkTicks >= MAX_BLINK_TICKS) {
                teleportBack();
                return;
            }

            // Teleport back after 2 ticks to ensure we have position packets queued
            // Using 2 instead of 1 gives more reliable packet ordering
            if (blinkTicks >= 2) {
                teleportBack();
                return;
            }
        }

        // ── Safety: blink without trigger (shouldn't happen, but defensive) ──
        if (blinking && !triggered && blinkTicks > 5) {
            abortBlink();
        }
    }

    // ========================
    // SAFE POSITION MANAGEMENT
    // ========================

    private void recordSafePosition() {
        safeX = mc.thePlayer.posX;
        safeY = mc.thePlayer.posY;
        safeZ = mc.thePlayer.posZ;
        safeYaw = mc.thePlayer.rotationYaw;
        safePitch = mc.thePlayer.rotationPitch;
        hasSafePos = true;
    }

    /**
     * Check if a position is safe (has solid ground, not in a block, etc.)
     */
    private boolean isPositionSafe(double x, double y, double z) {
        int bx = MathHelper.floor_double(x);
        int by = MathHelper.floor_double(y);
        int bz = MathHelper.floor_double(z);

        // Must have solid block below feet
        BlockPos belowPos = new BlockPos(bx, by - 1, bz);
        Block belowBlock = mc.theWorld.getBlockState(belowPos).getBlock();
        if (belowBlock == Blocks.air || belowBlock instanceof BlockLiquid) {
            // Check if player hitbox overlaps multiple blocks (edge of block)
            if (!hasAnySolidBelow(x, y, z)) {
                return false;
            }
        }

        // Must not be inside a solid block (suffocating)
        BlockPos feetPos = new BlockPos(bx, by, bz);
        Block feetBlock = mc.theWorld.getBlockState(feetPos).getBlock();
        if (feetBlock.isFullCube() && feetBlock != Blocks.air) {
            return false;
        }

        return y > VOID_Y_THRESHOLD;
    }

    /**
     * Check if any solid block exists below any corner of the player's hitbox.
     * Handles standing on block edges.
     */
    private boolean hasAnySolidBelow(double x, double y, double z) {
        double halfWidth = 0.3; // Player half-width
        int by = MathHelper.floor_double(y) - 1;
        if (by < 0) return false;

        // Check all 4 corners of player hitbox
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int dz = -1; dz <= 1; dz += 2) {
                int checkX = MathHelper.floor_double(x + dx * halfWidth);
                int checkZ = MathHelper.floor_double(z + dz * halfWidth);
                BlockPos pos = new BlockPos(checkX, by, checkZ);
                Block block = mc.theWorld.getBlockState(pos).getBlock();
                if (block != Blocks.air && !(block instanceof BlockLiquid) && block.getMaterial().isSolid()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Check if the same position as current safe pos (within threshold).
     * Prevents recording the same position repeatedly.
     */
    private boolean isSamePosition(double x, double y, double z) {
        double dx = x - safeX;
        double dy = y - safeY;
        double dz = z - safeZ;
        return (dx * dx + dy * dy + dz * dz) < MIN_SAFE_POS_DISTANCE_SQ;
    }

    // ========================
    // BLINK CONTROL
    // ========================

    private void startBlink() {
        blinking = true;
        blinkTicks = 0;
        queue.clear();
    }

    /**
     * Teleport client back to safe position.
     * Discards all queued void-bound packets.
     * Sends a single position packet to server with safe coordinates.
     */
    private void teleportBack() {
        if (!hasSafePos) {
            abortBlink();
            return;
        }

        // Check if we'd be teleporting to the same place we just teleported to
        // This prevents the spam issue
        if (isSameTeleportTarget(safeX, safeY, safeZ) && ticksSinceLastTp < 20) {
            // We already teleported here recently - don't spam
            // Just abort the blink and let the player fall
            abortBlink();

            // Invalidate safe pos since it clearly didn't work
            hasSafePos = false;
            cooldownTicks = 40; // 2 second cooldown before trying again
            return;
        }

        // Move client visually
        mc.thePlayer.setPosition(safeX, safeY, safeZ);
        mc.thePlayer.motionX = 0.0;
        mc.thePlayer.motionY = 0.0;
        mc.thePlayer.motionZ = 0.0;
        mc.thePlayer.fallDistance = 0.0F;
        mc.thePlayer.onGround = true;

        // Send position to server
        if (mc.getNetHandler() != null) {
            PacketUtil.sendPacketSafe(new C06PacketPlayerPosLook(
                    safeX, safeY, safeZ, safeYaw, safePitch, true));
        }

        // Record this teleport to prevent spam
        lastTpX = safeX;
        lastTpY = safeY;
        lastTpZ = safeZ;
        ticksSinceLastTp = 0;

        // Clear all void-bound packets
        queue.clear();
        blinking = false;
        triggered = false;
        blinkTicks = 0;

        // Cooldown before allowing another trigger
        cooldownTicks = 10;
    }

    /**
     * Abort blink without teleporting - just flush packets normally.
     * Used when we determine blink was unnecessary (landed on block, etc.)
     */
    private void abortBlink() {
        blinking = false;
        blinkTicks = 0;

        // Flush queued packets so server stays in sync
        if (mc.getNetHandler() != null) {
            for (Packet<?> pkt : queue) {
                PacketUtil.sendPacketSafe(pkt);
            }
        }
        queue.clear();

        triggered = false;
    }

    /**
     * Flush all queued packets (used on disable).
     */
    private void flush() {
        if (mc.getNetHandler() != null) {
            for (Packet<?> pkt : queue) {
                PacketUtil.sendPacketSafe(pkt);
            }
        }
        queue.clear();
        blinking = false;
        triggered = false;
        blinkTicks = 0;
    }

    /**
     * Check if target position matches last teleport position.
     */
    private boolean isSameTeleportTarget(double x, double y, double z) {
        if (Double.isNaN(lastTpX)) return false;
        double dx = x - lastTpX;
        double dy = y - lastTpY;
        double dz = z - lastTpZ;
        return (dx * dx + dy * dy + dz * dz) < MIN_SAFE_POS_DISTANCE_SQ;
    }

    // ========================
    // PACKET INTERCEPT
    // ========================

    @EventTarget(Priority.HIGH)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled()) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;

        // ── Outgoing packets ─────────────────────────────────────────────────
        if (event.getType() == EventType.SEND) {
            if (!blinking) return;

            Packet<?> pkt = (Packet<?>) event.getPacket();

            // NEVER hold these - server kicks for timeout
            if (pkt instanceof C00PacketKeepAlive) return;
            if (pkt instanceof C01PacketChatMessage) return;

            // Hold position/movement packets while blinking
            if (pkt instanceof C03PacketPlayer) {
                event.setCancelled(true);
                queue.offer(pkt);
                return;
            }

            // Hold transaction confirmations during blink
            if (pkt instanceof C0FPacketConfirmTransaction) {
                event.setCancelled(true);
                queue.offer(pkt);
                return;
            }

            // Let everything else through (interactions, slot changes, etc.)
            // This is important so Scaffold, KillAura, etc. still work
            // even if we're briefly blinking
            return;
        }

        // ── Incoming packets ─────────────────────────────────────────────────
        if (event.getType() == EventType.RECEIVE) {
            Packet<?> pkt = (Packet<?>) event.getPacket();

            // Server teleport - respect it and stop blinking
            if (pkt instanceof S08PacketPlayerPosLook) {
                S08PacketPlayerPosLook tpPacket = (S08PacketPlayerPosLook) pkt;

                // Server is correcting our position - stop fighting it
                if (blinking) {
                    queue.clear(); // Discard our queued packets
                    blinking = false;
                    triggered = false;
                    blinkTicks = 0;
                }

                // Record the server's position as our new safe pos
                // (after a brief cooldown to let the teleport settle)
                serverTeleported = true;
                serverTpCooldown = 5;
                cooldownTicks = 10;

                // Update safe position to where server put us
                // This prevents re-triggering immediately after server TP
                safeX = tpPacket.getX();
                safeY = tpPacket.getY();
                safeZ = tpPacket.getZ();
                safeYaw = tpPacket.getYaw();
                safePitch = tpPacket.getPitch();
                hasSafePos = true;

                // Reset teleport tracking
                lastTpX = safeX;
                lastTpY = safeY;
                lastTpZ = safeZ;
                ticksSinceLastTp = 0;
            }
        }
    }

    // ========================
    // ENDER PEARL HANDLING
    // ========================

    @EventTarget
    public void onKey(KeyEvent event) {
        if (!this.isEnabled()) return;
        if (mc.thePlayer == null) return;
        if (event.getKey() != mc.gameSettings.keyBindUseItem.getKeyCode()) return;

        ItemStack item = mc.thePlayer.inventory.getCurrentItem();
        if (item != null && item.getItem() instanceof ItemEnderPearl) {
            // Pearl will teleport us - cancel blink so packets flow
            if (blinking) {
                abortBlink();
            }
            // Invalidate safe pos - pearl destination is unknown
            hasSafePos = false;
            triggered = false;
            cooldownTicks = 20; // Wait for pearl to land
        }
    }

    // ========================
    // MODULE CONFLICT CHECKS
    // ========================

    private boolean isScaffoldActive() {
        Module scaffold = Myau.moduleManager.modules.get(Scaffold.class);
        return scaffold != null && scaffold.isEnabled();
    }

    private boolean isLongJumpActive() {
        LongJump lj = (LongJump) Myau.moduleManager.modules.get(LongJump.class);
        return lj != null && lj.isEnabled() && lj.isJumping();
    }

    private boolean isFlyActive() {
        Module fly = Myau.moduleManager.modules.get(Fly.class);
        return fly != null && fly.isEnabled();
    }

    // ========================
    // BLOCK CHECKS
    // ========================

    /**
     * Check if there is any solid block below the player within `range` blocks.
     * Checks the player's full hitbox width, not just center column.
     * This prevents false triggers when falling near block edges.
     */
    private boolean hasSolidBlockBelowPlayer(int range) {
        double playerX = mc.thePlayer.posX;
        double playerZ = mc.thePlayer.posZ;
        int startY = MathHelper.floor_double(mc.thePlayer.posY) - 1;
        int minY = Math.max(0, startY - range);

        // Player hitbox corners
        double halfWidth = 0.3;
        int minBX = MathHelper.floor_double(playerX - halfWidth);
        int maxBX = MathHelper.floor_double(playerX + halfWidth);
        int minBZ = MathHelper.floor_double(playerZ - halfWidth);
        int maxBZ = MathHelper.floor_double(playerZ + halfWidth);

        // Also check predicted horizontal position if checkHorizontal is on
        if (checkHorizontal.getValue()) {
            double predX = playerX + mc.thePlayer.motionX * 5;
            double predZ = playerZ + mc.thePlayer.motionZ * 5;
            int predMinBX = MathHelper.floor_double(predX - halfWidth);
            int predMaxBX = MathHelper.floor_double(predX + halfWidth);
            int predMinBZ = MathHelper.floor_double(predZ - halfWidth);
            int predMaxBZ = MathHelper.floor_double(predZ + halfWidth);
            minBX = Math.min(minBX, predMinBX);
            maxBX = Math.max(maxBX, predMaxBX);
            minBZ = Math.min(minBZ, predMinBZ);
            maxBZ = Math.max(maxBZ, predMaxBZ);
        }

        for (int y = startY; y >= minY; y--) {
            for (int x = minBX; x <= maxBX; x++) {
                for (int z = minBZ; z <= maxBZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    Block block = mc.theWorld.getBlockState(pos).getBlock();
                    if (block != Blocks.air && block.getMaterial().isSolid()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ========================
    // PUBLIC API
    // ========================

    /**
     * Whether AntiVoid is currently blinking packets.
     * Other modules can check this to avoid conflicts.
     */
    public boolean isBlinking() {
        return this.blinking;
    }

    /**
     * Whether AntiVoid has been triggered (detected void fall).
     */
    public boolean isTriggered() {
        return this.triggered;
    }

    @Override
    public void verifyValue(String mode) {
        if (this.isEnabled()) {
            flush();
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(
                CaseFormat.UPPER_CAMEL, this.mode.getModeString())};
    }
}