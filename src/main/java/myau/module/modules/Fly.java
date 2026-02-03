package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.*;
import myau.module.Module;
import myau.util.KeyBindUtil;
import myau.util.MoveUtil;
import myau.util.ChatUtil;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLadder;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;

public class Fly extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private double verticalMotion = 0.0;
    
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"VANILLA", "VULCAN_GHOST"});
    public final FloatProperty hSpeed = new FloatProperty("horizontal-speed", 1.0F, 0.0F, 100.0F);
    public final FloatProperty vSpeed = new FloatProperty("vertical-speed", 1.0F, 0.0F, 100.0F);
    public final FloatProperty ghostSpeed = new FloatProperty("ghost-speed", 0.3F, 0.1F, 1.0F, () -> this.mode.getValue() == 1);
    public final BooleanProperty grimBypass = new BooleanProperty("grim-bypass", true, () -> this.mode.getValue() == 1);
    public final FloatProperty grimMaxDistance = new FloatProperty("grim-max-distance", 3.5F, 2.0F, 6.0F, () -> this.mode.getValue() == 1 && this.grimBypass.getValue());
    
    private boolean ghostModeActive = false;
    private long lastGroundTime = 0L;

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
    }

    @EventTarget
    public void onStrafe(StrafeEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        
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
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        
        if (this.mode.getValue() == 0) {
            this.verticalMotion = 0.0;
            if (mc.currentScreen == null) {
                if (KeyBindUtil.isKeyDown(mc.gameSettings.keyBindJump.getKeyCode())) {
                    this.verticalMotion = this.verticalMotion + this.vSpeed.getValue().doubleValue() * 0.42F;
                }
                if (KeyBindUtil.isKeyDown(mc.gameSettings.keyBindSneak.getKeyCode())) {
                    this.verticalMotion = this.verticalMotion - this.vSpeed.getValue().doubleValue() * 0.42F;
                }
                KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), false);
            }
        } else if (this.mode.getValue() == 1) {
            if (this.grimBypass.getValue()) {
                boolean jumpPressed = KeyBindUtil.isKeyDown(mc.gameSettings.keyBindJump.getKeyCode());
                boolean sneakPressed = KeyBindUtil.isKeyDown(mc.gameSettings.keyBindSneak.getKeyCode());
                
                if (jumpPressed && !sneakPressed) {
                    if (mc.thePlayer.motionY < 0) {
                        mc.thePlayer.motionY *= 0.98;
                    }
                }
            }
        }
    }
    
    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || this.mode.getValue() != 1) {
            return;
        }
        
        if (event.getType() == EventType.RECEIVE && event.getPacket() instanceof S08PacketPlayerPosLook) {
            event.setCancelled(true);
            ghostModeActive = false;
        }
    }
    
    @EventTarget
    public void onBlockBB(BlockBBEvent event) {
        if (!this.isEnabled() || this.mode.getValue() != 1) {
            return;
        }
        
        boolean jumpPressed = KeyBindUtil.isKeyDown(mc.gameSettings.keyBindJump.getKeyCode());
        boolean sneakPressed = KeyBindUtil.isKeyDown(mc.gameSettings.keyBindSneak.getKeyCode());
        
        if (!jumpPressed && sneakPressed) {
            return;
        }
        
        if (mc.thePlayer.onGround) {
            lastGroundTime = System.currentTimeMillis();
        }
        
        if (this.grimBypass.getValue()) {
            long timeSinceGround = System.currentTimeMillis() - lastGroundTime;
            if (timeSinceGround < 500) {
                return;
            }
        }
        
        Block block = event.getBlock();
        Material material = block.getMaterial();
        
        if (!material.blocksMovement() && 
            material != Material.carpet && 
            material != Material.vine && 
            material != Material.snow && 
            !(block instanceof BlockLadder)) {
            
            BlockPos pos = event.getPos();
            
            double playerX = mc.thePlayer.posX;
            double playerY = mc.thePlayer.posY;
            double playerZ = mc.thePlayer.posZ;
            
            double distanceToBlock = Math.sqrt(
                Math.pow(pos.getX() + 0.5 - playerX, 2) +
                Math.pow(pos.getY() - playerY, 2) +
                Math.pow(pos.getZ() + 0.5 - playerZ, 2)
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

    @Override
    public void onDisabled() {
        if (this.mode.getValue() == 0) {
            mc.thePlayer.motionY = 0.0;
            MoveUtil.setSpeed(0.0);
            KeyBindUtil.updateKeyState(mc.gameSettings.keyBindSneak.getKeyCode());
        }
        
        ghostModeActive = false;
    }
    
    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }
}
