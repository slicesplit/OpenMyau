package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.network.play.client.C03PacketPlayer;

@ModuleInfo(category = ModuleCategory.MOVEMENT)
public class Freecam extends Module {

    private final Minecraft mc = Minecraft.getMinecraft();

    public final FloatProperty speed = new FloatProperty("Speed", 1.0F, 0.1F, 5.0F);
    public final BooleanProperty fakePlayer = new BooleanProperty("FakePlayer", true);

    private EntityOtherPlayerMP clone;

    private double startX, startY, startZ;
    private float startYaw, startPitch;

    public Freecam() {
        super("Freecam", false);
    }

    @Override
    public void onEnabled() {
        if (mc.thePlayer == null || mc.theWorld == null) {
            setEnabled(false);
            return;
        }

        // Save starting position AND ground state
        startX = mc.thePlayer.posX;
        startY = mc.thePlayer.posY;
        startZ = mc.thePlayer.posZ;
        startYaw = mc.thePlayer.rotationYaw;
        startPitch = mc.thePlayer.rotationPitch;

        // Spawn fake player
        if (fakePlayer.getValue()) {
            clone = new EntityOtherPlayerMP(mc.theWorld, mc.thePlayer.getGameProfile());
            clone.copyLocationAndAnglesFrom(mc.thePlayer);
            clone.rotationYawHead = mc.thePlayer.rotationYawHead;
            clone.inventory = mc.thePlayer.inventory;

            mc.theWorld.addEntityToWorld(-1337, clone);
        }

        // GRIM BYPASS: Don't modify player state immediately
        // Let packets cancel naturally to avoid position flags
        mc.thePlayer.noClip = true;
        mc.thePlayer.capabilities.isFlying = true;
        mc.thePlayer.capabilities.setFlySpeed(speed.getValue() * 0.05F);
        
        // NOCLIP: Disable collision completely
        mc.thePlayer.setEntityBoundingBox(null);
    }

    @Override
    public void onDisabled() {
        if (mc.thePlayer == null || mc.theWorld == null) return;

        // Remove clone
        if (clone != null) {
            mc.theWorld.removeEntityFromWorld(-1337);
            clone = null;
        }

        // GRIM BYPASS: Restore state BEFORE position to avoid flags
        mc.thePlayer.noClip = false;
        
        if (!mc.thePlayer.capabilities.isCreativeMode) {
            mc.thePlayer.capabilities.isFlying = false;
            mc.thePlayer.capabilities.setFlySpeed(0.05F);
        }
        
        // NOCLIP: Restore collision bounding box BEFORE teleporting
        mc.thePlayer.setEntityBoundingBox(mc.thePlayer.getEntityBoundingBox());
        
        // GRIM BYPASS: Reset ground state to true to avoid ground spoof
        mc.thePlayer.onGround = true;
        
        // GRIM BYPASS: Restore position AFTER restoring collision
        // This prevents instant teleport flags
        mc.thePlayer.setPositionAndRotation(startX, startY, startZ, startYaw, startPitch);
        
        // GRIM BYPASS: Reset velocities to prevent movement flags
        mc.thePlayer.motionX = 0.0;
        mc.thePlayer.motionY = 0.0;
        mc.thePlayer.motionZ = 0.0;
        mc.thePlayer.fallDistance = 0.0F;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!isEnabled() || mc.thePlayer == null) return;
        if (event.getType() != EventType.PRE) return;

        // NOCLIP: Keep noclip and collision disabled every tick
        mc.thePlayer.noClip = true;
        mc.thePlayer.fallDistance = 0.0F;
        mc.thePlayer.capabilities.isFlying = true;
        
        // GRIM BYPASS: Don't force onGround = false every tick
        // Let it naturally update to avoid ground spoof flags
        
        // NOCLIP: Ensure collision stays disabled
        if (mc.thePlayer.getEntityBoundingBox() != null) {
            mc.thePlayer.setEntityBoundingBox(null);
        }

        // Apply speed setting
        mc.thePlayer.capabilities.setFlySpeed(speed.getValue() * 0.05F);
        
        // GRIM BYPASS: Keep player at exact starting position on server
        // Client position can change but server position stays frozen
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled()) return;

        // Cancel movement packets (server never updates your position)
        if (event.getType() == EventType.SEND) {
            if (event.getPacket() instanceof C03PacketPlayer) {
                event.setCancelled(true);
            }
        }
    }
}
