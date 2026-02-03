package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.*;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.util.KeyBindUtil;
import myau.util.MoveUtil;
import myau.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.entity.Entity;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import org.lwjgl.input.Keyboard;

public class Freecam extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final FloatProperty speed = new FloatProperty("speed", 1.0F, 0.1F, 5.0F);
    public final BooleanProperty allowInteracting = new BooleanProperty("allow-interacting", false);
    public final BooleanProperty spawnFake = new BooleanProperty("spawn-fake", true);
    public final BooleanProperty moveFake = new BooleanProperty("move-fake", false, () -> this.spawnFake.getValue());

    private double startX, startY, startZ;
    private float startYaw, startPitch;
    private EntityOtherPlayerMP fakePlayer;
    private double cameraX, cameraY, cameraZ;
    private float cameraYaw, cameraPitch;

    public Freecam() {
        super("Freecam", false);
    }

    @Override
    public void onEnabled() {
        if (mc.thePlayer == null || mc.theWorld == null) {
            this.setEnabled(false);
            return;
        }

        startX = mc.thePlayer.posX;
        startY = mc.thePlayer.posY;
        startZ = mc.thePlayer.posZ;
        startYaw = mc.thePlayer.rotationYaw;
        startPitch = mc.thePlayer.rotationPitch;

        cameraX = startX;
        cameraY = startY;
        cameraZ = startZ;
        cameraYaw = startYaw;
        cameraPitch = startPitch;

        if (this.spawnFake.getValue()) {
            spawnFakePlayer();
        }

        mc.thePlayer.noClip = true;
    }

    @Override
    public void onDisabled() {
        if (mc.thePlayer != null) {
            mc.thePlayer.setPositionAndRotation(cameraX, cameraY, cameraZ, cameraYaw, cameraPitch);
            mc.thePlayer.noClip = false;
        }

        if (fakePlayer != null && mc.theWorld != null) {
            mc.theWorld.removeEntity(fakePlayer);
            fakePlayer = null;
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || event.getType() != EventType.PRE) {
            return;
        }

        handleCameraMovement();
        updateFakePlayer();
    }

    @EventTarget
    public void onPacketSend(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.SEND) {
            return;
        }

        if (event.getPacket() instanceof C03PacketPlayer) {
            event.setCancelled(true);
            PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(startX, startY, startZ, true));
        }
    }

    @EventTarget
    public void onMove(MoveInputEvent event) {
        if (!this.isEnabled()) {
            return;
        }

        mc.thePlayer.movementInput.moveForward = 0;
        mc.thePlayer.movementInput.moveStrafe = 0;
    }

    @EventTarget
    public void onLeftClick(LeftClickMouseEvent event) {
        if (!this.isEnabled()) {
            return;
        }

        if (!this.allowInteracting.getValue()) {
            event.setCancelled(true);
        }
    }

    @EventTarget
    public void onRightClick(RightClickMouseEvent event) {
        if (!this.isEnabled()) {
            return;
        }

        if (!this.allowInteracting.getValue()) {
            event.setCancelled(true);
        }
    }

    private void handleCameraMovement() {
        float speedMultiplier = this.speed.getValue();
        
        if (KeyBindUtil.isKeyDown(mc.gameSettings.keyBindSprint.getKeyCode())) {
            speedMultiplier *= 2.0F;
        }

        double motionX = 0.0;
        double motionY = 0.0;
        double motionZ = 0.0;

        if (KeyBindUtil.isKeyDown(mc.gameSettings.keyBindForward.getKeyCode())) {
            motionX += Math.sin(-cameraYaw * Math.PI / 180.0) * Math.cos(cameraPitch * Math.PI / 180.0);
            motionY -= Math.sin(cameraPitch * Math.PI / 180.0);
            motionZ += Math.cos(-cameraYaw * Math.PI / 180.0) * Math.cos(cameraPitch * Math.PI / 180.0);
        }

        if (KeyBindUtil.isKeyDown(mc.gameSettings.keyBindBack.getKeyCode())) {
            motionX -= Math.sin(-cameraYaw * Math.PI / 180.0) * Math.cos(cameraPitch * Math.PI / 180.0);
            motionY += Math.sin(cameraPitch * Math.PI / 180.0);
            motionZ -= Math.cos(-cameraYaw * Math.PI / 180.0) * Math.cos(cameraPitch * Math.PI / 180.0);
        }

        if (KeyBindUtil.isKeyDown(mc.gameSettings.keyBindLeft.getKeyCode())) {
            motionX += Math.sin((-cameraYaw - 90) * Math.PI / 180.0);
            motionZ += Math.cos((-cameraYaw - 90) * Math.PI / 180.0);
        }

        if (KeyBindUtil.isKeyDown(mc.gameSettings.keyBindRight.getKeyCode())) {
            motionX += Math.sin((-cameraYaw + 90) * Math.PI / 180.0);
            motionZ += Math.cos((-cameraYaw + 90) * Math.PI / 180.0);
        }

        if (KeyBindUtil.isKeyDown(mc.gameSettings.keyBindJump.getKeyCode())) {
            motionY += 0.5;
        }

        if (KeyBindUtil.isKeyDown(mc.gameSettings.keyBindSneak.getKeyCode())) {
            motionY -= 0.5;
        }

        double length = Math.sqrt(motionX * motionX + motionY * motionY + motionZ * motionZ);
        if (length > 0) {
            motionX = (motionX / length) * speedMultiplier;
            motionY = (motionY / length) * speedMultiplier;
            motionZ = (motionZ / length) * speedMultiplier;
        }

        cameraX += motionX;
        cameraY += motionY;
        cameraZ += motionZ;

        cameraYaw = mc.thePlayer.rotationYaw;
        cameraPitch = mc.thePlayer.rotationPitch;

        mc.thePlayer.setPosition(cameraX, cameraY, cameraZ);
        mc.thePlayer.motionX = 0;
        mc.thePlayer.motionY = 0;
        mc.thePlayer.motionZ = 0;
    }

    private void spawnFakePlayer() {
        if (mc.theWorld == null || mc.thePlayer == null) {
            return;
        }

        fakePlayer = new EntityOtherPlayerMP(mc.theWorld, mc.thePlayer.getGameProfile());
        fakePlayer.copyLocationAndAnglesFrom(mc.thePlayer);
        fakePlayer.rotationYawHead = mc.thePlayer.rotationYawHead;
        fakePlayer.inventory.copyInventory(mc.thePlayer.inventory);
        fakePlayer.setHealth(mc.thePlayer.getHealth());

        mc.theWorld.addEntityToWorld(-1337, fakePlayer);
    }

    private void updateFakePlayer() {
        if (fakePlayer == null || !this.spawnFake.getValue()) {
            return;
        }

        if (!this.moveFake.getValue()) {
            fakePlayer.setPosition(startX, startY, startZ);
            fakePlayer.rotationYaw = startYaw;
            fakePlayer.rotationPitch = startPitch;
            fakePlayer.rotationYawHead = startYaw;
            return;
        }

        double fakeMotionX = 0.0;
        double fakeMotionZ = 0.0;
        boolean moved = false;

        if (KeyBindUtil.isKeyDown(Keyboard.KEY_UP)) {
            fakeMotionX += Math.sin(-fakePlayer.rotationYaw * Math.PI / 180.0) * 0.2;
            fakeMotionZ += Math.cos(-fakePlayer.rotationYaw * Math.PI / 180.0) * 0.2;
            moved = true;
        }

        if (KeyBindUtil.isKeyDown(Keyboard.KEY_DOWN)) {
            fakeMotionX -= Math.sin(-fakePlayer.rotationYaw * Math.PI / 180.0) * 0.2;
            fakeMotionZ -= Math.cos(-fakePlayer.rotationYaw * Math.PI / 180.0) * 0.2;
            moved = true;
        }

        if (KeyBindUtil.isKeyDown(Keyboard.KEY_LEFT)) {
            fakePlayer.rotationYaw -= 5.0F;
            fakePlayer.rotationYawHead = fakePlayer.rotationYaw;
        }

        if (KeyBindUtil.isKeyDown(Keyboard.KEY_RIGHT)) {
            fakePlayer.rotationYaw += 5.0F;
            fakePlayer.rotationYawHead = fakePlayer.rotationYaw;
        }

        if (moved) {
            fakePlayer.setPosition(
                fakePlayer.posX + fakeMotionX,
                fakePlayer.posY,
                fakePlayer.posZ + fakeMotionZ
            );
        }
    }

    public boolean isInFreecam() {
        return this.isEnabled();
    }

    public Vec3 getRealPosition() {
        return new Vec3(startX, startY, startZ);
    }
}
