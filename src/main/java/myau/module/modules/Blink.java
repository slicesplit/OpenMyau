package myau.module.modules;

import myau.enums.ModuleCategory;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.Render3DEvent;
import myau.events.TickEvent;
import myau.mixin.IAccessorRenderManager;
import myau.module.Module;
import myau.module.ModuleInfo;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.ModeProperty;
import myau.utility.backtrack.TimedPacket;
import myau.utility.render.Animation;
import myau.utility.render.Easing;
import myau.util.PacketUtil;
import myau.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.handshake.client.C00Handshake;
import net.minecraft.network.login.client.C00PacketLoginStart;
import net.minecraft.network.login.client.C01PacketEncryptionResponse;
import net.minecraft.network.play.client.C01PacketChatMessage;
import net.minecraft.network.status.client.C00PacketServerQuery;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

@ModuleInfo(category = ModuleCategory.MOVEMENT)
public class Blink extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    // 0 = Normal  — holds packets until manually disabled (Raven NormalBlink)
    // 1 = FakeLag — holds packets up to maxBlinkTime ms then auto-releases (Raven FakeLagBlink)
    public final ModeProperty mode = new ModeProperty("Mode", 0,
            new String[]{"Normal", "FakeLag"});

    // Normal mode options
    public final BooleanProperty pulse = new BooleanProperty("Pulse", false);
    public final FloatProperty pulseDelay = new FloatProperty("PulseDelay", 1000f, 0f, 10000f,
            () -> mode.getValue() == 0 && pulse.getValue());
    public final BooleanProperty showInitialPosition = new BooleanProperty("ShowInitialPos", false);

    // FakeLag mode options
    public final FloatProperty maxBlinkTime = new FloatProperty("MaxBlinkTime", 20000f, 1000f, 30000f,
            () -> mode.getValue() == 1);
    public final BooleanProperty slowRelease = new BooleanProperty("SlowRelease", false,
            () -> mode.getValue() == 1);
    public final FloatProperty releaseSpeed = new FloatProperty("ReleaseSpeed", 2f, 2f, 10f,
            () -> mode.getValue() == 1 && slowRelease.getValue());

    // Shared
    public final BooleanProperty drawRealPosition = new BooleanProperty("DrawRealPos", true);

    // ── state ────────────────────────────────────────────────────────────────

    private final Queue<TimedPacket> packetQueue = new ConcurrentLinkedQueue<>();
    private final Queue<Packet<?>> skipPackets = new ConcurrentLinkedQueue<>();

    private Vec3 trackedPos = null;
    private Vec3 initialPos = null;

    private final Animation animationX = new Animation(Easing.EASE_OUT_CIRC, 200);
    private final Animation animationY = new Animation(Easing.EASE_OUT_CIRC, 200);
    private final Animation animationZ = new Animation(Easing.EASE_OUT_CIRC, 200);

    private long startTime = 0;
    private long stopTime = 0;
    private long blinkedTime = 0;
    private boolean needToDisable = false;

    public Blink() {
        super("Blink", false);
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onEnabled() {
        if (mc.thePlayer == null) return;

        packetQueue.clear();
        skipPackets.clear();
        needToDisable = false;

        initialPos = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
        trackedPos = initialPos;

        animationX.setValue(trackedPos.xCoord);
        animationY.setValue(trackedPos.yCoord);
        animationZ.setValue(trackedPos.zCoord);

        startTime = System.currentTimeMillis();
        blinkedTime = 0;
    }

    @Override
    public void onDisabled() {
        if (mode.getValue() == 1 && !needToDisable) {
            needToDisable = true;
            stopTime = System.currentTimeMillis();
        } else {
            reset();
        }
    }

    // ── tick ─────────────────────────────────────────────────────────────────

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE) return;
        if (mc.thePlayer == null) {
            sendPacket(false);
            return;
        }

        if (mode.getValue() == 1) {
            // FakeLag mode tick
            if (needToDisable) {
                sendPacket(false);
                if (packetQueue.isEmpty()) {
                    needToDisable = false;
                }
                return;
            }

            blinkedTime = Math.min(
                    System.currentTimeMillis() - startTime,
                    maxBlinkTime.getValue().longValue()
            );

            sendPacket(true);

        } else {
            // Normal mode — pulse check
            if (pulse.getValue() && trackedPos != null) {
                if (System.currentTimeMillis() - startTime >= pulseDelay.getValue().longValue()) {
                    reset();
                    start();
                }
            }
        }
    }

    // ── packet hook ───────────────────────────────────────────────────────────

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (event.getType() != EventType.SEND) return;
        if (mc.thePlayer == null || mc.thePlayer.isDead) {
            setEnabled(false);
            return;
        }

        Packet<?> packet = event.getPacket();

        if (skipPackets.contains(packet)) {
            skipPackets.remove(packet);
            return;
        }

        if (packet instanceof C00Handshake
                || packet instanceof C00PacketLoginStart
                || packet instanceof C00PacketServerQuery
                || packet instanceof C01PacketEncryptionResponse
                || packet instanceof C01PacketChatMessage) {
            return;
        }

        if (packet.getClass().getSimpleName().startsWith("S")) {
            return;
        }

        if (event.isCancelled()) return;

        if (mode.getValue() == 1 && needToDisable) return;

        packetQueue.add(new TimedPacket(packet, System.currentTimeMillis()));
        event.setCancelled(true);
    }

    // ── render ────────────────────────────────────────────────────────────────

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (mc.thePlayer == null) return;

        if (trackedPos != null) {
            animationX.run(trackedPos.xCoord);
            animationY.run(trackedPos.yCoord);
            animationZ.run(trackedPos.zCoord);
        }

        if (drawRealPosition.getValue() && trackedPos != null) {
            Vec3 animated = new Vec3(
                    animationX.getValue(),
                    animationY.getValue(),
                    animationZ.getValue()
            );
            drawBox(animated);
        }

        if (mode.getValue() == 0 && showInitialPosition.getValue() && initialPos != null) {
            drawBox(initialPos);
        }
    }

    // ── world load ────────────────────────────────────────────────────────────

    @EventTarget
    public void onWorldLoad(LoadWorldEvent event) {
        setEnabled(false);
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private void sendPacket(boolean delay) {
        try {
            while (!packetQueue.isEmpty()) {
                boolean shouldSend;

                if (!delay) {
                    if (slowRelease.getValue() && mode.getValue() == 1) {
                        double maxTime = maxBlinkTime.getValue().doubleValue();
                        double relSpeed = releaseSpeed.getValue().doubleValue();
                        double releaseProgress = Math.min(
                                (System.currentTimeMillis() - stopTime) / (maxTime / relSpeed),
                                1.0
                        );
                        double holdProgress = blinkedTime / maxTime;
                        double remaining = holdProgress - releaseProgress;
                        shouldSend = packetQueue.element().getCold().getCum(
                                (long) (maxTime * remaining)
                        );
                    } else {
                        shouldSend = true;
                    }
                } else {
                    shouldSend = packetQueue.element().getCold().getCum(
                            maxBlinkTime.getValue().longValue()
                    );
                }

                if (shouldSend) {
                    Packet<?> packet = packetQueue.remove().getPacket();
                    if (packet == null) continue;

                    updateTrackedPos(packet);

                    skipPackets.add(packet);
                    PacketUtil.sendPacketNoEvent(packet);
                } else {
                    break;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void reset() {
        synchronized (packetQueue) {
            for (TimedPacket tp : packetQueue) {
                Packet<?> packet = tp.getPacket();
                skipPackets.add(packet);
                PacketUtil.sendPacketNoEvent(packet);
            }
        }
        packetQueue.clear();
        trackedPos = null;
        initialPos = null;
        needToDisable = false;
    }

    private void start() {
        packetQueue.clear();
        if (mc.thePlayer != null) {
            initialPos = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
            trackedPos = initialPos;
        }
        startTime = System.currentTimeMillis();
    }

    private void updateTrackedPos(Packet<?> packet) {
        if (packet instanceof net.minecraft.network.play.client.C03PacketPlayer.C06PacketPlayerPosLook) {
            net.minecraft.network.play.client.C03PacketPlayer.C06PacketPlayerPosLook p =
                    (net.minecraft.network.play.client.C03PacketPlayer.C06PacketPlayerPosLook) packet;
            trackedPos = new Vec3(p.getPositionX(), p.getPositionY(), p.getPositionZ());
        } else if (packet instanceof net.minecraft.network.play.client.C03PacketPlayer.C04PacketPlayerPosition) {
            net.minecraft.network.play.client.C03PacketPlayer.C04PacketPlayerPosition p =
                    (net.minecraft.network.play.client.C03PacketPlayer.C04PacketPlayerPosition) packet;
            trackedPos = new Vec3(p.getPositionX(), p.getPositionY(), p.getPositionZ());
        }
    }

    /**
     * Draw a white wireframe box at the given world position.
     * Mirrors Raven's Blink.drawBox() exactly.
     */
    public static void drawBox(Vec3 pos) {
        if (mc.thePlayer == null) return;

        double x = pos.xCoord - ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosX();
        double y = pos.yCoord - ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosY();
        double z = pos.zCoord - ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosZ();

        AxisAlignedBB bbox = mc.thePlayer.getEntityBoundingBox().expand(0.1, 0.1, 0.1);
        AxisAlignedBB axis = new AxisAlignedBB(
                bbox.minX - mc.thePlayer.posX + x,
                bbox.minY - mc.thePlayer.posY + y,
                bbox.minZ - mc.thePlayer.posZ + z,
                bbox.maxX - mc.thePlayer.posX + x,
                bbox.maxY - mc.thePlayer.posY + y,
                bbox.maxZ - mc.thePlayer.posZ + z
        );

        int color = new Color(255, 255, 255, 200).getRGB();
        float a = (float) (color >> 24 & 255) / 255.0F;
        float r = (float) (color >> 16 & 255) / 255.0F;
        float g = (float) (color >> 8 & 255) / 255.0F;
        float b = (float) (color & 255) / 255.0F;

        GL11.glPushMatrix();
        GL11.glBlendFunc(770, 771);
        GL11.glEnable(3042);
        GL11.glDisable(3553);
        GL11.glDisable(2929);
        GL11.glDepthMask(false);
        GL11.glLineWidth(2.0F);
        GL11.glColor4f(r, g, b, a);
        RenderUtil.drawOutlinedBox(axis);
        GL11.glEnable(3553);
        GL11.glEnable(2929);
        GL11.glDepthMask(true);
        GL11.glDisable(3042);
        GL11.glPopMatrix();
    }

    @Override
    public String[] getSuffix() {
        return new String[]{String.valueOf(packetQueue.size())};
    }
}