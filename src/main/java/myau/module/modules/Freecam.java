package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.FloatProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.network.play.client.C03PacketPlayer;

public class Freecam extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    public final FloatProperty speed = new FloatProperty("speed", 1.0F, 0.1F, 5.0F);
    
    private EntityOtherPlayerMP fakePlayer = null;
    private double startX, startY, startZ;
    private float startYaw, startPitch;
    
    public Freecam() {
        super("Freecam", false);
    }
    
    @Override
    public void onEnabled() {
        if (mc.thePlayer == null || mc.theWorld == null) {
            this.setEnabled(false);
            return;
        }
        
        // Store starting position
        startX = mc.thePlayer.posX;
        startY = mc.thePlayer.posY;
        startZ = mc.thePlayer.posZ;
        startYaw = mc.thePlayer.rotationYaw;
        startPitch = mc.thePlayer.rotationPitch;
        
        // Create fake player at current position
        fakePlayer = new EntityOtherPlayerMP(mc.theWorld, mc.thePlayer.getGameProfile());
        fakePlayer.copyLocationAndAnglesFrom(mc.thePlayer);
        fakePlayer.rotationYawHead = mc.thePlayer.rotationYawHead;
        fakePlayer.inventory = mc.thePlayer.inventory;
        mc.theWorld.addEntityToWorld(-100, fakePlayer);
        
        // Set player to spectator-like mode
        mc.thePlayer.noClip = true;
        mc.thePlayer.capabilities.isFlying = true;
        mc.thePlayer.capabilities.setFlySpeed(this.speed.getValue() * 0.05F);
    }
    
    @Override
    public void onDisabled() {
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        
        // Restore position
        mc.thePlayer.setPosition(startX, startY, startZ);
        mc.thePlayer.rotationYaw = startYaw;
        mc.thePlayer.rotationPitch = startPitch;
        
        // Remove fake player
        if (fakePlayer != null) {
            mc.theWorld.removeEntityFromWorld(-100);
            fakePlayer = null;
        }
        
        // Restore player state
        mc.thePlayer.noClip = false;
        if (!mc.thePlayer.capabilities.isCreativeMode) {
            mc.thePlayer.capabilities.isFlying = false;
            mc.thePlayer.capabilities.setFlySpeed(0.05F);
        }
    }
    
    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null) {
            return;
        }
        
        // Update fly speed based on setting
        mc.thePlayer.capabilities.setFlySpeed(this.speed.getValue() * 0.05F);
        
        // Keep noClip enabled
        mc.thePlayer.noClip = true;
        
        // Prevent fall damage
        mc.thePlayer.fallDistance = 0.0F;
    }
    
    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        
        // Cancel position packets to prevent server-side movement
        if (event.getType() == EventType.SEND) {
            if (event.getPacket() instanceof C03PacketPlayer) {
                C03PacketPlayer packet = (C03PacketPlayer) event.getPacket();
                
                // Keep sending packets but with original position
                if (packet.isMoving()) {
                    event.setCancelled(true);
                    
                    // Send packet with original position instead
                    C03PacketPlayer newPacket = new C03PacketPlayer.C06PacketPlayerPosLook(
                        startX, startY, startZ,
                        packet.getYaw(), packet.getPitch(),
                        packet.isOnGround()
                    );
                    mc.getNetHandler().getNetworkManager().sendPacket(newPacket);
                }
            }
        }
    }
}
