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
    
    // Grim bypass state (Blink-based)
    private boolean grimBlinkActive = false;
    private int ticksInVoid = 0;
    private double grimVoidStartY = 0.0;
    
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"BLINK", "GRIM"});
    public final FloatProperty distance = new FloatProperty("distance", 5.0F, 0.0F, 16.0F);
    
    // Grim mode settings (Blink-based approach)
    public final myau.property.properties.IntProperty grimMaxTicks = new myau.property.properties.IntProperty("grim-max-ticks", 15, 5, 30, () -> this.mode.getValue() == 1);
    public final myau.property.properties.BooleanProperty grimAutoRelease = new myau.property.properties.BooleanProperty("grim-auto-release", true, () -> this.mode.getValue() == 1);
    public final myau.property.properties.IntProperty grimScanRadius = new myau.property.properties.IntProperty("grim-scan-radius", 5, 3, 10, () -> this.mode.getValue() == 1);

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
            
            // Grim mode (Blink-based)
            if (this.mode.getValue() == 1) {
                handleGrimBlinkMode();
            }
            // Standard Blink mode
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

    private void handleGrimBlinkMode() {
        // Blink-based approach: Ghost packets instead of teleporting
        boolean voidDetected = detectVoidBelow();
        
        if (voidDetected) {
            if (!grimBlinkActive) {
                // Start blinking when void is detected
                grimBlinkActive = true;
                grimVoidStartY = mc.thePlayer.posY;
                ticksInVoid = 0;
                
                // Enable blink to buffer packets
                Myau.blinkManager.setBlinkState(true, BlinkModules.ANTI_VOID);
            }
            
            ticksInVoid++;
            
            // Check if we've found solid ground or reached max ticks
            if (ticksInVoid > this.grimMaxTicks.getValue() || hasFoundGround()) {
                // Release packets - player will snap to current position
                if (this.grimAutoRelease.getValue()) {
                    Myau.blinkManager.setBlinkState(false, BlinkModules.ANTI_VOID);
                }
                grimBlinkActive = false;
                ticksInVoid = 0;
            }
        } else {
            // No void detected
            if (grimBlinkActive) {
                // We're safe now, release packets
                Myau.blinkManager.setBlinkState(false, BlinkModules.ANTI_VOID);
                grimBlinkActive = false;
                ticksInVoid = 0;
            }
            
            // Update safe position when on ground
            if (mc.thePlayer.onGround) {
                if (this.isSafePosition(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ)) {
                    this.validatedSafePosition = new double[]{mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ};
                    this.safePositionAge = 0;
                }
            }
        }
    }
    
    private boolean detectVoidBelow() {
        // Scan area below player for void
        int playerY = (int) Math.floor(mc.thePlayer.posY);
        int playerX = (int) Math.floor(mc.thePlayer.posX);
        int playerZ = (int) Math.floor(mc.thePlayer.posZ);
        int scanRadius = this.mode.getValue() == 1 ? this.grimScanRadius.getValue() : 1;
        
        boolean voidConfirmed = true;
        
        for (int xOff = -scanRadius; xOff <= scanRadius; xOff++) {
            for (int zOff = -scanRadius; zOff <= scanRadius; zOff++) {
                for (int y = playerY - 1; y >= Math.max(0, playerY - 15); y--) {
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
    
    private boolean hasFoundGround() {
        // Check if player has landed on solid ground
        if (mc.thePlayer.onGround) {
            return true;
        }
        
        // Check if there's ground very close below
        int playerY = (int) Math.floor(mc.thePlayer.posY);
        int playerX = (int) Math.floor(mc.thePlayer.posX);
        int playerZ = (int) Math.floor(mc.thePlayer.posZ);
        
        for (int y = playerY; y >= Math.max(0, playerY - 3); y--) {
            net.minecraft.util.BlockPos pos = new net.minecraft.util.BlockPos(playerX, y, playerZ);
            net.minecraft.block.Block block = mc.theWorld.getBlockState(pos).getBlock();
            
            if (block != null && !block.getMaterial().isReplaceable()) {
                return true;
            }
        }
        
        return false;
    }

    @Override
    public void onEnabled() {
        this.isInVoid = false;
        this.wasInVoid = false;
        this.resetBlink();
        this.ticksInVoid = 0;
        this.grimBlinkActive = false;
        this.grimVoidStartY = 0.0;
    }

    @Override
    public void onDisabled() {
        Myau.blinkManager.setBlinkState(false, BlinkModules.ANTI_VOID);
        this.ticksInVoid = 0;
        this.grimBlinkActive = false;
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
