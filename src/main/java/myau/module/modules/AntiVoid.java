package myau.module.modules;

import com.google.common.base.CaseFormat;
import myau.Myau;
import myau.enums.BlinkModules;
import myau.event.EventTarget;
import myau.event.types.Priority;
import myau.events.KeyEvent;
import myau.events.PlayerUpdateEvent;
import myau.module.Module;
import myau.util.PlayerUtil;
import myau.util.RandomUtil;
import myau.property.properties.FloatProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemEnderPearl;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C03PacketPlayer.C04PacketPlayerPosition;
import net.minecraft.util.AxisAlignedBB;

public class AntiVoid extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private boolean isInVoid = false;
    private boolean wasInVoid = false;
    private double[] lastSafePosition = null;
    private double[] validatedSafePosition = null;
    private int safePositionAge = 0;
    
    // Grim bypass state
    private double[] predictedPosition = null;
    private double motionYAccumulator = 0.0;
    private int ticksInVoid = 0;
    private boolean grimSafeMode = false;
    
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"BLINK", "GRIM"});
    public final FloatProperty distance = new FloatProperty("distance", 5.0F, 0.0F, 16.0F);
    
    // Grim mode settings
    public final FloatProperty grimSafetyMargin = new FloatProperty("grim-safety-margin", 0.5F, 0.1F, 2.0F, () -> this.mode.getValue() == 1);
    public final FloatProperty grimPredictionAccuracy = new FloatProperty("grim-prediction-accuracy", 0.95F, 0.8F, 1.0F, () -> this.mode.getValue() == 1);
    public final myau.property.properties.IntProperty grimMaxTicks = new myau.property.properties.IntProperty("grim-max-ticks", 10, 5, 20, () -> this.mode.getValue() == 1);
    public final myau.property.properties.BooleanProperty grimAdvancedPrediction = new myau.property.properties.BooleanProperty("grim-advanced-prediction", true, () -> this.mode.getValue() == 1);

    private void resetBlink() {
        Myau.blinkManager.setBlinkState(false, BlinkModules.ANTI_VOID);
        this.lastSafePosition = null;
        this.validatedSafePosition = null;
        this.safePositionAge = 0;
    }

    private boolean canUseAntiVoid() {
        LongJump longJump = (LongJump) Myau.moduleManager.modules.get(LongJump.class);
        return !longJump.isJumping();
    }

    public AntiVoid() {
        super("AntiVoid", false);
    }

    @EventTarget(Priority.LOWEST)
    public void onUpdate(PlayerUpdateEvent event) {
        if (this.isEnabled()) {
            this.isInVoid = !mc.thePlayer.capabilities.allowFlying && PlayerUtil.isInWater();
            
            // Grim mode
            if (this.mode.getValue() == 1) {
                handleGrimMode();
            }
            // Blink mode
            else if (this.mode.getValue() == 0) {
                if (!this.isInVoid && mc.thePlayer.onGround) {
                    if (this.isSafePosition(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ)) {
                        this.validatedSafePosition = new double[]{mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ};
                        this.safePositionAge = 0;
                    }
                }
                
                if (this.validatedSafePosition != null) {
                    this.safePositionAge++;
                    if (this.safePositionAge > 200) {
                        this.validatedSafePosition = null;
                        this.safePositionAge = 0;
                    }
                }
                
                if (!this.isInVoid) {
                    if (Myau.blinkManager.getBlinkingModule() == BlinkModules.ANTI_VOID) {
                        this.resetBlink();
                    }
                }
                
                if (this.lastSafePosition != null) {
                    float subWidth = mc.thePlayer.width / 2.0F;
                    float height = mc.thePlayer.height;
                    if (PlayerUtil.checkInWater(
                            new AxisAlignedBB(
                                    this.lastSafePosition[0] - (double) subWidth,
                                    this.lastSafePosition[1],
                                    this.lastSafePosition[2] - (double) subWidth,
                                    this.lastSafePosition[0] + (double) subWidth,
                                    this.lastSafePosition[1] + (double) height,
                                    this.lastSafePosition[2] + (double) subWidth
                            )
                    )) {
                        this.lastSafePosition = null;
                    }
                }
                
                if (!this.wasInVoid && this.isInVoid && this.canUseAntiVoid()) {
                    Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                    if (Myau.blinkManager.setBlinkState(true, BlinkModules.ANTI_VOID)) {
                        double[] bestSafePos = this.validatedSafePosition != null ? this.validatedSafePosition 
                            : new double[]{mc.thePlayer.prevPosX, mc.thePlayer.prevPosY, mc.thePlayer.prevPosZ};
                        
                        if (this.isSafePosition(bestSafePos[0], bestSafePos[1], bestSafePos[2])) {
                            this.lastSafePosition = bestSafePos;
                        } else {
                            this.lastSafePosition = this.findNearestSafePosition(bestSafePos[0], bestSafePos[1], bestSafePos[2]);
                        }
                    }
                }
                
                if (Myau.blinkManager.getBlinkingModule() == BlinkModules.ANTI_VOID
                        && this.lastSafePosition != null
                        && this.lastSafePosition[1] - (double) this.distance.getValue().floatValue() > mc.thePlayer.posY) {
                    
                    double teleportY = this.lastSafePosition[1] + 0.5;
                    
                    Myau.blinkManager
                            .blinkedPackets
                            .offerFirst(
                                    new C04PacketPlayerPosition(
                                            this.lastSafePosition[0], 
                                            teleportY, 
                                            this.lastSafePosition[2], 
                                            true
                                    )
                            );
                    this.resetBlink();
                }
            }
            this.wasInVoid = this.isInVoid;
        }
    }
    
    private boolean isSafePosition(double x, double y, double z) {
        if (y < 0) {
            return false;
        }
        
        int blockX = (int) Math.floor(x);
        int blockY = (int) Math.floor(y);
        int blockZ = (int) Math.floor(z);
        
        for (int checkY = blockY; checkY >= Math.max(0, blockY - 5); checkY--) {
            net.minecraft.util.BlockPos pos = new net.minecraft.util.BlockPos(blockX, checkY, blockZ);
            net.minecraft.block.Block block = mc.theWorld.getBlockState(pos).getBlock();
            
            if (block != null && !block.getMaterial().isReplaceable() && block.getMaterial().isSolid()) {
                return true;
            }
        }
        
        return false;
    }
    
    private double[] findNearestSafePosition(double x, double y, double z) {
        double bestX = x;
        double bestY = y;
        double bestZ = z;
        double bestDistance = Double.MAX_VALUE;
        
        for (int xOffset = -3; xOffset <= 3; xOffset++) {
            for (int zOffset = -3; zOffset <= 3; zOffset++) {
                for (int yOffset = 0; yOffset <= 5; yOffset++) {
                    double checkX = x + xOffset;
                    double checkY = y + yOffset;
                    double checkZ = z + zOffset;
                    
                    if (this.isSafePosition(checkX, checkY, checkZ)) {
                        double distance = Math.sqrt(
                            xOffset * xOffset + yOffset * yOffset + zOffset * zOffset
                        );
                        
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            bestX = checkX;
                            bestY = checkY;
                            bestZ = checkZ;
                        }
                    }
                }
            }
        }
        
        return new double[]{bestX, bestY, bestZ};
    }

    @EventTarget
    public void onKey(KeyEvent event) {
        if (event.getKey() == mc.gameSettings.keyBindUseItem.getKeyCode()) {
            ItemStack currentItem = mc.thePlayer.inventory.getCurrentItem();
            if (currentItem != null && currentItem.getItem() instanceof ItemEnderPearl) {
                this.resetBlink();
            }
        }
    }

    private void handleGrimMode() {
        // Hyper-precise void detection for Grim
        boolean voidDetected = detectVoidWithPrediction();
        
        if (voidDetected) {
            ticksInVoid++;
            
            // Advanced prediction algorithm
            if (this.grimAdvancedPrediction.getValue()) {
                predictFallTrajectory();
            }
            
            // Calculate optimal teleport position
            if (ticksInVoid >= 3 && ticksInVoid <= this.grimMaxTicks.getValue()) {
                double[] optimalPos = calculateGrimSafeTeleport();
                
                if (optimalPos != null && shouldTeleportNow()) {
                    performGrimSafeTeleport(optimalPos);
                    ticksInVoid = 0;
                    grimSafeMode = false;
                }
            } else if (ticksInVoid > this.grimMaxTicks.getValue()) {
                // Emergency teleport
                double[] emergency = findEmergencySafePosition();
                if (emergency != null) {
                    performGrimSafeTeleport(emergency);
                }
                ticksInVoid = 0;
            }
        } else {
            ticksInVoid = 0;
            grimSafeMode = false;
            motionYAccumulator = 0.0;
        }
        
        // Update safe position
        if (!voidDetected && mc.thePlayer.onGround) {
            if (this.isSafePosition(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ)) {
                this.validatedSafePosition = new double[]{mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ};
                this.safePositionAge = 0;
            }
        }
    }
    
    private boolean detectVoidWithPrediction() {
        // Check 3x3 area below player
        int playerY = (int) Math.floor(mc.thePlayer.posY);
        int playerX = (int) Math.floor(mc.thePlayer.posX);
        int playerZ = (int) Math.floor(mc.thePlayer.posZ);
        
        boolean voidConfirmed = true;
        
        for (int xOff = -1; xOff <= 1; xOff++) {
            for (int zOff = -1; zOff <= 1; zOff++) {
                for (int y = playerY - 1; y >= Math.max(0, playerY - 10); y--) {
                    net.minecraft.util.BlockPos pos = new net.minecraft.util.BlockPos(
                        playerX + xOff, y, playerZ + zOff
                    );
                    net.minecraft.block.Block block = mc.theWorld.getBlockState(pos).getBlock();
                    
                    if (block != null && !block.getMaterial().isReplaceable()) {
                        voidConfirmed = false;
                        break;
                    }
                    
                    if (y <= 2) break;
                }
                if (!voidConfirmed) break;
            }
            if (!voidConfirmed) break;
        }
        
        return voidConfirmed;
    }
    
    private void predictFallTrajectory() {
        // Accurate fall prediction using Minecraft physics
        double motionY = mc.thePlayer.motionY;
        motionYAccumulator += motionY;
        
        // Predict position after N ticks
        double predictedY = mc.thePlayer.posY;
        double currentMotionY = motionY;
        
        for (int tick = 0; tick < 5; tick++) {
            currentMotionY -= 0.08; // Gravity
            currentMotionY *= 0.98; // Air resistance
            predictedY += currentMotionY;
        }
        
        predictedPosition = new double[]{
            mc.thePlayer.posX + mc.thePlayer.motionX * 3,
            predictedY,
            mc.thePlayer.posZ + mc.thePlayer.motionZ * 3
        };
    }
    
    private double[] calculateGrimSafeTeleport() {
        if (validatedSafePosition == null) {
            return findEmergencySafePosition();
        }
        
        // Calculate safe teleport with Grim-safe margins
        double safeX = validatedSafePosition[0];
        double safeY = validatedSafePosition[1] + this.grimSafetyMargin.getValue();
        double safeZ = validatedSafePosition[2];
        
        // Verify it's still safe
        if (isSafePosition(safeX, safeY, safeZ)) {
            return new double[]{safeX, safeY, safeZ};
        }
        
        return findEmergencySafePosition();
    }
    
    private boolean shouldTeleportNow() {
        // Grim-safe timing: Only teleport when fall velocity indicates danger
        double motionY = mc.thePlayer.motionY;
        double currentY = mc.thePlayer.posY;
        
        // Calculate if we'll hit void in next few ticks
        double predictedY = currentY + (motionY * 3) - (0.08 * 3 * 3 / 2);
        
        if (predictedY < this.grimSafetyMargin.getValue()) {
            return true;
        }
        
        // Also check if motion is too fast
        if (Math.abs(motionY) > 0.7) {
            return true;
        }
        
        return false;
    }
    
    private void performGrimSafeTeleport(double[] position) {
        // Use precise positioning to avoid Grim setback detection
        double teleportX = position[0];
        double teleportY = position[1];
        double teleportZ = position[2];
        
        // Add small random offset for realism (Grim bypass)
        teleportX += RandomUtil.nextFloat(-0.05F, 0.05F);
        teleportZ += RandomUtil.nextFloat(-0.05F, 0.05F);
        
        // Send position packet
        mc.thePlayer.setPosition(teleportX, teleportY, teleportZ);
        mc.getNetHandler().getNetworkManager().sendPacket(
            new C04PacketPlayerPosition(teleportX, teleportY, teleportZ, true)
        );
        
        // Reset motion
        mc.thePlayer.motionY = 0.0;
    }
    
    private double[] findEmergencySafePosition() {
        // Search wider area for any safe position
        double playerX = mc.thePlayer.posX;
        double playerY = mc.thePlayer.posY;
        double playerZ = mc.thePlayer.posZ;
        
        for (int radius = 1; radius <= 10; radius++) {
            for (int xOff = -radius; xOff <= radius; xOff++) {
                for (int zOff = -radius; zOff <= radius; zOff++) {
                    for (int yOff = 0; yOff <= 10; yOff++) {
                        double checkX = playerX + xOff;
                        double checkY = Math.max(playerY + yOff, 1.0);
                        double checkZ = playerZ + zOff;
                        
                        if (isSafePosition(checkX, checkY, checkZ)) {
                            return new double[]{checkX, checkY, checkZ};
                        }
                    }
                }
            }
        }
        
        // Last resort: teleport to spawn height
        return new double[]{playerX, 64.0, playerZ};
    }

    @Override
    public void onEnabled() {
        this.isInVoid = false;
        this.wasInVoid = false;
        this.resetBlink();
        this.ticksInVoid = 0;
        this.grimSafeMode = false;
        this.motionYAccumulator = 0.0;
        this.predictedPosition = null;
    }

    @Override
    public void onDisabled() {
        Myau.blinkManager.setBlinkState(false, BlinkModules.ANTI_VOID);
        this.ticksInVoid = 0;
        this.grimSafeMode = false;
    }

    @Override
    public void verifyValue(String mode) {
        if (this.isEnabled()) {
            this.onDisabled();
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, this.mode.getModeString())};
    }
}
