package myau.module.modules;

import myau.Myau;
import myau.enums.BlinkModules;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.PacketEvent;
import myau.events.PlayerUpdateEvent;
import myau.module.Module;
import myau.property.properties.*;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.Vec3;

/**
 * AntiVoid - Advanced void protection with prediction system
 * 
 * Modes:
 * - GhostBlock: Creates fake collision blocks below player
 * - Flag: Teleports up when falling into void
 * - Blink: Uses blink to buffer packets and snap back to safe position
 * - Teleport: Directly teleports to last safe position
 * 
 * Based on LiquidBounce's architecture with prediction system
 */
public class AntiVoid extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // Mode selection
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"GhostBlock", "Flag", "Blink", "Teleport"});
    
    // Void detection
    public final IntProperty voidLevel = new IntProperty("void-level", 0, -64, 128);
    public final IntProperty predictTicks = new IntProperty("predict-ticks", 10, 1, 30);
    
    // GhostBlock mode settings
    public final BooleanProperty ghostBlockRender = new BooleanProperty("ghost-render", true, () -> mode.getValue() == 0);
    
    // Flag mode settings
    public final FloatProperty flagHeight = new FloatProperty("flag-height", 0.42F, 0.1F, 5.0F, () -> mode.getValue() == 1);
    public final FloatProperty flagFallDistance = new FloatProperty("flag-fall-distance", 0.5F, 0.0F, 6.0F, () -> mode.getValue() == 1);
    
    // Blink mode settings
    public final IntProperty blinkMaxTicks = new IntProperty("blink-max-ticks", 20, 5, 50, () -> mode.getValue() == 2);
    
    // Teleport mode settings
    public final BooleanProperty teleportSilent = new BooleanProperty("teleport-silent", true, () -> mode.getValue() == 3);
    
    // State tracking
    private boolean isLikelyFalling = false;
    private Vec3 rescuePosition = null;
    private int ticksInVoid = 0;
    private boolean actionTaken = false;

    public AntiVoid() {
        super("AntiVoid", false);
    }

    @Override
    public void onEnabled() {
        isLikelyFalling = false;
        rescuePosition = null;
        ticksInVoid = 0;
        actionTaken = false;
    }

    @Override
    public void onDisabled() {
        // Clean up blink if active
        if (Myau.blinkManager.getBlinkingModule() == BlinkModules.ANTI_VOID) {
            Myau.blinkManager.setBlinkState(false, BlinkModules.ANTI_VOID);
        }
        actionTaken = false;
    }

    @EventTarget(Priority.HIGH)
    public void onUpdate(PlayerUpdateEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        
        // Exemption checks
        if (isExempt()) {
            if (Myau.blinkManager.getBlinkingModule() == BlinkModules.ANTI_VOID) {
                Myau.blinkManager.setBlinkState(false, BlinkModules.ANTI_VOID);
            }
            return;
        }
        
        // Check if likely falling into void
        isLikelyFalling = predictFall();
        
        // Discover safe position
        if (isLikelyFalling && rescuePosition == null) {
            rescuePosition = discoverRescuePosition();
        } else if (!isLikelyFalling) {
            rescuePosition = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
            actionTaken = false;
        }
        
        // Check if we need to take action
        if (isLikelyFalling && rescuePosition != null && !actionTaken) {
            if (isInVoidArea()) {
                boolean rescued = rescue();
                if (rescued) {
                    actionTaken = true;
                }
            }
        }
    }

    /**
     * Predict if player will fall into void based on future simulation
     */
    private boolean predictFall() {
        for (int tick = 0; tick < predictTicks.getValue(); tick++) {
            double predictedY = mc.thePlayer.posY - (tick * 0.08 * (1.0 - 0.02 * tick));
            
            if (predictedY < voidLevel.getValue()) {
                return true;
            }
            
            // Check if there's ground
            if (mc.thePlayer.fallDistance > 0.5 && !hasGroundBelow()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if player is currently in void area
     */
    private boolean isInVoidArea() {
        AxisAlignedBB checkBox = mc.thePlayer.getEntityBoundingBox().offset(0, voidLevel.getValue() - mc.thePlayer.posY, 0);
        
        // Check for any solid blocks in the void area
        int minX = (int) Math.floor(checkBox.minX);
        int minY = (int) Math.floor(checkBox.minY);
        int minZ = (int) Math.floor(checkBox.minZ);
        int maxX = (int) Math.ceil(checkBox.maxX);
        int maxY = (int) Math.ceil(checkBox.maxY);
        int maxZ = (int) Math.ceil(checkBox.maxZ);
        
        for (int x = minX; x < maxX; x++) {
            for (int y = minY; y < maxY; y++) {
                for (int z = minZ; z < maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    Block block = mc.theWorld.getBlockState(pos).getBlock();
                    if (block != null && block.getMaterial().isSolid()) {
                        return false;
                    }
                }
            }
        }
        
        return true;
    }

    /**
     * Check if there's ground below player
     */
    private boolean hasGroundBelow() {
        int playerX = (int) Math.floor(mc.thePlayer.posX);
        int playerY = (int) Math.floor(mc.thePlayer.posY);
        int playerZ = (int) Math.floor(mc.thePlayer.posZ);
        
        for (int y = playerY - 1; y >= Math.max(voidLevel.getValue(), playerY - 20); y--) {
            BlockPos pos = new BlockPos(playerX, y, playerZ);
            Block block = mc.theWorld.getBlockState(pos).getBlock();
            
            if (block != null && block.getMaterial().isSolid()) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * Discover a safe rescue position
     */
    private Vec3 discoverRescuePosition() {
        // If not falling, current position is safe
        if (!isLikelyFalling && mc.thePlayer.onGround) {
            return new Vec3(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
        }
        
        // Use previous rescue position if still valid
        if (rescuePosition != null && isSafePosition(rescuePosition)) {
            return rescuePosition;
        }
        
        // Search for safe position nearby
        return findNearestSafePosition();
    }

    /**
     * Check if a position is safe (has ground below)
     */
    private boolean isSafePosition(Vec3 pos) {
        int x = (int) Math.floor(pos.xCoord);
        int y = (int) Math.floor(pos.yCoord);
        int z = (int) Math.floor(pos.zCoord);
        
        for (int checkY = y - 1; checkY >= Math.max(voidLevel.getValue(), y - 5); checkY--) {
            BlockPos blockPos = new BlockPos(x, checkY, z);
            Block block = mc.theWorld.getBlockState(blockPos).getBlock();
            
            if (block != null && block.getMaterial().isSolid()) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * Find nearest safe position
     */
    private Vec3 findNearestSafePosition() {
        Vec3 currentPos = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
        Vec3 bestPos = currentPos;
        double bestDistance = Double.MAX_VALUE;
        
        int range = 5;
        for (int xOff = -range; xOff <= range; xOff++) {
            for (int yOff = -2; yOff <= 5; yOff++) {
                for (int zOff = -range; zOff <= range; zOff++) {
                    Vec3 testPos = currentPos.addVector(xOff, yOff, zOff);
                    
                    if (isSafePosition(testPos)) {
                        double distance = testPos.distanceTo(currentPos);
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            bestPos = testPos;
                        }
                    }
                }
            }
        }
        
        return bestPos;
    }

    /**
     * Check if player is exempt from anti-void (flying, etc)
     */
    private boolean isExempt() {
        // Don't interfere with creative flying
        if (mc.thePlayer.capabilities.isFlying) {
            return true;
        }
        
        // Don't interfere with Fly module
        Fly fly = (Fly) Myau.moduleManager.modules.get(Fly.class);
        if (fly != null && fly.isEnabled()) {
            return true;
        }
        
        // Don't interfere with LongJump
        LongJump longJump = (LongJump) Myau.moduleManager.modules.get(LongJump.class);
        if (longJump != null && longJump.isJumping()) {
            return true;
        }
        
        return false;
    }

    /**
     * Execute rescue based on selected mode
     */
    private boolean rescue() {
        switch (mode.getValue()) {
            case 0: // GhostBlock
                return rescueGhostBlock();
            case 1: // Flag
                return rescueFlag();
            case 2: // Blink
                return rescueBlink();
            case 3: // Teleport
                return rescueTeleport();
            default:
                return false;
        }
    }

    /**
     * GhostBlock mode: Creates fake collision (handled in mixin/event)
     */
    private boolean rescueGhostBlock() {
        // Ghost block mode works passively by modifying collision checks
        // The actual collision modification would need a mixin or packet manipulation
        // For now, we'll use a simple upward motion
        if (mc.thePlayer.fallDistance > flagFallDistance.getValue()) {
            mc.thePlayer.motionY = 0.42;
            mc.thePlayer.fallDistance = 0;
            return true;
        }
        return false;
    }

    /**
     * Flag mode: Teleport up when falling
     */
    private boolean rescueFlag() {
        if (mc.thePlayer.fallDistance >= flagFallDistance.getValue()) {
            mc.thePlayer.setPosition(
                mc.thePlayer.posX,
                mc.thePlayer.posY + flagHeight.getValue(),
                mc.thePlayer.posZ
            );
            mc.thePlayer.fallDistance = 0;
            mc.thePlayer.motionY = 0;
            return true;
        }
        return false;
    }

    /**
     * Blink mode: Buffer packets and snap back
     */
    private boolean rescueBlink() {
        if (Myau.blinkManager.getBlinkingModule() != BlinkModules.ANTI_VOID) {
            // Start blinking
            Myau.blinkManager.setBlinkState(true, BlinkModules.ANTI_VOID);
            ticksInVoid = 0;
        }
        
        ticksInVoid++;
        
        // Release after max ticks or if found ground
        if (ticksInVoid >= blinkMaxTicks.getValue() || mc.thePlayer.onGround || hasGroundBelow()) {
            Myau.blinkManager.setBlinkState(false, BlinkModules.ANTI_VOID);
            ticksInVoid = 0;
            return true;
        }
        
        return false;
    }

    /**
     * Teleport mode: Direct teleport to safe position
     */
    private boolean rescueTeleport() {
        if (rescuePosition == null) {
            return false;
        }
        
        if (teleportSilent.getValue()) {
            // Silent teleport (just update position)
            mc.thePlayer.setPosition(rescuePosition.xCoord, rescuePosition.yCoord + 0.5, rescuePosition.zCoord);
        } else {
            // Send packet teleport
            mc.thePlayer.setPosition(rescuePosition.xCoord, rescuePosition.yCoord + 0.5, rescuePosition.zCoord);
            mc.getNetHandler().addToSendQueue(new C03PacketPlayer.C04PacketPlayerPosition(
                rescuePosition.xCoord,
                rescuePosition.yCoord + 0.5,
                rescuePosition.zCoord,
                true
            ));
        }
        
        mc.thePlayer.fallDistance = 0;
        mc.thePlayer.motionY = 0;
        return true;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{mode.getModeString()};
    }
}
