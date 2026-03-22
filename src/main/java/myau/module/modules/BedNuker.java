package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;

import com.google.common.base.CaseFormat;
import myau.Myau;
import myau.enums.ChatColors;
import myau.enums.DelayModules;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.*;
import myau.management.RotationState;
import myau.mixin.IAccessorPlayerControllerMP;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import myau.util.*;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.BlockBed.EnumPartType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.ItemPickaxe;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C07PacketPlayerDigging.Action;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.potion.Potion;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ModuleInfo(category = ModuleCategory.MISC)
public class BedNuker extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    private static final int MIN_BREAK_DELAY_MS = 305;

    // ── Progress bar rendering constants ──────────────────────────────────────
    // Minecraft-style pixel art progress bar
    private static final int BAR_WIDTH = 182;       // Same as XP bar
    private static final int BAR_HEIGHT = 5;        // Same as XP bar
    private static final int BAR_BORDER = 1;
    private static final int BAR_Y_OFFSET = 32;     // Above hotbar

    // Smooth animation
    private float displayProgress = 0.0F;           // Interpolated for smooth rendering
    private float lastProgress = 0.0F;
    private long lastRenderTime = 0L;

    // Colors - Minecraft style
    private static final int COLOR_BG_OUTER     = 0xFF1A1A1A; // Dark border
    private static final int COLOR_BG_INNER     = 0xFF2D2D2D; // Inner background
    private static final int COLOR_BG_SHADOW    = 0xFF0D0D0D; // Bottom shadow
    private static final int COLOR_BG_HIGHLIGHT = 0xFF3A3A3A; // Top highlight

    private final ArrayList<BlockPos> bedWhitelist = new ArrayList<>();

    // ── Break state ───────────────────────────────────────────────────────────
    private BlockPos targetBed        = null;
    private EnumFacing targetFace     = null;
    private int      breakStage       = 0;
    private int      tickCounter      = 0;
    private float    breakProgress    = 0.0F;
    private boolean  isBed            = false;
    private int      savedSlot        = -1;
    private boolean  readyToBreak     = false;
    private boolean  breaking         = false;
    private boolean  waitingForStart  = false;
    private int      minBreakTicks    = 1;

    // ── BreakAura[A-2] fix ────────────────────────────────────────────────────
    private BlockPos serverBreakBlock  = null;
    private EnumFacing serverBreakFace = null;
    private boolean  serverBreakActive = false;

    // ── FastBreak fix ─────────────────────────────────────────────────────────
    private long lastStopTime = 0L;

    // ── KB / teleport edge cases ──────────────────────────────────────────────
    private boolean kbInterrupted      = false;
    private int     kbCooldownTicks    = 0;
    private boolean teleportInterrupted = false;
    private int     teleportCooldown   = 0;
    private double  breakStartX        = 0.0;
    private double  breakStartY        = 0.0;
    private double  breakStartZ        = 0.0;

    // ── Range tracking ────────────────────────────────────────────────────────
    private boolean wasOutOfRange      = false;
    private int     outOfRangeTicks    = 0;

    public final ModeProperty    mode           = new ModeProperty("mode", 0, new String[]{"LEGIT", "SWAP"});
    public final FloatProperty   range          = new FloatProperty("range", 4.5F, 3.0F, 6.0F);
    public final PercentProperty speed          = new PercentProperty("speed", 0);
    public final BooleanProperty groundSpeed    = new BooleanProperty("ground-spoof", false);
    public final ModeProperty    ignoreVelocity = new ModeProperty("ignore-velocity", 0, new String[]{"NONE", "CANCEL", "DELAY"});
    public final BooleanProperty surroundings   = new BooleanProperty("surroundings", true);
    public final BooleanProperty toolCheck      = new BooleanProperty("tool-check", true);
    public final BooleanProperty whiteList      = new BooleanProperty("whitelist", true);
    public final BooleanProperty swing          = new BooleanProperty("swing", true);
    public final ModeProperty    moveFix        = new ModeProperty("move-fix", 1, new String[]{"NONE", "SILENT", "STRICT"});
    public final ModeProperty    showTarget     = new ModeProperty("show-target", 1, new String[]{"NONE", "DEFAULT", "HUD"});
    public final ModeProperty    showProgress   = new ModeProperty("show-progress", 1, new String[]{"NONE", "BAR", "HUD"});

    public BedNuker() {
        super("BedNuker", false);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // STATE MANAGEMENT
    // ══════════════════════════════════════════════════════════════════════════

    private void abortServerBreak() {
        if (serverBreakActive && serverBreakBlock != null && serverBreakFace != null) {
            PacketUtil.sendPacket(new C07PacketPlayerDigging(
                    Action.ABORT_DESTROY_BLOCK, serverBreakBlock, serverBreakFace));
            serverBreakActive = false;
            serverBreakBlock = null;
            serverBreakFace = null;
        }
    }

    private void resetBreakState() {
        if (this.targetBed != null) {
            mc.theWorld.sendBlockBreakProgress(mc.thePlayer.getEntityId(), this.targetBed, -1);
        }
        this.targetBed       = null;
        this.targetFace      = null;
        this.breakStage      = 0;
        this.tickCounter     = 0;
        this.breakProgress   = 0.0F;
        this.isBed           = false;
        this.readyToBreak    = false;
        this.breaking        = false;
        this.minBreakTicks   = 1;
        this.wasOutOfRange   = false;
        this.outOfRangeTicks = 0;
    }

    private void fullReset() {
        abortServerBreak();
        resetBreakState();
    }

    private void interruptBreak(int cooldownTicks) {
        restoreSlot();
        fullReset();
        this.kbCooldownTicks = Math.max(this.kbCooldownTicks, cooldownTicks);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DISTANCE / VALIDATION
    // ══════════════════════════════════════════════════════════════════════════

    private boolean isWithinRange(BlockPos pos) {
        if (pos == null) return false;
        return PlayerUtil.isBlockWithinReach(pos,
                mc.thePlayer.posX,
                mc.thePlayer.posY + mc.thePlayer.getEyeHeight(),
                mc.thePlayer.posZ,
                this.range.getValue().doubleValue());
    }

    private boolean isWithinGrimReach(BlockPos pos) {
        if (pos == null) return false;
        double dx = mc.thePlayer.posX - (pos.getX() + 0.5);
        double dy = (mc.thePlayer.posY + mc.thePlayer.getEyeHeight()) - (pos.getY() + 0.5);
        double dz = mc.thePlayer.posZ - (pos.getZ() + 0.5);
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= 6.0;
    }

    private boolean hasMovedTooFar() {
        if (targetBed == null) return false;
        double dx = mc.thePlayer.posX - breakStartX;
        double dy = mc.thePlayer.posY - breakStartY;
        double dz = mc.thePlayer.posZ - breakStartZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) > 2.0 && !isWithinGrimReach(targetBed);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private float calcProgress() {
        if (this.targetBed == null) return 0.0F;
        float progress = this.breakProgress;
        if (this.groundSpeed.getValue()) {
            int slot = ItemUtil.findInventorySlot(
                    mc.thePlayer.inventory.currentItem,
                    mc.theWorld.getBlockState(this.targetBed).getBlock());
            progress = (float) this.tickCounter
                    * this.getBreakDelta(mc.theWorld.getBlockState(this.targetBed),
                    this.targetBed, slot, true);
        }
        return Math.min(1.0F, progress / (1.0F - 0.3F * ((float) this.speed.getValue().intValue() / 100.0F)));
    }

    private void restoreSlot() {
        if (this.savedSlot != -1) {
            mc.thePlayer.inventory.currentItem = this.savedSlot;
            this.syncHeldItem();
            this.savedSlot = -1;
        }
    }

    private void syncHeldItem() {
        int currentPlayerItem = ((IAccessorPlayerControllerMP) mc.playerController).getCurrentPlayerItem();
        if (mc.thePlayer.inventory.currentItem != currentPlayerItem) {
            mc.thePlayer.stopUsingItem();
        }
        ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
    }

    private boolean hasProperTool(Block block) {
        Material material = block.getMaterial();
        if (material != Material.iron && material != Material.anvil && material != Material.rock) return true;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (stack != null && stack.getItem() instanceof ItemPickaxe) return true;
        }
        return false;
    }

    private EnumFacing getHitFacing(BlockPos blockPos) {
        double x = blockPos.getX() + 0.5 - mc.thePlayer.posX;
        double y = blockPos.getY() + 0.25 - mc.thePlayer.posY - mc.thePlayer.getEyeHeight();
        double z = blockPos.getZ() + 0.5 - mc.thePlayer.posZ;
        float[] rotations = RotationUtil.getRotationsTo(x, y, z,
                mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch);
        MovingObjectPosition mop = RotationUtil.rayTrace(rotations[0], rotations[1], 8.0, 1.0F);
        return mop == null ? EnumFacing.UP : mop.sideHit;
    }

    private float getDigSpeed(IBlockState iBlockState, int slot, boolean onGround) {
        ItemStack item = mc.thePlayer.inventory.getStackInSlot(slot);
        float digSpeed = item == null ? 1.0F : item.getItem().getDigSpeed(item, iBlockState);
        if (digSpeed > 1.0F) {
            int eff = EnchantmentHelper.getEnchantmentLevel(Enchantment.efficiency.effectId, item);
            if (eff > 0) digSpeed += (float) (eff * eff + 1);
        }
        if (mc.thePlayer.isPotionActive(Potion.digSpeed)) {
            digSpeed *= 1.0F + (float) (mc.thePlayer.getActivePotionEffect(Potion.digSpeed).getAmplifier() + 1) * 0.2F;
        }
        if (mc.thePlayer.isPotionActive(Potion.digSlowdown)) {
            switch (mc.thePlayer.getActivePotionEffect(Potion.digSlowdown).getAmplifier()) {
                case 0:  digSpeed *= 0.3F;    break;
                case 1:  digSpeed *= 0.09F;   break;
                case 2:  digSpeed *= 0.0027F; break;
                default: digSpeed *= 8.1E-4F;
            }
        }
        if (mc.thePlayer.isInsideOfMaterial(Material.water)
                && !EnchantmentHelper.getAquaAffinityModifier(mc.thePlayer)) {
            digSpeed /= 5.0F;
        }
        if (!onGround) digSpeed /= 5.0F;
        return digSpeed;
    }

    boolean canHarvest(Block block, int slot) {
        if (block.getMaterial().isToolNotRequired()) return true;
        ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
        return stack != null && stack.canHarvestBlock(block);
    }

    private float getBreakDelta(IBlockState iBlockState, BlockPos blockPos, int slot, boolean onGround) {
        Block block = iBlockState.getBlock();
        float hardness = block.getBlockHardness(mc.theWorld, blockPos);
        float boost = this.canHarvest(block, slot) ? 30.0F : 100.0F;
        return hardness < 0.0F ? 0.0F : this.getDigSpeed(iBlockState, slot, onGround) / hardness / boost;
    }

    private int computeMinBreakTicks(float blockDamage) {
        if (blockDamage <= 0.0F) return 200;
        return Math.max(1, (int) Math.ceil(1.0 / blockDamage));
    }

    private float calcBlockStrength(BlockPos blockPos) {
        IBlockState blockState = mc.theWorld.getBlockState(blockPos);
        int slot = ItemUtil.findInventorySlot(mc.thePlayer.inventory.currentItem, blockState.getBlock());
        return this.getBreakDelta(blockState, blockPos, slot, mc.thePlayer.onGround);
    }

    private BlockPos validateBedPlacement(BlockPos bedPosition) {
        IBlockState blockState = mc.theWorld.getBlockState(bedPosition);
        if (!(blockState.getBlock() instanceof BlockBed)) return null;

        ArrayList<BlockPos> pos = new ArrayList<>();
        EnumPartType partType = blockState.getValue(BlockBed.PART);
        EnumFacing facing = blockState.getValue(BlockBed.FACING);

        for (BlockPos blockPos : Arrays.asList(
                bedPosition,
                bedPosition.offset(partType == EnumPartType.HEAD ? facing.getOpposite() : facing))) {
            for (EnumFacing enumFacing : Arrays.asList(
                    EnumFacing.UP, EnumFacing.NORTH,
                    EnumFacing.EAST, EnumFacing.SOUTH, EnumFacing.WEST)) {
                Block block = mc.theWorld.getBlockState(blockPos.offset(enumFacing)).getBlock();
                if (BlockUtil.isReplaceable(block)) return null;
                if (!(block instanceof BlockBed)) pos.add(blockPos.offset(enumFacing));
            }
        }

        if (!pos.isEmpty()) {
            pos.sort((a, b) -> {
                int o = Float.compare(this.calcBlockStrength(b), this.calcBlockStrength(a));
                return o != 0 ? o : Double.compare(
                        a.distanceSqToCenter(mc.thePlayer.posX,
                                mc.thePlayer.posY + mc.thePlayer.getEyeHeight(),
                                mc.thePlayer.posZ),
                        b.distanceSqToCenter(mc.thePlayer.posX,
                                mc.thePlayer.posY + mc.thePlayer.getEyeHeight(),
                                mc.thePlayer.posZ));
            });
            return pos.get(0);
        }
        return null;
    }

    private BlockPos findNearestBed() {
        return this.findTargetBed(
                mc.thePlayer.posX,
                mc.thePlayer.posY + mc.thePlayer.getEyeHeight(),
                mc.thePlayer.posZ);
    }

    private BlockPos findTargetBed(double x, double y, double z) {
        ArrayList<BlockPos> targets = new ArrayList<>();
        int sX = MathHelper.floor_double(x);
        int sY = MathHelper.floor_double(y);
        int sZ = MathHelper.floor_double(z);

        for (int i = sX - 6; i <= sX + 6; i++) {
            for (int j = sY - 6; j <= sY + 6; j++) {
                for (int k = sZ - 6; k <= sZ + 6; k++) {
                    BlockPos newPos = new BlockPos(i, j, k);
                    if (this.whiteList.getValue() && this.bedWhitelist.contains(newPos)) continue;
                    Block block = mc.theWorld.getBlockState(newPos).getBlock();
                    if (block instanceof BlockBed
                            && PlayerUtil.isBlockWithinReach(newPos, x, y, z,
                            this.range.getValue().doubleValue())) {
                        targets.add(newPos);
                    }
                }
            }
        }

        if (targets.isEmpty()) return null;

        targets.sort(Comparator.comparingDouble(blockPos ->
                blockPos.distanceSqToCenter(mc.thePlayer.posX,
                        mc.thePlayer.posY + mc.thePlayer.getEyeHeight(),
                        mc.thePlayer.posZ)));

        for (BlockPos blockPos : targets) {
            if (this.surroundings.getValue()) {
                BlockPos pos = this.validateBedPlacement(blockPos);
                if (pos != null) {
                    Block block = mc.theWorld.getBlockState(pos).getBlock();
                    if (this.toolCheck.getValue() && !this.hasProperTool(block)) continue;
                    return pos;
                }
            }
            return blockPos;
        }
        return null;
    }

    private void doSwing() {
        if (this.swing.getValue()) {
            mc.thePlayer.swingItem();
        } else {
            PacketUtil.sendPacket(new C0APacketAnimation());
        }
    }

    private boolean canStartNewBreak() {
        return (System.currentTimeMillis() - lastStopTime) >= MIN_BREAK_DELAY_MS;
    }

    public boolean isReady() {
        return this.targetBed != null && this.readyToBreak;
    }

    public boolean isBreaking() {
        return this.targetBed != null && this.breaking;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PROGRESS BAR RENDERING
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Get progress bar color based on completion.
     * Smooth gradient: red → orange → yellow → green
     */
    private int getBarColor(float progress) {
        if (progress < 0.25F) {
            // Red to orange
            float t = progress / 0.25F;
            int r = 220;
            int g = (int) (50 + t * 120);
            int b = 40;
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        } else if (progress < 0.5F) {
            // Orange to yellow
            float t = (progress - 0.25F) / 0.25F;
            int r = 220 + (int) (t * 35);
            int g = 170 + (int) (t * 60);
            int b = 40;
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        } else if (progress < 0.75F) {
            // Yellow to lime
            float t = (progress - 0.5F) / 0.25F;
            int r = 255 - (int) (t * 140);
            int g = 230 + (int) (t * 15);
            int b = 40 + (int) (t * 20);
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        } else {
            // Lime to green
            float t = (progress - 0.75F) / 0.25F;
            int r = 115 - (int) (t * 55);
            int g = 245 - (int) (t * 10);
            int b = 60 + (int) (t * 20);
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }

    /**
     * Get a brighter version of the bar color for the highlight stripe.
     */
    private int getBarHighlight(float progress) {
        int base = getBarColor(progress);
        int r = Math.min(255, ((base >> 16) & 0xFF) + 50);
        int g = Math.min(255, ((base >> 8) & 0xFF) + 50);
        int b = Math.min(255, (base & 0xFF) + 30);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * Get a darker version of the bar color for the bottom shadow.
     */
    private int getBarShadow(float progress) {
        int base = getBarColor(progress);
        int r = Math.max(0, ((base >> 16) & 0xFF) - 60);
        int g = Math.max(0, ((base >> 8) & 0xFF) - 60);
        int b = Math.max(0, (base & 0xFF) - 40);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * Smooth interpolation for display progress.
     * Makes the bar animate smoothly instead of jumping.
     */
    private float smoothProgress(float target, float current, float deltaTime) {
        float speed = 8.0F; // Animation speed
        if (target > current) {
            return Math.min(target, current + speed * deltaTime);
        } else if (target < current) {
            // Snap down instantly when progress resets
            return target;
        }
        return current;
    }

    /**
     * Draw a filled rectangle with GL.
     */
    private void drawRect(float x, float y, float w, float h, int color) {
        float a = (float) (color >> 24 & 0xFF) / 255.0F;
        float r = (float) (color >> 16 & 0xFF) / 255.0F;
        float g = (float) (color >> 8 & 0xFF) / 255.0F;
        float b = (float) (color & 0xFF) / 255.0F;

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();

        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        GlStateManager.color(r, g, b, a);

        wr.begin(7, DefaultVertexFormats.POSITION);
        wr.pos(x, y + h, 0.0).endVertex();
        wr.pos(x + w, y + h, 0.0).endVertex();
        wr.pos(x + w, y, 0.0).endVertex();
        wr.pos(x, y, 0.0).endVertex();
        tessellator.draw();

        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
    }

    /**
     * Draw a rounded-feel rectangle with 1px beveled corners.
     * Gives a Minecraft-style pixel art rounded edge.
     */
    private void drawBeveledRect(float x, float y, float w, float h,
                                  int colorMain, int colorHighlight, int colorShadow) {
        // Main fill
        drawRect(x + 1, y + 1, w - 2, h - 2, colorMain);

        // Top edge (highlight)
        drawRect(x + 1, y, w - 2, 1, colorHighlight);

        // Bottom edge (shadow)
        drawRect(x + 1, y + h - 1, w - 2, 1, colorShadow);

        // Left edge
        drawRect(x, y + 1, 1, h - 2, colorHighlight);

        // Right edge
        drawRect(x + w - 1, y + 1, 1, h - 2, colorShadow);
    }

    /**
     * Render the Minecraft-style progress bar.
     *
     * Layout (centered above hotbar):
     *
     *   ┌──────────────────────────────────────┐
     *   │  ██████████████████░░░░░░░░░░  85%   │
     *   └──────────────────────────────────────┘
     *
     * Features:
     *   - Beveled border (Minecraft button style)
     *   - Smooth gradient fill (red → green)
     *   - 1px highlight on top of fill
     *   - 1px shadow on bottom of fill
     *   - Percentage text right-aligned
     *   - Smooth animation (8 units/sec interpolation)
     *   - Block name label above bar
     */
    private void renderProgressBar(ScaledResolution sr, float progress) {
        int screenW = sr.getScaledWidth();
        int screenH = sr.getScaledHeight();

        // ── Smooth animation ──────────────────────────────────────────────
        long now = System.currentTimeMillis();
        float dt = (now - lastRenderTime) / 1000.0F;
        if (dt > 0.1F) dt = 0.1F; // Cap to prevent jumps on lag
        lastRenderTime = now;
        displayProgress = smoothProgress(progress, displayProgress, dt);

        // ── Layout ────────────────────────────────────────────────────────
        int totalBarW = BAR_WIDTH;
        int percentTextW = mc.fontRendererObj.getStringWidth("100%") + 4;
        int totalW = totalBarW + percentTextW + 4; // bar + gap + text

        float barX = (screenW - totalW) / 2.0F;
        float barY = screenH - BAR_Y_OFFSET - BAR_HEIGHT - 12; // Above hotbar with padding

        // Don't overlap with armor/health bars
        if (barY < screenH / 2.0F) barY = screenH / 2.0F + 20;

        int barInnerW = totalBarW - 4; // Inside border
        int barInnerH = BAR_HEIGHT;
        int filledW = (int) (barInnerW * displayProgress);

        // ── Background (beveled container) ────────────────────────────────
        // Outer border
        drawBeveledRect(barX, barY, totalBarW, BAR_HEIGHT + 4,
                COLOR_BG_INNER, COLOR_BG_HIGHLIGHT, COLOR_BG_SHADOW);

        // Inner inset shadow (1px inside the border)
        drawRect(barX + 2, barY + 2, barInnerW, barInnerH, COLOR_BG_SHADOW);

        // ── Fill bar ──────────────────────────────────────────────────────
        if (filledW > 0) {
            int barColor = getBarColor(displayProgress);
            int barHighlight = getBarHighlight(displayProgress);
            int barShadow = getBarShadow(displayProgress);

            // Main fill
            drawRect(barX + 2, barY + 2, filledW, barInnerH, barColor);

            // Top highlight stripe (1px)
            drawRect(barX + 2, barY + 2, filledW, 1, barHighlight);

            // Bottom shadow stripe (1px)
            if (barInnerH > 2) {
                drawRect(barX + 2, barY + 2 + barInnerH - 1, filledW, 1, barShadow);
            }

            // Shimmer effect at the fill edge (2px bright line)
            if (filledW > 2 && displayProgress < 1.0F) {
                int shimmerX = (int) barX + 2 + filledW - 1;
                drawRect(shimmerX, barY + 2, 1, barInnerH, barHighlight);
            }
        }

        // ── Progress segments (Minecraft XP bar style notches) ────────────
        // Draw subtle notch marks every 10%
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        for (int i = 1; i < 10; i++) {
            float notchX = barX + 2 + (barInnerW * i / 10.0F);
            int notchColor = (i * 10 <= (int) (displayProgress * 100))
                    ? 0x30000000  // Dark notch on filled area
                    : 0x15FFFFFF; // Light notch on empty area
            drawRect(notchX, barY + 2, 1, barInnerH, notchColor);
        }
        GlStateManager.disableBlend();

        // ── Percentage text ───────────────────────────────────────────────
        int percent = (int) (displayProgress * 100.0F);
        String percentText = percent + "%";

        // Color matches bar fill
        int textColor;
        if (displayProgress >= 1.0F) {
            textColor = 0xFF60FF60; // Bright green when complete
        } else {
            textColor = getBarColor(displayProgress);
        }

        float textX = barX + totalBarW + 4;
        float textY = barY + (BAR_HEIGHT + 4) / 2.0F - mc.fontRendererObj.FONT_HEIGHT / 2.0F + 1;

        // Shadow
        mc.fontRendererObj.drawString(percentText,
                textX + 1, textY + 1,
                0x3F000000, false);
        // Main text
        mc.fontRendererObj.drawString(percentText,
                textX, textY,
                textColor, false);

        // ── Block label above bar ─────────────────────────────────────────
        if (this.targetBed != null) {
            IBlockState state = mc.theWorld.getBlockState(this.targetBed);
            String blockName = this.isBed ? "Bed" : state.getBlock().getLocalizedName();

            // Status indicator
            String status;
            int statusColor;
            if (kbCooldownTicks > 0) {
                status = " §c[KB]";
                statusColor = 0xFFFF5555;
            } else if (teleportCooldown > 0) {
                status = " §c[TP]";
                statusColor = 0xFFFF5555;
            } else if (wasOutOfRange) {
                status = " §e[RANGE]";
                statusColor = 0xFFFFFF55;
            } else if (displayProgress >= 1.0F) {
                status = " §a[DONE]";
                statusColor = 0xFF55FF55;
            } else {
                status = "";
                statusColor = 0;
            }

            String label = "§f" + blockName + status;

            float labelW = mc.fontRendererObj.getStringWidth(label);
            float labelX = barX + (totalBarW - labelW) / 2.0F;
            float labelY = barY - mc.fontRendererObj.FONT_HEIGHT - 2;

            // Label shadow
            mc.fontRendererObj.drawString(label,
                    labelX + 0.5F, labelY + 0.5F,
                    0x3F000000, false);
            // Label text
            mc.fontRendererObj.drawString(label,
                    labelX, labelY,
                    0xFFFFFFFF, true);
        }

        // ── Completion flash effect ───────────────────────────────────────
        if (displayProgress >= 1.0F) {
            // Bright flash overlay on the bar
            long pulse = System.currentTimeMillis() % 1000;
            float pulseAlpha = (float) Math.sin(pulse / 1000.0 * Math.PI * 2) * 0.15F + 0.05F;
            int flashColor = ((int) (pulseAlpha * 255) << 24) | 0x60FF60;

            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
            drawRect(barX + 2, barY + 2, barInnerW, barInnerH, flashColor);
            GlStateManager.disableBlend();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MAIN TICK
    // ══════════════════════════════════════════════════════════════════════════

    @EventTarget(Priority.HIGH)
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) return;

        if (kbCooldownTicks > 0) { kbCooldownTicks--; return; }
        if (teleportCooldown > 0) { teleportCooldown--; return; }

        AntiFireball antiFireball = (AntiFireball) Myau.moduleManager.modules.get(AntiFireball.class);
        if (antiFireball != null && antiFireball.isDeflecting()) return;

        // ── Validate current target ───────────────────────────────────────
        if (this.targetBed != null) {
            if (mc.theWorld.isAirBlock(this.targetBed)) {
                this.restoreSlot();
                this.fullReset();
            } else if (!isWithinRange(this.targetBed)) {
                if (!wasOutOfRange) {
                    wasOutOfRange = true;
                    outOfRangeTicks = 0;
                }
                outOfRangeTicks++;
                if (outOfRangeTicks > 3) {
                    this.restoreSlot();
                    this.fullReset();
                }
                return;
            } else if (wasOutOfRange) {
                wasOutOfRange = false;
                outOfRangeTicks = 0;
                this.restoreSlot();
                this.fullReset();
            } else if (hasMovedTooFar()) {
                this.restoreSlot();
                this.fullReset();
            } else if (!this.isBed) {
                BlockPos nearestBed = this.findNearestBed();
                if (nearestBed != null
                        && mc.theWorld.getBlockState(nearestBed).getBlock() instanceof BlockBed) {
                    this.restoreSlot();
                    this.fullReset();
                }
            }
        }

        // ── Break sequence ────────────────────────────────────────────────
        if (this.targetBed != null) {
            IBlockState blockState = mc.theWorld.getBlockState(this.targetBed);
            if (blockState.getBlock().getMaterial() == Material.air) {
                this.restoreSlot();
                this.fullReset();
                return;
            }

            int slot = ItemUtil.findInventorySlot(mc.thePlayer.inventory.currentItem, blockState.getBlock());

            if (this.mode.getValue() == 0 && this.savedSlot == -1) {
                this.savedSlot = mc.thePlayer.inventory.currentItem;
                mc.thePlayer.inventory.currentItem = slot;
                this.syncHeldItem();
            }

            switch (this.breakStage) {
                case 0:
                    if (mc.thePlayer.isUsingItem()) break;
                    abortServerBreak();
                    this.targetFace = this.getHitFacing(this.targetBed);
                    float blockDamage = this.getBreakDelta(blockState, this.targetBed, slot, true);
                    this.minBreakTicks = this.computeMinBreakTicks(blockDamage);
                    this.breakStartX = mc.thePlayer.posX;
                    this.breakStartY = mc.thePlayer.posY;
                    this.breakStartZ = mc.thePlayer.posZ;
                    this.doSwing();
                    PacketUtil.sendPacket(new C07PacketPlayerDigging(
                            Action.START_DESTROY_BLOCK, this.targetBed, this.targetFace));
                    this.doSwing();
                    mc.effectRenderer.addBlockHitEffects(this.targetBed, this.targetFace);
                    this.serverBreakBlock = this.targetBed;
                    this.serverBreakFace = this.targetFace;
                    this.serverBreakActive = true;
                    this.breakStage = 1;
                    break;

                case 1:
                    if (this.mode.getValue() == 1) this.readyToBreak = false;
                    this.breaking = true;
                    this.tickCounter++;
                    if (!isWithinRange(this.targetBed)) {
                        wasOutOfRange = true;
                        outOfRangeTicks = 1;
                        return;
                    }
                    float delta = this.getBreakDelta(blockState, this.targetBed, slot, mc.thePlayer.onGround);
                    this.breakProgress += delta;
                    mc.effectRenderer.addBlockHitEffects(this.targetBed, this.targetFace);
                    float speedMult = 1.0F - 0.3F * ((float) this.speed.getValue().intValue() / 100.0F);
                    boolean progressReached = this.breakProgress >= speedMult;
                    boolean ticksReached = this.tickCounter >= this.minBreakTicks;
                    if (progressReached && ticksReached) {
                        if (this.mode.getValue() == 1) {
                            this.readyToBreak = true;
                            this.savedSlot = mc.thePlayer.inventory.currentItem;
                            mc.thePlayer.inventory.currentItem = slot;
                            this.syncHeldItem();
                            if (mc.thePlayer.isUsingItem()) {
                                this.savedSlot = mc.thePlayer.inventory.currentItem;
                                mc.thePlayer.inventory.currentItem = (mc.thePlayer.inventory.currentItem + 1) % 9;
                                this.syncHeldItem();
                            }
                        }
                        this.breaking = false;
                        if (!isWithinGrimReach(this.targetBed)) {
                            this.restoreSlot();
                            this.fullReset();
                            return;
                        }
                        PacketUtil.sendPacket(new C07PacketPlayerDigging(
                                Action.STOP_DESTROY_BLOCK, this.targetBed, this.targetFace));
                        this.doSwing();
                        this.lastStopTime = System.currentTimeMillis();
                        this.serverBreakActive = false;
                        this.serverBreakBlock = null;
                        this.serverBreakFace = null;
                        IBlockState bs = mc.theWorld.getBlockState(this.targetBed);
                        Block block = bs.getBlock();
                        if (block.getMaterial() != Material.air) {
                            mc.theWorld.playAuxSFX(2001, this.targetBed, Block.getStateId(bs));
                            mc.theWorld.setBlockToAir(this.targetBed);
                        }
                        this.breakStage = 2;
                    }
                    break;

                case 2:
                    this.restoreSlot();
                    this.resetBreakState();
                    break;
            }

            if (this.targetBed != null) return;
        }

        // ── Find new target ───────────────────────────────────────────────
        if (mc.thePlayer.capabilities.allowEdit && canStartNewBreak()) {
            this.targetBed     = this.findNearestBed();
            this.breakStage    = 0;
            this.tickCounter   = 0;
            this.breakProgress = 0.0F;
            this.minBreakTicks = 1;
            this.isBed         = this.targetBed != null
                    && mc.theWorld.getBlockState(this.targetBed).getBlock() instanceof BlockBed;
            this.restoreSlot();
            if (this.targetBed != null) this.readyToBreak = true;
        }

        if (this.targetBed == null) {
            Myau.delayManager.setDelayState(false, DelayModules.BED_NUKER);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ROTATION
    // ══════════════════════════════════════════════════════════════════════════

    @EventTarget(Priority.LOWEST)
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) return;
        if (!this.isReady() || this.targetBed == null) return;
        if (kbCooldownTicks > 0 || teleportCooldown > 0) return;

        double x = this.targetBed.getX() + 0.5 - mc.thePlayer.posX;
        double y = this.targetBed.getY() + 0.5 - mc.thePlayer.posY - mc.thePlayer.getEyeHeight();
        double z = this.targetBed.getZ() + 0.5 - mc.thePlayer.posZ;
        float[] rotations = RotationUtil.getRotationsTo(x, y, z, event.getYaw(), event.getPitch());
        event.setRotation(rotations[0], rotations[1], 5);
        event.setPervRotation(
                this.moveFix.getValue() != 0 ? rotations[0] : mc.thePlayer.rotationYaw, 5);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // RENDER
    // ══════════════════════════════════════════════════════════════════════════

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled()) return;
        if (this.targetBed == null) {
            // Decay display progress when no target
            if (displayProgress > 0.0F) {
                long now = System.currentTimeMillis();
                float dt = (now - lastRenderTime) / 1000.0F;
                if (dt > 0.1F) dt = 0.1F;
                lastRenderTime = now;
                displayProgress = Math.max(0.0F, displayProgress - 12.0F * dt);
            }
            return;
        }
        if (this.isBed && this.surroundings.getValue()) return;
        if (this.showProgress.getValue() == 0) return;

        ScaledResolution sr = new ScaledResolution(mc);
        float progress = this.calcProgress();

        if (this.showProgress.getValue() == 1) {
            // ── BAR mode: Minecraft-style progress bar ────────────────────
            GlStateManager.pushMatrix();
            GlStateManager.disableDepth();

            renderProgressBar(sr, progress);

            GlStateManager.enableDepth();
            GlStateManager.popMatrix();

        } else if (this.showProgress.getValue() == 2) {
            // ── HUD mode: HUD-colored text (original behavior) ───────────
            HUD hud = (HUD) Myau.moduleManager.modules.get(HUD.class);
            float scale = hud.scale.getValue();
            String text = String.format("%d%%", (int) (progress * 100.0F));

            Color color = ((HUD) Myau.moduleManager.modules.get(HUD.class)).getColor(System.currentTimeMillis());

            GlStateManager.pushMatrix();
            GlStateManager.scale(scale, scale, 0.0F);
            GlStateManager.disableDepth();
            GlStateManager.enableBlend();
            GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

            int width = mc.fontRendererObj.getStringWidth(text);
            mc.fontRendererObj.drawString(
                    text,
                    (float) sr.getScaledWidth() / 2.0F / scale - (float) width / 2.0F,
                    (float) sr.getScaledHeight() / 5.0F * 2.0F / scale,
                    color.getRGB() & 16777215 | -1090519040,
                    hud.shadow.getValue());

            GlStateManager.disableBlend();
            GlStateManager.enableDepth();
            GlStateManager.popMatrix();
        }
    }

    @EventTarget(Priority.LOW)
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || this.targetBed == null || mc.theWorld.isAirBlock(this.targetBed)) return;
        mc.theWorld.sendBlockBreakProgress(mc.thePlayer.getEntityId(), this.targetBed,
                (int) (this.calcProgress() * 10.0F) - 1);
        if (this.showTarget.getValue() == 0) return;

        BedESP bedESP = (BedESP) Myau.moduleManager.modules.get(BedESP.class);

        // Use progress-based color for target box too
        float progress = this.calcProgress();
        int progColor = getBarColor(progress);
        int r = (progColor >> 16) & 0xFF;
        int g = (progColor >> 8) & 0xFF;
        int b = progColor & 0xFF;

        if (this.showTarget.getValue() == 2) {
            Color hudColor = ((HUD) Myau.moduleManager.modules.get(HUD.class)).getColor(System.currentTimeMillis());
            r = hudColor.getRed();
            g = hudColor.getGreen();
            b = hudColor.getBlue();
        }

        RenderUtil.enableRenderState();
        double newHeight = this.isBed ? bedESP.getHeight() : 1.0;
        RenderUtil.drawBlockBox(this.targetBed, newHeight, r, g, b);
        RenderUtil.disableRenderState();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MISC EVENTS
    // ══════════════════════════════════════════════════════════════════════════

    @EventTarget
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (!this.isEnabled()) return;
        if (this.isBreaking()
                && !Myau.playerStateManager.attacking
                && !Myau.playerStateManager.digging
                && !Myau.playerStateManager.placing
                && !Myau.playerStateManager.swinging) {
            this.doSwing();
        }
    }

    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        if (!this.isEnabled()) return;
        if (this.moveFix.getValue() == 1
                && RotationState.isActived()
                && RotationState.getPriority() == 5.0F
                && MoveUtil.isForwardPressed()) {
            MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
        }
    }

    @EventTarget(Priority.HIGH)
    public void onKnockback(KnockbackEvent event) {
        if (!this.isEnabled() || event.isCancelled() || event.getY() <= 0.0) return;
        if (this.ignoreVelocity.getValue() == 1 && this.targetBed != null) {
            event.setCancelled(true);
            event.setX(mc.thePlayer.motionX);
            event.setY(mc.thePlayer.motionY);
            event.setZ(mc.thePlayer.motionZ);
            return;
        }
        if (this.targetBed != null && this.breaking) {
            kbInterrupted = true;
            interruptBreak(5);
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (event.isCancelled()) return;

        if (event.getPacket() instanceof S02PacketChat) {
            String text = ((S02PacketChat) event.getPacket()).getChatComponent().getFormattedText();
            if (text.contains("§e§lProtect your bed and destroy the enemy bed")
                    || text.contains("§e§lDestroy the enemy bed and then eliminate them")) {
                this.waitingForStart = true;
            }
        }

        if (event.getPacket() instanceof S08PacketPlayerPosLook) {
            if (this.waitingForStart) {
                this.waitingForStart = false;
                this.bedWhitelist.clear();
                this.scheduler.schedule(() -> {
                    if (mc.thePlayer == null || mc.theWorld == null) return;
                    int sX = MathHelper.floor_double(mc.thePlayer.posX);
                    int sY = MathHelper.floor_double(mc.thePlayer.posY + mc.thePlayer.getEyeHeight());
                    int sZ = MathHelper.floor_double(mc.thePlayer.posZ);
                    for (int i = sX - 25; i <= sX + 25; i++) {
                        for (int j = sY - 25; j <= sY + 25; j++) {
                            for (int k = sZ - 25; k <= sZ + 25; k++) {
                                BlockPos blockPos = new BlockPos(i, j, k);
                                Block block = mc.theWorld.getBlockState(blockPos).getBlock();
                                if (block instanceof BlockBed) this.bedWhitelist.add(blockPos);
                            }
                        }
                    }
                }, 1L, TimeUnit.SECONDS);
            }
            if (this.isEnabled() && this.targetBed != null) {
                teleportInterrupted = true;
                interruptBreak(10);
            }
        }

        if (!this.isEnabled() || this.targetBed == null) return;
        if (this.ignoreVelocity.getValue() != 2) return;
        if (Myau.delayManager.getDelayModule() == DelayModules.BED_NUKER) return;

        if (event.getPacket() instanceof S12PacketEntityVelocity) {
            S12PacketEntityVelocity packet = (S12PacketEntityVelocity) event.getPacket();
            if (packet.getEntityID() == mc.thePlayer.getEntityId() && packet.getMotionY() > 0) {
                Myau.delayManager.delay(DelayModules.BED_NUKER);
                Myau.delayManager.delayedPacket.offer(packet);
                event.setCancelled(true);
                if (this.breaking) {
                    kbInterrupted = true;
                    interruptBreak(5);
                }
            }
        }

        if (event.getPacket() instanceof S27PacketExplosion) {
            S27PacketExplosion explosion = (S27PacketExplosion) event.getPacket();
            if (explosion.func_149149_c() != 0.0F
                    || explosion.func_149144_d() != 0.0F
                    || explosion.func_149147_e() != 0.0F) {
                Myau.delayManager.delay(DelayModules.BED_NUKER);
                Myau.delayManager.delayedPacket.offer(explosion);
                event.setCancelled(true);
                if (this.breaking) {
                    kbInterrupted = true;
                    interruptBreak(5);
                }
            }
        }
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.waitingForStart = false;
        this.fullReset();
        this.displayProgress = 0.0F;
    }

    @EventTarget
    public void onLeftClick(LeftClickMouseEvent event) {
        if (!this.isEnabled()) return;
        if (this.isReady() || (this.targetBed != null
                && mc.objectMouseOver != null
                && mc.objectMouseOver.typeOfHit == MovingObjectType.BLOCK)) {
            event.setCancelled(true);
        }
    }

    @EventTarget
    public void onRightClick(RightClickMouseEvent event) {
        if (this.isEnabled() && this.isReady()) event.setCancelled(true);
    }

    @EventTarget
    public void onHitBlock(HitBlockEvent event) {
        if (!this.isEnabled()) return;
        if (this.isReady() || (this.targetBed != null
                && mc.objectMouseOver != null
                && mc.objectMouseOver.typeOfHit == MovingObjectType.BLOCK)) {
            event.setCancelled(true);
        }
    }

    @EventTarget
    public void onSwap(SwapItemEvent event) {
        if (this.isEnabled() && this.savedSlot != -1) event.setCancelled(true);
    }

    @Override
    public void onEnabled() {
        this.displayProgress = 0.0F;
        this.lastRenderTime = System.currentTimeMillis();
    }

    @Override
    public void onDisabled() {
        this.restoreSlot();
        this.fullReset();
        this.savedSlot = -1;
        this.kbCooldownTicks = 0;
        this.teleportCooldown = 0;
        this.kbInterrupted = false;
        this.teleportInterrupted = false;
        this.displayProgress = 0.0F;
        Myau.delayManager.setDelayState(false, DelayModules.BED_NUKER);
    }

    @Override
    public String[] getSuffix() {
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL,
                this.mode.getModeString())};
    }
}