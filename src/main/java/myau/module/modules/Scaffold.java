package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.*;
import myau.management.RotationState;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import myau.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.potion.Potion;
import net.minecraft.util.*;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import net.minecraft.world.WorldSettings.GameType;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;

@ModuleInfo(category = ModuleCategory.PLAYER)
public class Scaffold extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final double[] placeOffsets = new double[]{
            0.03125, 0.09375, 0.15625, 0.21875, 0.28125, 0.34375, 0.40625, 0.46875,
            0.53125, 0.59375, 0.65625, 0.71875, 0.78125, 0.84375, 0.90625, 0.96875
    };

    // Core state
    private int rotationTick = 0;
    private int lastSlot = -1;
    private int blockCount = -1;
    private float yaw = -180.0F;
    private float pitch = 0.0F;
    private float prevYaw = -180.0F;
    private float prevPitch = 0.0F;
    private boolean canRotate = false;
    private int towerTick = 0;
    private int towerDelay = 0;
    private int stage = 0;
    private int startY = 256;
    private boolean shouldKeepY = false;
    private boolean towering = false;
    private EnumFacing targetFacing = null;
    private int tellyTicksAir = 0;
    private boolean tellyJumped = false;
    private double lastOnGroundY = 0.0;
    private float lastServerYaw = -180.0F;
    private float lastServerPitch = 0.0F;

    // Anti-flag state
    private int enableTicks = 0;
    private boolean hasSentRotationThisTick = false;
    private boolean hasPlacedThisTick = false;
    private long lastDisableTime = 0L;
    private long lastEnableTime = 0L;
    private int placeCooldown = 0;

    // Block count cache
    private int cachedBlockCount = 0;

    // Knockback
    private double lastMotionX = 0.0;
    private double lastMotionZ = 0.0;
    private double lastMotionY = 0.0;
    private boolean kbDetected = false;
    private int kbTicks = 0;

    // Anti-DuplicateRotPlace
    private float lastRotationDeltaYaw = 0.0F;
    private int sameRotationDeltaCount = 0;

    // Properties
    public final ModeProperty rotationMode = new ModeProperty("rotations", 2,
            new String[]{"NONE", "DEFAULT", "BACKWARDS", "SIDEWAYS"});
    public final ModeProperty moveFix = new ModeProperty("move-fix", 1,
            new String[]{"NONE", "SILENT"});
    public final ModeProperty sprintMode = new ModeProperty("sprint", 0,
            new String[]{"NONE", "VANILLA"});
    public final PercentProperty groundMotion = new PercentProperty("ground-motion", 100);
    public final PercentProperty airMotion = new PercentProperty("air-motion", 100);
    public final PercentProperty speedMotion = new PercentProperty("speed-motion", 100);
    public final ModeProperty tower = new ModeProperty("tower", 0,
            new String[]{"NONE", "VANILLA", "EXTRA", "TELLY"});
    public final ModeProperty keepY = new ModeProperty("keep-y", 0,
            new String[]{"NONE", "VANILLA", "EXTRA", "TELLY"});
    public final BooleanProperty keepYonPress = new BooleanProperty("keep-y-on-press", false,
            () -> this.keepY.getValue() != 0);
    public final BooleanProperty disableWhileJumpActive = new BooleanProperty(
            "no-keep-y-on-jump-potion", false, () -> this.keepY.getValue() != 0);
    public final BooleanProperty multiplace = new BooleanProperty("multi-place", true);
    public final BooleanProperty safeWalk = new BooleanProperty("safe-walk", true);
    public final BooleanProperty swing = new BooleanProperty("swing", true);
    public final BooleanProperty itemSpoof = new BooleanProperty("item-spoof", false);
    public final BooleanProperty blockCounter = new BooleanProperty("block-counter", true);
    public final BooleanProperty thirdPersonView = new BooleanProperty("third-person-view", true);

    private int originalPerspective = 0;

    // ========================
    // BLOCK INVENTORY
    // ========================

    private void updateBlockInventory() {
        int total = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.thePlayer.inventory.getStackInSlot(i);
            if (s != null && s.stackSize > 0 && ItemUtil.isBlock(s)) total += s.stackSize;
        }
        this.cachedBlockCount = total;
    }

    private int findBestBlockSlot() {
        int current = mc.thePlayer.inventory.currentItem;
        ItemStack cur = mc.thePlayer.inventory.getStackInSlot(current);
        if (cur != null && cur.stackSize > 0 && ItemUtil.isBlock(cur)) return current;
        for (int i = 0; i < 9; i++) {
            if (i == current) continue;
            ItemStack s = mc.thePlayer.inventory.getStackInSlot(i);
            if (s != null && s.stackSize > 0 && ItemUtil.isBlock(s)) return i;
        }
        return -1;
    }

    private boolean hasPlaceableBlock() {
        if (this.itemSpoof.getValue()) {
            return findBestBlockSlot() != -1;
        }
        ItemStack held = mc.thePlayer.getHeldItem();
        return held != null && held.stackSize > 0 && ItemUtil.isBlock(held);
    }

    // ========================
    // ITEM SPOOF
    // No C09 packets ever sent. We temporarily set inventory.currentItem
    // to the block slot, call placement, then restore immediately within
    // the same method call. Vanilla only auto-sends C09 between its own
    // ticks, not mid-tick, so no C09 leaks out.
    // ========================

    /**
     * Call before placement. Returns saved slot (>=0), -2 if not spoofing, -1 if no block.
     */
    private int beginItemSpoof() {
        if (!this.itemSpoof.getValue()) return -2;
        int blockSlot = findBestBlockSlot();
        if (blockSlot == -1) return -1;
        int saved = mc.thePlayer.inventory.currentItem;
        mc.thePlayer.inventory.currentItem = blockSlot;
        return saved;
    }

    /**
     * Call after placement with value returned by beginItemSpoof().
     */
    private void finishItemSpoof(int savedSlot) {
        if (savedSlot < 0) return; // -2 = not spoofing, -1 = error, both skip
        mc.thePlayer.inventory.currentItem = savedSlot;
    }

    // ========================
    // KNOCKBACK
    // ========================

    private void detectKnockback() {
        double dx = mc.thePlayer.motionX - this.lastMotionX;
        double dz = mc.thePlayer.motionZ - this.lastMotionZ;
        double dy = mc.thePlayer.motionY - this.lastMotionY;
        double hDelta = Math.sqrt(dx * dx + dz * dz);

        if ((hDelta > 0.1 || dy > 0.2) && !mc.thePlayer.onGround) {
            this.kbDetected = true;
            this.kbTicks = 0;
            this.canRotate = false;
            this.rotationTick = 0;
            float newMoveYaw = (float) (Math.atan2(-mc.thePlayer.motionX, mc.thePlayer.motionZ) * 180.0 / Math.PI);
            float targetYaw = MathHelper.wrapAngleTo180_float(newMoveYaw + 180.0F);
            float[] smoothed = smoothRotation(this.yaw, this.pitch, targetYaw, 82.0F);
            this.yaw = smoothed[0];
            this.pitch = smoothed[1];
        }
        if (this.kbDetected) {
            this.kbTicks++;
            if (this.kbTicks > 10 || mc.thePlayer.onGround) {
                this.kbDetected = false;
                this.kbTicks = 0;
            }
        }
        this.lastMotionX = mc.thePlayer.motionX;
        this.lastMotionZ = mc.thePlayer.motionZ;
        this.lastMotionY = mc.thePlayer.motionY;
    }

    // ========================
    // ROTATION
    // ========================

    private float[] smoothRotation(float currentYaw, float currentPitch,
                                   float targetYaw, float targetPitch) {
        float sensitivity = mc.gameSettings.mouseSensitivity * 0.6F + 0.2F;
        float gcd = sensitivity * sensitivity * sensitivity * 1.2F;

        currentYaw = MathHelper.wrapAngleTo180_float(currentYaw);
        targetYaw = MathHelper.wrapAngleTo180_float(targetYaw);

        float yawDiff = MathHelper.wrapAngleTo180_float(targetYaw - currentYaw);
        float pitchDiff = targetPitch - currentPitch;

        if (Math.abs(yawDiff) > gcd) {
            yawDiff = yawDiff - (yawDiff % gcd);
        } else {
            yawDiff = 0;
        }
        if (Math.abs(pitchDiff) > gcd) {
            pitchDiff = pitchDiff - (pitchDiff % gcd);
        } else {
            pitchDiff = 0;
        }

        // DuplicateRotPlace jitter
        if (Math.abs(yawDiff) > 2.0F) {
            float absDiff = Math.abs(yawDiff - this.lastRotationDeltaYaw);
            if (absDiff < 0.001F) {
                this.sameRotationDeltaCount++;
                if (this.sameRotationDeltaCount >= 1) {
                    float jitterDir = (this.sameRotationDeltaCount % 2 == 0) ? 1.0F : -1.0F;
                    yawDiff += gcd * jitterDir;
                    this.sameRotationDeltaCount = 0;
                }
            } else {
                this.sameRotationDeltaCount = 0;
            }
        }
        this.lastRotationDeltaYaw = yawDiff;

        float newYaw = MathHelper.wrapAngleTo180_float(currentYaw + yawDiff);
        float newPitch = MathHelper.clamp_float(currentPitch + pitchDiff, -90.0F, 90.0F);
        return new float[]{newYaw, newPitch};
    }

    // ========================
    // PREDICTION
    // ========================

    private double getPredictedY(int ticks) {
        double posY = mc.thePlayer.posY;
        double motionY = mc.thePlayer.motionY;
        for (int i = 0; i < ticks; i++) {
            posY += motionY;
            motionY -= 0.08;
            motionY *= 0.98;
        }
        return posY;
    }

    private int getTicksToLandHeight() {
        double posY = mc.thePlayer.posY;
        double motionY = mc.thePlayer.motionY;
        for (int i = 0; i < 40; i++) {
            posY += motionY;
            motionY -= 0.08;
            motionY *= 0.98;
            if (posY <= this.startY + 1.0) return i + 1;
        }
        return 40;
    }

    // ========================
    // UTILITY
    // ========================

    private boolean shouldStopSprint() {
        if (this.isTowering()) return false;
        boolean stageActive = this.keepY.getValue() == 1 || this.keepY.getValue() == 2;
        return (!stageActive || this.stage <= 0) && this.sprintMode.getValue() == 0;
    }

    private boolean canPlaceBlock() {
        if (this.placeCooldown > 0) return false;
        if (!this.hasPlaceableBlock()) return false;
        BedNuker bedNuker = (BedNuker) Myau.moduleManager.modules.get(BedNuker.class);
        if (bedNuker.isEnabled() && bedNuker.isReady()) return false;
        LongJump longJump = (LongJump) Myau.moduleManager.modules.get(LongJump.class);
        return !longJump.isEnabled() || !longJump.isAutoMode() || longJump.isJumping();
    }

    private EnumFacing getBestFacing(BlockPos blockPos1, BlockPos blockPos3) {
        double offset = 0.0;
        EnumFacing enumFacing = null;
        for (EnumFacing facing : EnumFacing.VALUES) {
            if (facing != EnumFacing.DOWN) {
                BlockPos pos = blockPos1.offset(facing);
                if (pos.getY() <= blockPos3.getY()) {
                    double distance = pos.distanceSqToCenter(
                            blockPos3.getX() + 0.5, blockPos3.getY() + 0.5, blockPos3.getZ() + 0.5);
                    if (enumFacing == null || distance < offset
                            || (distance == offset && facing == EnumFacing.UP)) {
                        offset = distance;
                        enumFacing = facing;
                    }
                }
            }
        }
        return enumFacing;
    }

    private BlockData getBlockData() {
        int sy = MathHelper.floor_double(mc.thePlayer.posY);
        BlockPos targetPos = new BlockPos(
                MathHelper.floor_double(mc.thePlayer.posX),
                (this.stage != 0 && !this.shouldKeepY ? Math.min(sy, this.startY) : sy) - 1,
                MathHelper.floor_double(mc.thePlayer.posZ));
        if (!BlockUtil.isReplaceable(targetPos)) return null;

        ArrayList<BlockPos> positions = new ArrayList<>();
        for (int x = -4; x <= 4; x++) {
            for (int y = -4; y <= 0; y++) {
                for (int z = -4; z <= 4; z++) {
                    BlockPos pos = targetPos.add(x, y, z);
                    if (!BlockUtil.isReplaceable(pos)
                            && !BlockUtil.isInteractable(pos)
                            && !(mc.thePlayer.getDistance(
                            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
                            > mc.playerController.getBlockReachDistance())
                            && (this.stage == 0 || this.shouldKeepY || pos.getY() < this.startY)) {
                        for (EnumFacing facing : EnumFacing.VALUES) {
                            if (facing != EnumFacing.DOWN
                                    && BlockUtil.isReplaceable(pos.offset(facing))) {
                                positions.add(pos);
                                break;
                            }
                        }
                    }
                }
            }
        }
        if (positions.isEmpty()) return null;

        positions.sort(Comparator.comparingDouble(o ->
                o.distanceSqToCenter(
                        targetPos.getX() + 0.5,
                        targetPos.getY() + 0.5,
                        targetPos.getZ() + 0.5)));
        BlockPos blockPos = positions.get(0);
        EnumFacing facing = this.getBestFacing(blockPos, targetPos);
        return facing == null ? null : new BlockData(blockPos, facing);
    }

    /**
     * Place a block with instant item spoof:
     * temporarily swap inventory.currentItem to block slot,
     * place, restore immediately. No C09 packets sent.
     */
    private void place(BlockPos blockPos, EnumFacing enumFacing, Vec3 vec3) {
        int savedSlot = beginItemSpoof();
        if (savedSlot == -1) return; // no block found in spoof mode

        if (!this.itemSpoof.getValue()) {
            // non-spoof: must be holding a block with count
            if (!ItemUtil.isHoldingBlock() || this.blockCount <= 0) return;
        }

        ItemStack heldItem = mc.thePlayer.inventory.getCurrentItem();
        if (heldItem == null || heldItem.stackSize <= 0 || !ItemUtil.isBlock(heldItem)) {
            finishItemSpoof(savedSlot);
            return;
        }

        if (mc.playerController.onPlayerRightClick(
                mc.thePlayer, mc.theWorld, heldItem, blockPos, enumFacing, vec3)) {
            if (mc.playerController.getCurrentGameType() != GameType.CREATIVE) {
                this.blockCount--;
            }
            if (this.swing.getValue()) {
                mc.thePlayer.swingItem();
            } else {
                PacketUtil.sendPacket(new C0APacketAnimation());
            }
            this.hasPlacedThisTick = true;
        }

        finishItemSpoof(savedSlot);
    }

    private EnumFacing yawToFacing(float yaw) {
        if (yaw < -135.0F || yaw > 135.0F) return EnumFacing.NORTH;
        if (yaw < -45.0F) return EnumFacing.EAST;
        return yaw < 45.0F ? EnumFacing.SOUTH : EnumFacing.WEST;
    }

    private double distanceToEdge(EnumFacing enumFacing) {
        switch (enumFacing) {
            case NORTH: return mc.thePlayer.posZ - Math.floor(mc.thePlayer.posZ);
            case EAST:  return Math.ceil(mc.thePlayer.posX) - mc.thePlayer.posX;
            case SOUTH: return Math.ceil(mc.thePlayer.posZ) - mc.thePlayer.posZ;
            default:    return mc.thePlayer.posX - Math.floor(mc.thePlayer.posX);
        }
    }

    private float getSpeed() {
        if (!mc.thePlayer.onGround) return (float) this.airMotion.getValue() / 100.0F;
        return MoveUtil.getSpeedLevel() > 0
                ? (float) this.speedMotion.getValue() / 100.0F
                : (float) this.groundMotion.getValue() / 100.0F;
    }

    private double getRandomOffset() {
        return 0.2155 - RandomUtil.nextDouble(1.0E-4, 9.0E-4);
    }

    private float getCurrentYaw() {
        return MoveUtil.adjustYaw(mc.thePlayer.rotationYaw,
                (float) MoveUtil.getForwardValue(),
                (float) MoveUtil.getLeftValue());
    }

    private boolean isDiagonal(float yaw) {
        float absYaw = Math.abs(yaw % 90.0F);
        return absYaw > 20.0F && absYaw < 70.0F;
    }

    private boolean isTowering() {
        if (mc.thePlayer.onGround && MoveUtil.isForwardPressed() && !PlayerUtil.isAirAbove()) {
            boolean ky = this.keepY.getValue() == 3;
            boolean tw = this.tower.getValue() == 3;
            return (ky && this.stage > 0) || (tw && mc.gameSettings.keyBindJump.isKeyDown());
        }
        return false;
    }

    // ========================
    // CONSTRUCTOR
    // ========================

    public Scaffold() {
        super("Scaffold", false);
    }

    public int getSlot() {
        return this.lastSlot;
    }

    // ========================
    // EVENT HANDLERS
    // ========================

    @EventTarget(Priority.HIGH)
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) return;

        this.hasSentRotationThisTick = false;
        this.hasPlacedThisTick = false;
        this.enableTicks++;
        if (this.placeCooldown > 0) this.placeCooldown--;

        updateBlockInventory();
        detectKnockback();

        // Block count tracking
        {
            ItemStack stack = mc.thePlayer.getHeldItem();
            int count = (stack != null && ItemUtil.isBlock(stack)) ? stack.stackSize : 0;
            if (count > 0) {
                this.blockCount = (this.blockCount == -1) ? count : Math.min(this.blockCount, count);
            }
            if (this.blockCount <= 0 && !this.itemSpoof.getValue()) {
                int slot = mc.thePlayer.inventory.currentItem;
                if (this.blockCount == 0) slot--;
                for (int i = slot; i > slot - 9; i--) {
                    int hs = (i % 9 + 9) % 9;
                    ItemStack candidate = mc.thePlayer.inventory.getStackInSlot(hs);
                    if (candidate != null && ItemUtil.isBlock(candidate)) {
                        mc.thePlayer.inventory.currentItem = hs;
                        this.blockCount = candidate.stackSize;
                        break;
                    }
                }
            }
            if (this.blockCount <= 0 && this.itemSpoof.getValue()) {
                int best = findBestBlockSlot();
                if (best != -1) {
                    ItemStack s = mc.thePlayer.inventory.getStackInSlot(best);
                    this.blockCount = (s != null) ? s.stackSize : 0;
                }
            }
        }

        AntiFireball antiFireball = (AntiFireball) Myau.moduleManager.modules.get(AntiFireball.class);
        if (antiFireball != null && antiFireball.isDeflecting()) return;

        if (this.rotationTick > 0) this.rotationTick--;

        // Ground state
        if (mc.thePlayer.onGround) {
            if (this.stage > 0) this.stage--;
            if (this.stage < 0) this.stage++;
            if (this.stage == 0
                    && this.keepY.getValue() != 0
                    && (!(Boolean) this.keepYonPress.getValue() || PlayerUtil.isUsingItem())
                    && (!this.disableWhileJumpActive.getValue()
                    || !mc.thePlayer.isPotionActive(Potion.jump))
                    && !mc.gameSettings.keyBindJump.isKeyDown()) {
                this.stage = 1;
            }
            this.startY = this.shouldKeepY ? this.startY
                    : MathHelper.floor_double(mc.thePlayer.posY);
            this.shouldKeepY = false;
            this.towering = false;
            this.tellyTicksAir = 0;
            this.tellyJumped = false;
            this.lastOnGroundY = mc.thePlayer.posY;
        } else {
            this.tellyTicksAir++;
        }

        if (!this.canPlaceBlock() && !this.kbDetected) {
            if (this.rotationMode.getValue() != 0 && this.canRotate) {
                float sendYaw = MathHelper.wrapAngleTo180_float(this.yaw);
                event.setRotation(sendYaw, this.pitch, 3);
                this.lastServerYaw = sendYaw;
                this.lastServerPitch = this.pitch;
                this.hasSentRotationThisTick = true;
                if (this.moveFix.getValue() == 1) event.setPervRotation(sendYaw, 3);
            }
            return;
        }

        // Rotation logic
        float currentYaw = this.getCurrentYaw();
        float yawDiffTo180 = MathHelper.wrapAngleTo180_float(
                RotationUtil.wrapAngleDiff(currentYaw - 180.0F, event.getYaw()));
        float diagonalYaw = this.isDiagonal(currentYaw)
                ? yawDiffTo180
                : MathHelper.wrapAngleTo180_float(
                RotationUtil.wrapAngleDiff(
                        currentYaw - 135.0F * (
                                (currentYaw + 180.0F) % 90.0F < 45.0F ? 1.0F : -1.0F),
                        event.getYaw()));

        if (!this.canRotate) {
            float targetBaseYaw, targetBasePitch;
            switch (this.rotationMode.getValue()) {
                case 1: targetBaseYaw = diagonalYaw;  targetBasePitch = 85.0F; break;
                case 2: targetBaseYaw = yawDiffTo180; targetBasePitch = 85.0F; break;
                case 3: targetBaseYaw = diagonalYaw;  targetBasePitch = 85.0F; break;
                default: targetBaseYaw = this.yaw;    targetBasePitch = this.pitch; break;
            }
            if (this.yaw == -180.0F && this.pitch == 0.0F) {
                float[] s = smoothRotation(event.getYaw(), event.getPitch(),
                        targetBaseYaw, targetBasePitch);
                this.yaw = s[0];
                this.pitch = s[1];
            } else {
                float[] s = smoothRotation(this.yaw, this.pitch,
                        targetBaseYaw, targetBasePitch);
                this.yaw = s[0];
            }
        }

        BlockData blockData = this.getBlockData();
        Vec3 hitVec = null;

        if (blockData != null) {
            double[] xArr = placeOffsets, yArr = placeOffsets, zArr = placeOffsets;
            switch (blockData.facing()) {
                case NORTH: zArr = new double[]{0.0}; break;
                case EAST:  xArr = new double[]{1.0}; break;
                case SOUTH: zArr = new double[]{1.0}; break;
                case WEST:  xArr = new double[]{0.0}; break;
                case DOWN:  yArr = new double[]{0.0}; break;
                case UP:    yArr = new double[]{1.0}; break;
            }

            float bestYaw = -180.0F, bestPitch = 0.0F;
            float bestDiff = Float.MAX_VALUE;
            boolean foundValid = false;

            for (double dx : xArr) {
                for (double dy : yArr) {
                    for (double dz : zArr) {
                        double relX = blockData.blockPos().getX() + dx - mc.thePlayer.posX;
                        double relY = blockData.blockPos().getY() + dy
                                - mc.thePlayer.posY - mc.thePlayer.getEyeHeight();
                        double relZ = blockData.blockPos().getZ() + dz - mc.thePlayer.posZ;

                        float baseYaw = MathHelper.wrapAngleTo180_float(
                                RotationUtil.wrapAngleDiff(this.yaw, event.getYaw()));
                        float rawTargetYaw = MathHelper.wrapAngleTo180_float(
                                (float) (Math.atan2(-relX, relZ) * 180.0 / Math.PI));
                        float dist = (float) Math.sqrt(relX * relX + relZ * relZ);
                        float rawTargetPitch =
                                (float) (-(Math.atan2(relY, dist)) * 180.0 / Math.PI);

                        float[] smoothed = smoothRotation(baseYaw, this.pitch,
                                rawTargetYaw, rawTargetPitch);

                        MovingObjectPosition mop = RotationUtil.rayTrace(
                                smoothed[0], smoothed[1],
                                mc.playerController.getBlockReachDistance(), 1.0F);
                        if (mop != null && mop.typeOfHit == MovingObjectType.BLOCK
                                && mop.getBlockPos().equals(blockData.blockPos())
                                && mop.sideHit == blockData.facing()) {
                            float totalDiff =
                                    Math.abs(MathHelper.wrapAngleTo180_float(
                                            smoothed[0] - baseYaw))
                                            + Math.abs(smoothed[1] - this.pitch);
                            if (!foundValid || totalDiff < bestDiff) {
                                bestYaw = smoothed[0]; bestPitch = smoothed[1];
                                bestDiff = totalDiff; hitVec = mop.hitVec;
                                foundValid = true;
                            }
                        }

                        if (!foundValid) {
                            float[] rotations = RotationUtil.getRotationsTo(
                                    relX, relY, relZ, baseYaw, this.pitch);
                            mop = RotationUtil.rayTrace(rotations[0], rotations[1],
                                    mc.playerController.getBlockReachDistance(), 1.0F);
                            if (mop != null && mop.typeOfHit == MovingObjectType.BLOCK
                                    && mop.getBlockPos().equals(blockData.blockPos())
                                    && mop.sideHit == blockData.facing()) {
                                float[] gcd = smoothRotation(baseYaw, this.pitch,
                                        rotations[0], rotations[1]);
                                MovingObjectPosition gcdMop = RotationUtil.rayTrace(
                                        gcd[0], gcd[1],
                                        mc.playerController.getBlockReachDistance(), 1.0F);
                                if (gcdMop != null
                                        && gcdMop.typeOfHit == MovingObjectType.BLOCK
                                        && gcdMop.getBlockPos().equals(blockData.blockPos())
                                        && gcdMop.sideHit == blockData.facing()) {
                                    float totalDiff =
                                            Math.abs(MathHelper.wrapAngleTo180_float(
                                                    gcd[0] - baseYaw))
                                                    + Math.abs(gcd[1] - this.pitch);
                                    if (totalDiff < bestDiff) {
                                        bestYaw = gcd[0]; bestPitch = gcd[1];
                                        bestDiff = totalDiff; hitVec = gcdMop.hitVec;
                                        foundValid = true;
                                    }
                                } else {
                                    float totalDiff =
                                            Math.abs(MathHelper.wrapAngleTo180_float(
                                                    rotations[0] - baseYaw))
                                                    + Math.abs(rotations[1] - this.pitch);
                                    if (totalDiff < bestDiff) {
                                        bestYaw = rotations[0]; bestPitch = rotations[1];
                                        bestDiff = totalDiff; hitVec = mop.hitVec;
                                        foundValid = true;
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (foundValid) {
                this.yaw = MathHelper.wrapAngleTo180_float(bestYaw);
                this.pitch = bestPitch;
                this.canRotate = true;
            }
        }

        if (this.canRotate && MoveUtil.isForwardPressed()
                && Math.abs(MathHelper.wrapAngleTo180_float(yawDiffTo180 - this.yaw)) < 90.0F) {
            switch (this.rotationMode.getValue()) {
                case 2:
                    this.yaw = smoothRotation(this.yaw, this.pitch, yawDiffTo180, this.pitch)[0];
                    break;
                case 3:
                    this.yaw = smoothRotation(this.yaw, this.pitch, diagonalYaw, this.pitch)[0];
                    break;
            }
        }

        if (this.rotationMode.getValue() != 0) {
            float targetYaw = MathHelper.wrapAngleTo180_float(this.yaw);
            float targetPitch = this.pitch;

            if (this.towering && (mc.thePlayer.motionY > 0.0
                    || mc.thePlayer.posY > (double) (this.startY + 1))) {
                float yd = MathHelper.wrapAngleTo180_float(this.yaw - event.getYaw());
                int ticksToLand = getTicksToLandHeight();
                float tolerance = ticksToLand <= 2 ? 180.0F
                        : (this.rotationTick >= 2 ? 90.0F : 30.0F);
                if (Math.abs(yd) > tolerance) {
                    float clampedYaw = RotationUtil.clampAngle(yd, tolerance);
                    float rawTarget = event.getYaw() + clampedYaw;
                    float[] s = smoothRotation(event.getYaw(), event.getPitch(),
                            rawTarget, targetPitch);
                    targetYaw = s[0];
                    this.rotationTick = Math.max(this.rotationTick, 1);
                }
            }

            if (this.isTowering()) {
                float yawDelta = MathHelper.wrapAngleTo180_float(
                        mc.thePlayer.rotationYaw - event.getYaw());
                float lookYaw = event.getYaw() + yawDelta * 0.985F;
                float lookPitch = 75.0F + (float) (mc.thePlayer.motionY * 10.0);
                float[] s = smoothRotation(
                        this.prevYaw != -180.0F ? this.prevYaw : event.getYaw(),
                        this.prevPitch != 0.0F ? this.prevPitch : event.getPitch(),
                        lookYaw,
                        MathHelper.clamp_float(lookPitch, 30.0F, 85.0F));
                targetYaw = s[0];
                targetPitch = s[1];
                this.rotationTick = 3;
                this.towering = true;
            }

            targetYaw = MathHelper.wrapAngleTo180_float(targetYaw);
            this.prevYaw = targetYaw;
            this.prevPitch = targetPitch;

            event.setRotation(targetYaw, targetPitch, 3);
            this.lastServerYaw = targetYaw;
            this.lastServerPitch = targetPitch;
            this.hasSentRotationThisTick = true;
            if (this.moveFix.getValue() == 1) event.setPervRotation(targetYaw, 3);
        }

        // Placement - 0ms reaction, no enableTicks gate, no hasRotatedSinceEnable gate
        boolean canPlaceNow = this.placeCooldown <= 0
                && this.rotationTick <= 0
                && hasPlaceableBlock()
                && !this.hasPlacedThisTick;

        if (blockData != null && hitVec != null && canPlaceNow) {
            this.place(blockData.blockPos(), blockData.facing(), hitVec);

            if (this.multiplace.getValue() && this.hasPlacedThisTick) {
                for (int i = 0; i < 3; i++) {
                    if (!hasPlaceableBlock()) break;
                    blockData = this.getBlockData();
                    if (blockData == null) break;

                    MovingObjectPosition mop = RotationUtil.rayTrace(
                            this.yaw, this.pitch,
                            mc.playerController.getBlockReachDistance(), 1.0F);
                    if (mop != null && mop.typeOfHit == MovingObjectType.BLOCK
                            && mop.getBlockPos().equals(blockData.blockPos())
                            && mop.sideHit == blockData.facing()) {
                        this.place(blockData.blockPos(), blockData.facing(), mop.hitVec);
                    } else {
                        hitVec = BlockUtil.getClickVec(blockData.blockPos(), blockData.facing());
                        double ddx = hitVec.xCoord - mc.thePlayer.posX;
                        double ddy = hitVec.yCoord - mc.thePlayer.posY
                                - mc.thePlayer.getEyeHeight();
                        double ddz = hitVec.zCoord - mc.thePlayer.posZ;
                        float[] rotations = RotationUtil.getRotationsTo(
                                ddx, ddy, ddz, event.getYaw(), event.getPitch());
                        if (Math.abs(MathHelper.wrapAngleTo180_float(
                                rotations[0] - this.yaw)) >= 120.0F
                                || Math.abs(rotations[1] - this.pitch) >= 60.0F) break;
                        mop = RotationUtil.rayTrace(rotations[0], rotations[1],
                                mc.playerController.getBlockReachDistance(), 1.0F);
                        if (mop == null || mop.typeOfHit != MovingObjectType.BLOCK
                                || !mop.getBlockPos().equals(blockData.blockPos())
                                || mop.sideHit != blockData.facing()) break;
                        this.place(blockData.blockPos(), blockData.facing(), mop.hitVec);
                    }
                }
            }
        }

        if (this.targetFacing != null) {
            if (this.rotationTick <= 0 && canPlaceNow) {
                BlockPos belowPlayer = new BlockPos(
                        MathHelper.floor_double(mc.thePlayer.posX),
                        MathHelper.floor_double(mc.thePlayer.posY) - 1,
                        MathHelper.floor_double(mc.thePlayer.posZ));
                hitVec = BlockUtil.getHitVec(belowPlayer, this.targetFacing, this.yaw, this.pitch);
                this.place(belowPlayer, this.targetFacing, hitVec);
            }
            this.targetFacing = null;
        } else if (this.keepY.getValue() == 2 && this.stage > 0 && !mc.thePlayer.onGround) {
            double predictedY = getPredictedY(1);
            int nextBlockY = MathHelper.floor_double(predictedY);
            if (nextBlockY <= this.startY && mc.thePlayer.posY > (double) (this.startY + 1)) {
                this.shouldKeepY = true;
                blockData = this.getBlockData();
                if (blockData != null && this.rotationTick <= 0 && canPlaceNow) {
                    hitVec = BlockUtil.getHitVec(
                            blockData.blockPos(), blockData.facing(), this.yaw, this.pitch);
                    this.place(blockData.blockPos(), blockData.facing(), hitVec);
                }
            }
        }
    }

    @EventTarget
    public void onStrafe(StrafeEvent event) {
        if (!this.isEnabled()) return;

        if (!mc.thePlayer.isCollidedHorizontally
                && mc.thePlayer.hurtTime <= 5
                && !mc.thePlayer.isPotionActive(Potion.jump)
                && mc.gameSettings.keyBindJump.isKeyDown()
                && (hasPlaceableBlock() || this.itemSpoof.getValue())) {
            int yState = (int) (mc.thePlayer.posY % 1.0 * 100.0);
            switch (this.tower.getValue()) {
                case 1:
                    switch (this.towerTick) {
                        case 0:
                            if (mc.thePlayer.onGround) {
                                this.towerTick = 1;
                                mc.thePlayer.motionY = -0.0784000015258789;
                            }
                            return;
                        case 1:
                            if (yState == 0 && PlayerUtil.isAirBelow()) {
                                this.startY = MathHelper.floor_double(mc.thePlayer.posY);
                                this.towerTick = 2;
                                mc.thePlayer.motionY = 0.42F;
                                if (!MoveUtil.isForwardPressed()) {
                                    event.setForward(0.0F);
                                    event.setStrafe(0.0F);
                                }
                                return;
                            } else {
                                this.towerTick = 0;
                                return;
                            }
                        case 2:
                            this.towerTick = 3;
                            mc.thePlayer.motionY = 0.75 - mc.thePlayer.posY % 1.0;
                            return;
                        case 3:
                            this.towerTick = 1;
                            mc.thePlayer.motionY = 1.0 - mc.thePlayer.posY % 1.0;
                            return;
                        default:
                            this.towerTick = 0;
                            return;
                    }
                case 2:
                    switch (this.towerTick) {
                        case 0:
                            if (mc.thePlayer.onGround) {
                                this.towerTick = 1;
                                mc.thePlayer.motionY = -0.0784000015258789;
                            }
                            return;
                        case 1:
                            if (yState == 0 && PlayerUtil.isAirBelow()) {
                                this.startY = MathHelper.floor_double(mc.thePlayer.posY);
                                if (!MoveUtil.isForwardPressed()) {
                                    this.towerDelay = 2;
                                    event.setForward(0.0F);
                                    event.setStrafe(0.0F);
                                    EnumFacing facing = this.yawToFacing(
                                            MathHelper.wrapAngleTo180_float(this.yaw - 180.0F));
                                    double distance = this.distanceToEdge(facing);
                                    if (distance > 0.1) {
                                        if (mc.thePlayer.onGround) {
                                            Vec3i dv = facing.getDirectionVec();
                                            double offset = Math.min(
                                                    this.getRandomOffset(), distance - 0.05);
                                            double jitter = RandomUtil.nextDouble(0.02, 0.03);
                                            AxisAlignedBB nextBox = mc.thePlayer
                                                    .getEntityBoundingBox()
                                                    .offset(dv.getX() * (offset - jitter),
                                                            0.0,
                                                            dv.getZ() * (offset - jitter));
                                            if (mc.theWorld.getCollidingBoundingBoxes(
                                                    mc.thePlayer, nextBox).isEmpty()) {
                                                mc.thePlayer.motionY = -0.0784000015258789;
                                                mc.thePlayer.setPosition(
                                                        nextBox.minX + (nextBox.maxX - nextBox.minX) / 2.0,
                                                        nextBox.minY,
                                                        nextBox.minZ + (nextBox.maxZ - nextBox.minZ) / 2.0);
                                            }
                                            return;
                                        }
                                    } else {
                                        this.towerTick = 2;
                                        this.targetFacing = facing;
                                        mc.thePlayer.motionY = 0.42F;
                                    }
                                    return;
                                } else {
                                    this.towerTick = 2;
                                    this.towerDelay++;
                                    mc.thePlayer.motionY = 0.42F;
                                    return;
                                }
                            } else {
                                this.towerTick = 0;
                                this.towerDelay = 0;
                                return;
                            }
                        case 2:
                            this.towerTick = 3;
                            mc.thePlayer.motionY -= RandomUtil.nextDouble(0.00101, 0.00109);
                            return;
                        case 3:
                            if (this.towerDelay >= 4) {
                                this.towerTick = 4;
                                this.towerDelay = 0;
                            } else {
                                this.towerTick = 1;
                                mc.thePlayer.motionY = 1.0 - mc.thePlayer.posY % 1.0;
                            }
                            return;
                        case 4:
                            this.towerTick = 5;
                            return;
                        case 5:
                            if (!PlayerUtil.isAirBelow()) {
                                this.towerTick = 0;
                            } else {
                                this.towerTick = 1;
                                mc.thePlayer.motionY -= 0.08;
                                mc.thePlayer.motionY *= 0.98F;
                                mc.thePlayer.motionY -= 0.08;
                                mc.thePlayer.motionY *= 0.98F;
                            }
                            return;
                        default:
                            this.towerTick = 0;
                            this.towerDelay = 0;
                            return;
                    }
                case 3:
                    if (mc.thePlayer.onGround) {
                        this.startY = MathHelper.floor_double(mc.thePlayer.posY);
                        BlockData postJumpData = this.getBlockData();
                        if (postJumpData != null || !BlockUtil.isReplaceable(new BlockPos(
                                MathHelper.floor_double(mc.thePlayer.posX),
                                MathHelper.floor_double(mc.thePlayer.posY) - 1,
                                MathHelper.floor_double(mc.thePlayer.posZ)))) {
                            mc.thePlayer.motionY = 0.42F;
                            this.tellyJumped = true;
                            this.tellyTicksAir = 0;
                            this.towerTick = 1;
                        }
                    } else if (this.towerTick > 0) {
                        this.towerTick++;
                        double predictedNextY = getPredictedY(1);
                        if (MathHelper.floor_double(predictedNextY) <= this.startY
                                && mc.thePlayer.motionY < 0) {
                            this.shouldKeepY = true;
                        }
                        if (mc.thePlayer.onGround) {
                            this.towerTick = 0;
                            this.tellyJumped = false;
                        }
                    }
                    return;
                default:
                    this.towerTick = 0;
                    this.towerDelay = 0;
            }
        } else {
            this.towerTick = 0;
            this.towerDelay = 0;
        }
    }

    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        if (!this.isEnabled()) return;
        if (this.moveFix.getValue() == 1
                && RotationState.isActived()
                && RotationState.getPriority() == 3.0F
                && MoveUtil.isForwardPressed()) {
            MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
        }
        if (mc.thePlayer.onGround && this.stage > 0 && MoveUtil.isForwardPressed()) {
            mc.thePlayer.movementInput.jump = true;
        }
    }

    @EventTarget
    public void onLivingUpdate(LivingUpdateEvent event) {
        if (!this.isEnabled()) return;
        float speed = this.getSpeed();
        if (speed != 1.0F) {
            if (mc.thePlayer.movementInput.moveForward != 0.0F
                    && mc.thePlayer.movementInput.moveStrafe != 0.0F) {
                mc.thePlayer.movementInput.moveForward *= (1.0F / (float) Math.sqrt(2.0));
                mc.thePlayer.movementInput.moveStrafe *= (1.0F / (float) Math.sqrt(2.0));
            }
            mc.thePlayer.movementInput.moveForward *= speed;
            mc.thePlayer.movementInput.moveStrafe *= speed;
        }
        if (this.shouldStopSprint()) mc.thePlayer.setSprinting(false);
    }

    @EventTarget
    public void onSafeWalk(SafeWalkEvent event) {
        if (this.isEnabled() && this.safeWalk.getValue()) {
            if (mc.thePlayer.onGround && mc.thePlayer.motionY <= 0.0
                    && PlayerUtil.canMove(
                    mc.thePlayer.motionX, mc.thePlayer.motionZ, -1.0)) {
                event.setSafeWalk(true);
            }
        }
    }

    @EventTarget
    public void onRender(Render2DEvent event) {
        if (!this.isEnabled() || !this.blockCounter.getValue()) return;
        int count = this.cachedBlockCount;
        HUD hud = (HUD) Myau.moduleManager.modules.get(HUD.class);
        float scale = hud.scale.getValue();
        GlStateManager.pushMatrix();
        GlStateManager.scale(scale, scale, 0.0F);
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        mc.fontRendererObj.drawString(
                String.format("%d block%s left", count, count != 1 ? "s" : ""),
                ((float) new ScaledResolution(mc).getScaledWidth() / 2.0F
                        + (float) mc.fontRendererObj.FONT_HEIGHT * 1.5F) / scale,
                (float) new ScaledResolution(mc).getScaledHeight() / 2.0F / scale
                        - (float) mc.fontRendererObj.FONT_HEIGHT / 2.0F + 1.0F,
                (count > 0 ? Color.WHITE.getRGB() : new Color(255, 85, 85).getRGB())
                        | -1090519040,
                hud.shadow.getValue());
        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
    }

    @EventTarget
    public void onLeftClick(LeftClickMouseEvent event) {
        if (this.isEnabled()) event.setCancelled(true);
    }

    @EventTarget
    public void onRightClick(RightClickMouseEvent event) {
        if (this.isEnabled()) event.setCancelled(true);
    }

    @EventTarget
    public void onHitBlock(HitBlockEvent event) {
        if (this.isEnabled()) event.setCancelled(true);
    }

    @EventTarget
    public void onSwap(SwapItemEvent event) {
        if (this.isEnabled()) {
            this.lastSlot = event.setSlot(this.lastSlot);
            event.setCancelled(true);
        }
    }

    @Override
    public void onEnabled() {
        this.lastEnableTime = System.currentTimeMillis();

        if (mc.thePlayer != null) {
            this.lastSlot = mc.thePlayer.inventory.currentItem;
            this.lastOnGroundY = mc.thePlayer.posY;
            this.lastMotionX = mc.thePlayer.motionX;
            this.lastMotionZ = mc.thePlayer.motionZ;
            this.lastMotionY = mc.thePlayer.motionY;
        } else {
            this.lastSlot = -1;
        }

        this.blockCount = -1;
        this.rotationTick = 0;      // instant
        this.placeCooldown = 0;     // instant
        this.yaw = -180.0F;
        this.pitch = 0.0F;
        this.prevYaw = -180.0F;
        this.prevPitch = 0.0F;
        this.canRotate = false;
        this.towerTick = 0;
        this.towerDelay = 0;
        this.towering = false;
        this.tellyTicksAir = 0;
        this.tellyJumped = false;
        this.lastServerYaw = -180.0F;
        this.lastServerPitch = 0.0F;
        this.enableTicks = 0;
        this.hasSentRotationThisTick = false;
        this.hasPlacedThisTick = false;
        this.kbDetected = false;
        this.kbTicks = 0;
        this.lastRotationDeltaYaw = 0.0F;
        this.sameRotationDeltaCount = 0;
        this.cachedBlockCount = 0;
        this.stage = 0;
        this.startY = 256;
        this.shouldKeepY = false;
        this.targetFacing = null;

        if (mc.thePlayer != null) updateBlockInventory();

        if (this.thirdPersonView.getValue() && mc.gameSettings != null) {
            this.originalPerspective = mc.gameSettings.thirdPersonView;
            mc.gameSettings.thirdPersonView = 1;
        }
    }

    @Override
    public void onDisabled() {
        this.lastDisableTime = System.currentTimeMillis();

        // Restore client slot - no C09, vanilla handles it next tick
        if (mc.thePlayer != null && this.lastSlot != -1) {
            mc.thePlayer.inventory.currentItem = this.lastSlot;
        }

        this.canRotate = false;
        this.hasSentRotationThisTick = false;
        this.hasPlacedThisTick = false;
        this.targetFacing = null;
        this.towering = false;
        this.towerTick = 0;
        this.towerDelay = 0;
        this.kbDetected = false;
        this.kbTicks = 0;

        if (this.thirdPersonView.getValue() && mc.gameSettings != null) {
            mc.gameSettings.thirdPersonView = this.originalPerspective;
        }
    }

    public static class BlockData {
        private final BlockPos blockPos;
        private final EnumFacing facing;

        public BlockData(BlockPos blockPos, EnumFacing enumFacing) {
            this.blockPos = blockPos;
            this.facing = enumFacing;
        }

        public BlockPos blockPos() { return this.blockPos; }
        public EnumFacing facing() { return this.facing; }
    }
}