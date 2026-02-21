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
import net.minecraft.util.AxisAlignedBB;

@ModuleInfo(category = ModuleCategory.MOVEMENT)
public class Freecam extends Module {

    private final Minecraft mc = Minecraft.getMinecraft();

    public final FloatProperty speed = new FloatProperty("Speed", 1.0F, 0.1F, 5.0F);
    public final BooleanProperty fakePlayer = new BooleanProperty("FakePlayer", true);

    private EntityOtherPlayerMP clone;

    private double startX, startY, startZ;
    private float startYaw, startPitch;
    private AxisAlignedBB savedBB;

    public Freecam() {
        super("Freecam", false);
    }

    @Override
    public void onEnabled() {
        if (mc.thePlayer == null || mc.theWorld == null) {
            setEnabled(false);
            return;
        }

        startX = mc.thePlayer.posX;
        startY = mc.thePlayer.posY;
        startZ = mc.thePlayer.posZ;
        startYaw = mc.thePlayer.rotationYaw;
        startPitch = mc.thePlayer.rotationPitch;

        // Save the real bounding box so we can restore it on disable
        savedBB = mc.thePlayer.getEntityBoundingBox();

        if (fakePlayer.getValue()) {
            clone = new EntityOtherPlayerMP(mc.theWorld, mc.thePlayer.getGameProfile());
            clone.copyLocationAndAnglesFrom(mc.thePlayer);
            clone.rotationYawHead = mc.thePlayer.rotationYawHead;
            clone.inventory = mc.thePlayer.inventory;
            mc.theWorld.addEntityToWorld(-1337, clone);
        }

        mc.thePlayer.noClip = true;
        mc.thePlayer.capabilities.isFlying = true;
        mc.thePlayer.capabilities.setFlySpeed(speed.getValue() * 0.05F);
        // noClip = true already bypasses moveEntity collision — DO NOT null the BB,
        // that causes NPE in rendering, physics, and raytrace code paths.
    }

    @Override
    public void onDisabled() {
        if (mc.thePlayer == null || mc.theWorld == null) return;

        if (clone != null) {
            mc.theWorld.removeEntityFromWorld(-1337);
            clone = null;
        }

        mc.thePlayer.noClip = false;

        if (!mc.thePlayer.capabilities.isCreativeMode) {
            mc.thePlayer.capabilities.isFlying = false;
            mc.thePlayer.capabilities.setFlySpeed(0.05F);
        }

        // Restore saved bounding box before teleporting back
        if (savedBB != null) {
            mc.thePlayer.setEntityBoundingBox(savedBB);
            savedBB = null;
        }

        mc.thePlayer.onGround = true;
        mc.thePlayer.setPositionAndRotation(startX, startY, startZ, startYaw, startPitch);
        mc.thePlayer.motionX = 0.0;
        mc.thePlayer.motionY = 0.0;
        mc.thePlayer.motionZ = 0.0;
        mc.thePlayer.fallDistance = 0.0F;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!isEnabled() || mc.thePlayer == null) return;
        if (event.getType() != EventType.PRE) return;

        mc.thePlayer.noClip = true;
        mc.thePlayer.fallDistance = 0.0F;
        mc.thePlayer.capabilities.isFlying = true;
        mc.thePlayer.capabilities.setFlySpeed(speed.getValue() * 0.05F);
        // Keep BB updated to player's current client-side position so rendering stays valid
        mc.thePlayer.setEntityBoundingBox(mc.thePlayer.getEntityBoundingBox());
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled()) return;

        if (event.getType() == EventType.SEND) {
            if (event.getPacket() instanceof C03PacketPlayer) {
                event.setCancelled(true);
            }
        }
    }
}
