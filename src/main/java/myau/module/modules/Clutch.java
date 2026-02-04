package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.*;
import myau.management.RotationState;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.FloatProperty;
import myau.util.*;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.util.*;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class Clutch extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final double[] PRECISE_OFFSETS = new double[]{
            0.03125, 0.09375, 0.15625, 0.21875, 0.28125, 0.34375, 0.40625, 0.46875,
            0.53125, 0.59375, 0.65625, 0.71875, 0.78125, 0.84375, 0.90625, 0.96875
    };
    
    private int lastSlot = -1;
    private int blockCount = -1;
    private float originalYaw = 0.0F;
    private float originalPitch = 0.0F;
    private boolean hasStoredAngles = false;
    private long clutchCompleteTime = 0L;
    private boolean isClutching = false;
    private double clutchStartY = 0.0;
    private int staircaseJumps = 0;
    private long lastJumpTime = 0L;
    
    // Telly mode variables
    private boolean tellyActive = false;
    private int tellyStage = 0;
    private long lastTellyTime = 0L;
    private BlockPos tellyTargetPos = null;
    
    // Grim bypass variables
    private Map<BlockPos, Long> recentPlacements = new HashMap<>();
    private long lastPlaceTime = 0L;
    private int consecutivePlacements = 0;
    private double grimVoidCheckY = 0.0;
    private boolean grimSafeMode = false;
    
    // Block disappear prevention
    private BlockPos lastPlacedBlock = null;
    private long lastPlacedTime = 0L;
    private int placeConfirmTicks = 0;
    private boolean waitingForConfirm = false;
    
    // Activation Conditions
    public final BooleanProperty onVoid = new BooleanProperty("on-void", true);
    public final BooleanProperty onLethalFall = new BooleanProperty("on-lethal-fall", true);
    public final BooleanProperty onMoreThanXBlocks = new BooleanProperty("on-more-than-x-blocks", false);
    public final IntProperty blocks = new IntProperty("blocks", 5, 3, 20, () -> this.onMoreThanXBlocks.getValue());
    
    // Additional Settings
    public final BooleanProperty silentAim = new BooleanProperty("silent-aim", true);
    public final ModeProperty silentAimMovement = new ModeProperty("silent-aim-movement", 0, new String[]{"PROPER", "NONE", "SLOW"}, () -> this.silentAim.getValue());
    public final BooleanProperty showBlockCount = new BooleanProperty("show-block-count", true);
    public final BooleanProperty resetAngle = new BooleanProperty("reset-angle", true);
    public final BooleanProperty returnToSlot = new BooleanProperty("return-to-slot", true);
    public final BooleanProperty allowStaircaseUp = new BooleanProperty("allow-staircase-up", false);
    public final IntProperty clutchMoveDelay = new IntProperty("clutch-move-delay", 3, 0, 10);
    
    // Telly Mode
    public final BooleanProperty tellyMode = new BooleanProperty("telly-mode", false);
    public final FloatProperty tellyDistance = new FloatProperty("telly-distance", 0.2F, 0.1F, 0.4F, () -> this.tellyMode.getValue());
    public final IntProperty tellyDelay = new IntProperty("telly-delay", 2, 1, 5, () -> this.tellyMode.getValue());
    
    // Grim AntiVoid Bypass
    public final BooleanProperty grimMode = new BooleanProperty("grim-antivoid", false);
    public final FloatProperty grimSafetyMargin = new FloatProperty("grim-safety-margin", 0.5F, 0.1F, 2.0F, () -> this.grimMode.getValue());
    public final IntProperty grimPlaceDelay = new IntProperty("grim-place-delay", 50, 20, 200, () -> this.grimMode.getValue());
    public final BooleanProperty grimPreciseRotations = new BooleanProperty("grim-precise-rotations", true, () -> this.grimMode.getValue());
    
    // Block disappear/rubberband prevention
    public final BooleanProperty antiDisappear = new BooleanProperty("anti-disappear", true);
    public final IntProperty confirmDelay = new IntProperty("confirm-delay", 2, 1, 5, () -> this.antiDisappear.getValue());
    
    // Block Selection
    public final ModeProperty blockSelectionMode = new ModeProperty("block-selection", 0, new String[]{"WHITELIST", "BLACKLIST"});
    public final BooleanProperty swing = new BooleanProperty("swing", true);
    
    public Clutch() {
        super("Clutch", false);
    }
    
    @Override
    public void onEnabled() {
        this.isClutching = false;
        this.hasStoredAngles = false;
        this.clutchCompleteTime = 0L;
        this.staircaseJumps = 0;
        this.lastJumpTime = 0L;
        this.tellyActive = false;
        this.tellyStage = 0;
        this.recentPlacements.clear();
        this.waitingForConfirm = false;
        this.grimSafeMode = false;
    }
    
    @Override
    public void onDisabled() {
        if (this.returnToSlot.getValue() && this.lastSlot >= 0 && this.lastSlot < 9) {
            mc.thePlayer.inventory.currentItem = this.lastSlot;
            this.lastSlot = -1;
        }
        this.tellyActive = false;
        this.waitingForConfirm = false;
    }
    
    private boolean shouldActivate() {
        if (mc.thePlayer == null || mc.theWorld == null) {
            return false;
        }
        
        if (mc.thePlayer.capabilities.allowFlying || mc.thePlayer.capabilities.disableDamage) {
            return false;
        }
        
        if (mc.thePlayer.onGround) {
            return false;
        }
        
        // Check void condition
        if (this.onVoid.getValue() && isVoidBelow()) {
            return true;
        }
        
        // Check lethal fall condition
        if (this.onLethalFall.getValue() && isLethalFall()) {
            return true;
        }
        
        // Check more than x blocks condition
        if (this.onMoreThanXBlocks.getValue()) {
            int fallDistance = getFallDistance();
            if (fallDistance >= this.blocks.getValue()) {
                return true;
            }
        }
        
        return false;
    }
    
    private boolean isVoidBelow() {
        int playerY = MathHelper.floor_double(mc.thePlayer.posY);
        
        for (int y = playerY - 1; y >= 0; y--) {
            BlockPos pos = new BlockPos(
                MathHelper.floor_double(mc.thePlayer.posX),
                y,
                MathHelper.floor_double(mc.thePlayer.posZ)
            );
            
            if (!BlockUtil.isReplaceable(pos)) {
                return false;
            }
        }
        
        return true;
    }
    
    private boolean isLethalFall() {
        int fallDistance = getFallDistance();
        float damage = fallDistance - 3.0F;
        
        return damage >= mc.thePlayer.getHealth();
    }
    
    private int getFallDistance() {
        int playerY = MathHelper.floor_double(mc.thePlayer.posY);
        
        for (int y = playerY - 1; y >= 0; y--) {
            BlockPos pos = new BlockPos(
                MathHelper.floor_double(mc.thePlayer.posX),
                y,
                MathHelper.floor_double(mc.thePlayer.posZ)
            );
            
            if (!BlockUtil.isReplaceable(pos)) {
                return playerY - y;
            }
        }
        
        return playerY;
    }
    
    private void findBlocks() {
        this.blockCount = 0;
        
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            if (stack != null && stack.getItem() instanceof ItemBlock) {
                Block block = ((ItemBlock) stack.getItem()).getBlock();
                if (BlockUtil.isSolid(block)) {
                    this.blockCount += stack.stackSize;
                }
            }
        }
    }
    
    private int getBestBlockSlot() {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            if (stack != null && stack.getItem() instanceof ItemBlock) {
                Block block = ((ItemBlock) stack.getItem()).getBlock();
                if (BlockUtil.isSolid(block)) {
                    return slot;
                }
            }
        }
        return -1;
    }
    
    private BlockData getClutchBlockData() {
        BlockPos targetPos = new BlockPos(
            MathHelper.floor_double(mc.thePlayer.posX),
            MathHelper.floor_double(mc.thePlayer.posY) - 1,
            MathHelper.floor_double(mc.thePlayer.posZ)
        );
        
        // Anti-disappear: Wait for confirmation if needed
        if (this.antiDisappear.getValue() && waitingForConfirm) {
            if (placeConfirmTicks < this.confirmDelay.getValue()) {
                return null; // Wait before placing again
            }
            waitingForConfirm = false;
        }
        
        if (!BlockUtil.isReplaceable(targetPos)) {
            return null;
        }
        
        // Grim mode: Check if it's safe to place
        if (this.grimMode.getValue() && grimSafeMode) {
            double currentY = mc.thePlayer.posY;
            if (currentY < grimVoidCheckY + this.grimSafetyMargin.getValue()) {
                return null; // Too close to void, don't place yet
            }
        }
        
        // Check for staircase mode
        if (this.allowStaircaseUp.getValue() && shouldStaircase()) {
            double motionX = mc.thePlayer.motionX;
            double motionZ = mc.thePlayer.motionZ;
            double length = Math.sqrt(motionX * motionX + motionZ * motionZ);
            
            if (length > 0.01) {
                motionX /= length;
                motionZ /= length;
                
                BlockPos frontPos = targetPos.add(
                    MathHelper.floor_double(motionX),
                    0,
                    MathHelper.floor_double(motionZ)
                );
                
                if (BlockUtil.isReplaceable(frontPos)) {
                    targetPos = frontPos;
                }
            }
        }
        
        // Find best neighbor block to place against
        ArrayList<BlockData> options = new ArrayList<>();
        
        for (EnumFacing facing : EnumFacing.VALUES) {
            if (facing == EnumFacing.UP) continue;
            
            BlockPos neighbor = targetPos.offset(facing);
            
            if (!BlockUtil.isReplaceable(neighbor) && !BlockUtil.isInteractable(neighbor)) {
                double distance = mc.thePlayer.getDistance(
                    (double) neighbor.getX() + 0.5,
                    (double) neighbor.getY() + 0.5,
                    (double) neighbor.getZ() + 0.5
                );
                
                if (distance <= mc.playerController.getBlockReachDistance()) {
                    options.add(new BlockData(neighbor, facing.getOpposite()));
                }
            }
        }
        
        if (options.isEmpty()) {
            return null;
        }
        
        // Return closest option
        options.sort((a, b) -> {
            double distA = mc.thePlayer.getDistance(
                (double) a.blockPos.getX() + 0.5,
                (double) a.blockPos.getY() + 0.5,
                (double) a.blockPos.getZ() + 0.5
            );
            double distB = mc.thePlayer.getDistance(
                (double) b.blockPos.getX() + 0.5,
                (double) b.blockPos.getY() + 0.5,
                (double) b.blockPos.getZ() + 0.5
            );
            return Double.compare(distA, distB);
        });
        
        return options.get(0);
    }
    
    private Vec3 getPreciseHitVec(BlockPos blockPos, EnumFacing facing) {
        // Use precise offsets for better placement accuracy (Grim bypass)
        double[] xOffsets = PRECISE_OFFSETS;
        double[] yOffsets = PRECISE_OFFSETS;
        double[] zOffsets = PRECISE_OFFSETS;
        
        // Constrain to face
        switch (facing) {
            case NORTH:
                zOffsets = new double[]{0.0};
                break;
            case EAST:
                xOffsets = new double[]{1.0};
                break;
            case SOUTH:
                zOffsets = new double[]{1.0};
                break;
            case WEST:
                xOffsets = new double[]{0.0};
                break;
            case DOWN:
                yOffsets = new double[]{0.0};
                break;
            case UP:
                yOffsets = new double[]{1.0};
                break;
        }
        
        Vec3 bestVec = null;
        float bestYawDiff = Float.MAX_VALUE;
        
        for (double x : xOffsets) {
            for (double y : yOffsets) {
                for (double z : zOffsets) {
                    Vec3 vec = new Vec3(
                        blockPos.getX() + x,
                        blockPos.getY() + y,
                        blockPos.getZ() + z
                    );
                    
                    double deltaX = vec.xCoord - mc.thePlayer.posX;
                    double deltaY = vec.yCoord - (mc.thePlayer.posY + mc.thePlayer.getEyeHeight());
                    double deltaZ = vec.zCoord - mc.thePlayer.posZ;
                    
                    double distSq = deltaX * deltaX + deltaZ * deltaZ;
                    float yaw = (float) (Math.atan2(deltaZ, deltaX) * 180.0 / Math.PI) - 90.0F;
                    float yawDiff = Math.abs(MathHelper.wrapAngleTo180_float(yaw - mc.thePlayer.rotationYaw));
                    
                    if (yawDiff < bestYawDiff) {
                        bestYawDiff = yawDiff;
                        bestVec = vec;
                    }
                }
            }
        }
        
        return bestVec != null ? bestVec : new Vec3(
            blockPos.getX() + 0.5,
            blockPos.getY() + 0.5,
            blockPos.getZ() + 0.5
        );
    }
    
    private boolean shouldStaircase() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastJumpTime < 1000 && PlayerUtil.isJumping()) {
            staircaseJumps++;
            lastJumpTime = currentTime;
            return staircaseJumps >= 2;
        } else if (currentTime - lastJumpTime > 2000) {
            staircaseJumps = 0;
        }
        return false;
    }
    
    private void place(BlockPos blockPos, EnumFacing enumFacing, Vec3 vec3) {
        if (ItemUtil.isHoldingBlock() && this.blockCount > 0) {
            // Grim mode: Check placement timing
            if (this.grimMode.getValue()) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastPlaceTime < this.grimPlaceDelay.getValue()) {
                    return; // Too soon, respect place delay
                }
                lastPlaceTime = currentTime;
            }
            
            // Telly mode: Handle telly placement
            if (this.tellyMode.getValue() && tellyActive) {
                handleTellyPlacement(blockPos, enumFacing, vec3);
                return;
            }
            
            if (mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, mc.thePlayer.inventory.getCurrentItem(), blockPos, enumFacing, vec3)) {
                this.blockCount--;
                
                if (this.swing.getValue()) {
                    mc.thePlayer.swingItem();
                } else {
                    PacketUtil.sendPacket(new C0APacketAnimation());
                }
                
                // Anti-disappear: Track placement
                if (this.antiDisappear.getValue()) {
                    lastPlacedBlock = blockPos;
                    lastPlacedTime = System.currentTimeMillis();
                    waitingForConfirm = true;
                    placeConfirmTicks = 0;
                }
                
                // Track for Grim bypass
                if (this.grimMode.getValue()) {
                    recentPlacements.put(blockPos, System.currentTimeMillis());
                    consecutivePlacements++;
                    
                    // Clean old placements
                    recentPlacements.entrySet().removeIf(entry -> 
                        System.currentTimeMillis() - entry.getValue() > 5000
                    );
                }
                
                // Mark clutch as complete
                this.clutchCompleteTime = System.currentTimeMillis();
            }
        }
    }
    
    private void handleTellyPlacement(BlockPos blockPos, EnumFacing enumFacing, Vec3 vec3) {
        long currentTime = System.currentTimeMillis();
        
        switch (tellyStage) {
            case 0:
                // Stage 1: Move closer to edge
                tellyTargetPos = blockPos;
                EnumFacing facing = getDirectionFromYaw(mc.thePlayer.rotationYaw);
                Vec3i directionVec = facing.getDirectionVec();
                
                double offset = this.tellyDistance.getValue();
                mc.thePlayer.setPosition(
                    mc.thePlayer.posX + directionVec.getX() * offset,
                    mc.thePlayer.posY,
                    mc.thePlayer.posZ + directionVec.getZ() * offset
                );
                
                tellyStage = 1;
                lastTellyTime = currentTime;
                break;
                
            case 1:
                // Stage 2: Wait for delay
                if (currentTime - lastTellyTime >= this.tellyDelay.getValue() * 50L) {
                    tellyStage = 2;
                }
                break;
                
            case 2:
                // Stage 3: Place block
                if (mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, mc.thePlayer.inventory.getCurrentItem(), blockPos, enumFacing, vec3)) {
                    this.blockCount--;
                    
                    if (this.swing.getValue()) {
                        mc.thePlayer.swingItem();
                    } else {
                        PacketUtil.sendPacket(new C0APacketAnimation());
                    }
                }
                
                tellyStage = 3;
                lastTellyTime = currentTime;
                break;
                
            case 3:
                // Stage 4: Pull back
                if (currentTime - lastTellyTime >= 50L) {
                    tellyActive = false;
                    tellyStage = 0;
                }
                break;
        }
    }
    
    private EnumFacing getDirectionFromYaw(float yaw) {
        yaw = MathHelper.wrapAngleTo180_float(yaw);
        if (yaw < -135.0F || yaw >= 135.0F) {
            return EnumFacing.NORTH;
        } else if (yaw < -45.0F) {
            return EnumFacing.EAST;
        } else if (yaw < 45.0F) {
            return EnumFacing.SOUTH;
        } else {
            return EnumFacing.WEST;
        }
    }
    
    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        
        findBlocks();
        
        // Update place confirm counter
        if (waitingForConfirm) {
            placeConfirmTicks++;
            
            // Verify block was actually placed
            if (lastPlacedBlock != null && placeConfirmTicks >= this.confirmDelay.getValue()) {
                BlockPos below = new BlockPos(
                    MathHelper.floor_double(mc.thePlayer.posX),
                    MathHelper.floor_double(mc.thePlayer.posY) - 1,
                    MathHelper.floor_double(mc.thePlayer.posZ)
                );
                
                if (!BlockUtil.isReplaceable(below)) {
                    waitingForConfirm = false;
                }
            }
        }
        
        // Grim antivoid: Hyper-precise void detection
        if (this.grimMode.getValue() && this.onVoid.getValue()) {
            handleGrimAntiVoid();
        }
        
        // Telly mode: Activate when needed
        if (this.tellyMode.getValue() && !tellyActive && shouldActivate()) {
            tellyActive = true;
            tellyStage = 0;
        }
    }
    
    private void handleGrimAntiVoid() {
        // Detect void below with extreme precision
        int playerY = MathHelper.floor_double(mc.thePlayer.posY);
        double playerX = mc.thePlayer.posX;
        double playerZ = mc.thePlayer.posZ;
        
        boolean voidDetected = true;
        int groundY = -1;
        
        // Check in a 3x3 area below player for ground
        for (int xOff = -1; xOff <= 1; xOff++) {
            for (int zOff = -1; zOff <= 1; zOff++) {
                for (int y = playerY - 1; y >= 0; y--) {
                    BlockPos pos = new BlockPos(
                        MathHelper.floor_double(playerX) + xOff,
                        y,
                        MathHelper.floor_double(playerZ) + zOff
                    );
                    
                    if (!BlockUtil.isReplaceable(pos)) {
                        voidDetected = false;
                        groundY = Math.max(groundY, y);
                        break;
                    }
                    
                    if (y <= 2) {
                        break; // Confirmed void
                    }
                }
            }
        }
        
        if (voidDetected) {
            grimSafeMode = true;
            grimVoidCheckY = mc.thePlayer.posY;
            
            // Calculate safe placement Y based on fall trajectory
            double motionY = mc.thePlayer.motionY;
            double predictedY = mc.thePlayer.posY + motionY;
            
            // Only allow placement if we're not falling too fast or too close to void
            if (Math.abs(motionY) > 0.7 || predictedY < this.grimSafetyMargin.getValue()) {
                // Emergency mode: Need to place NOW
                grimSafeMode = false;
            }
        } else {
            grimSafeMode = false;
        }
    }
    
    @EventTarget(Priority.HIGH)
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        
        boolean shouldClutch = shouldActivate();
        
        if (shouldClutch && this.blockCount > 0) {
            if (!this.isClutching) {
                this.isClutching = true;
                this.clutchStartY = mc.thePlayer.posY;
                
                // Store original angles
                if (!this.hasStoredAngles) {
                    this.originalYaw = mc.thePlayer.rotationYaw;
                    this.originalPitch = mc.thePlayer.rotationPitch;
                    this.hasStoredAngles = true;
                }
                
                // Store original slot
                if (this.lastSlot == -1) {
                    this.lastSlot = mc.thePlayer.inventory.currentItem;
                }
            }
            
            // Switch to block slot
            int blockSlot = getBestBlockSlot();
            if (blockSlot >= 0) {
                mc.thePlayer.inventory.currentItem = blockSlot;
            }
            
            // Get block data for placement
            BlockData blockData = getClutchBlockData();
            
            if (blockData != null) {
                Vec3 hitVec;
                
                // Use precise hit vec for Grim mode
                if (this.grimMode.getValue() && this.grimPreciseRotations.getValue()) {
                    hitVec = getPreciseHitVec(blockData.blockPos, blockData.enumFacing);
                } else {
                    hitVec = BlockUtil.getHitVec(blockData.blockPos, blockData.enumFacing, event.getYaw(), event.getPitch());
                }
                
                Vec3 eyePos = mc.thePlayer.getPositionEyes(1.0f);
                double deltaX = hitVec.xCoord - eyePos.xCoord;
                double deltaY = hitVec.yCoord - eyePos.yCoord;
                double deltaZ = hitVec.zCoord - eyePos.zCoord;
                
                float[] rotations = RotationUtil.getRotations(deltaX, deltaY, deltaZ, event.getYaw(), event.getPitch(), 180.0f, 0.0f);
                
                // Grim mode: Add micro-adjustments to rotations for realism
                if (this.grimMode.getValue() && this.grimPreciseRotations.getValue()) {
                    rotations[0] += RandomUtil.nextFloat(-0.5F, 0.5F);
                    rotations[1] += RandomUtil.nextFloat(-0.5F, 0.5F);
                    rotations[1] = MathHelper.clamp_float(rotations[1], -90.0F, 90.0F);
                }
                
                if (this.silentAim.getValue()) {
                    event.setRotation(rotations[0], rotations[1], 0);
                    
                    // Handle movement correction based on mode
                    if (this.silentAimMovement.getValue() == 0) { // PROPER
                        event.setPervRotation(rotations[0], 0);
                    } else if (this.silentAimMovement.getValue() == 2) { // SLOW
                        float yawDiff = rotations[0] - mc.thePlayer.rotationYaw;
                        event.setPervRotation(mc.thePlayer.rotationYaw + yawDiff * 0.3F, 0);
                    }
                    // NONE mode doesn't set pervRotation
                }
                
                // Place the block
                place(blockData.blockPos, blockData.enumFacing, hitVec);
            }
        } else {
            if (this.isClutching) {
                this.isClutching = false;
                
                // Reset angle if enabled
                if (this.resetAngle.getValue() && this.hasStoredAngles) {
                    event.setRotation(this.originalYaw, this.originalPitch, 0);
                    this.hasStoredAngles = false;
                }
                
                // Return to original slot if enabled
                if (this.returnToSlot.getValue() && this.lastSlot >= 0 && this.lastSlot < 9) {
                    mc.thePlayer.inventory.currentItem = this.lastSlot;
                    this.lastSlot = -1;
                }
            }
        }
    }
    
    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        
        // Handle silent aim movement correction
        if (this.silentAim.getValue() && this.silentAimMovement.getValue() == 0 && this.isClutching) {
            if (RotationState.isActived() && MoveUtil.isForwardPressed()) {
                MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
            }
        }
    }
    
    @EventTarget
    public void onLivingUpdate(LivingUpdateEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        
        // Apply movement delay after clutch
        if (this.clutchMoveDelay.getValue() > 0) {
            long timeSinceClutch = System.currentTimeMillis() - this.clutchCompleteTime;
            int delayTicks = this.clutchMoveDelay.getValue();
            
            if (timeSinceClutch < delayTicks * 50L && timeSinceClutch > 0) {
                mc.thePlayer.movementInput.moveForward = 0.0F;
                mc.thePlayer.movementInput.moveStrafe = 0.0F;
            }
        }
    }
    
    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || !this.showBlockCount.getValue()) {
            return;
        }
        
        if (this.blockCount > 0) {
            ScaledResolution sr = new ScaledResolution(mc);
            int width = sr.getScaledWidth();
            int height = sr.getScaledHeight();
            
            String blockText = String.format("§7[§f%d§7]", this.blockCount);
            int textWidth = mc.fontRendererObj.getStringWidth(blockText);
            
            // Draw near crosshair
            int x = width / 2 + 10;
            int y = height / 2 - 4;
            
            GlStateManager.pushMatrix();
            GlStateManager.enableBlend();
            mc.fontRendererObj.drawStringWithShadow(blockText, x, y, -1);
            GlStateManager.disableBlend();
            GlStateManager.popMatrix();
        }
    }
    
    private static class BlockData {
        public final BlockPos blockPos;
        public final EnumFacing enumFacing;
        
        public BlockData(BlockPos blockPos, EnumFacing enumFacing) {
            this.blockPos = blockPos;
            this.enumFacing = enumFacing;
        }
    }
}
