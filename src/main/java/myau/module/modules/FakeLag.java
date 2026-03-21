package myau.module.modules;

import myau.enums.ModuleCategory;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.AttackEvent;
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
import myau.util.PacketUtil;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.handshake.client.C00Handshake;
import net.minecraft.network.login.client.C00PacketLoginStart;
import net.minecraft.network.login.client.C01PacketEncryptionResponse;
import net.minecraft.network.play.client.C01PacketChatMessage;
import net.minecraft.network.status.client.C00PacketServerQuery;
import net.minecraft.util.Vec3;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

@ModuleInfo(category = ModuleCategory.COMBAT)
public class FakeLag extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty mode = new ModeProperty("Mode", 0,
            new String[]{"Latency", "Dynamic"});

    public final FloatProperty delay = new FloatProperty("Delay", 200f, 25f, 1000f);
    public final BooleanProperty drawRealPosition = new BooleanProperty("DrawRealPos", true,
            () -> mode.getValue() == 0);

    public final BooleanProperty ignoreTeammates = new BooleanProperty("IgnoreTeammates", true,
            () -> mode.getValue() == 1);
    public final BooleanProperty stopOnHurt = new BooleanProperty("StopOnHurt", true,
            () -> mode.getValue() == 1);
    public final FloatProperty stopOnHurtTime = new FloatProperty("StopOnHurtTime", 500f, 0f, 1000f,
            () -> mode.getValue() == 1 && stopOnHurt.getValue());
    public final FloatProperty startRange = new FloatProperty("StartRange", 6.0f, 3.0f, 10.0f,
            () -> mode.getValue() == 1);
    public final FloatProperty stopRange = new FloatProperty("StopRange", 3.5f, 1.0f, 6.0f,
            () -> mode.getValue() == 1);
    public final FloatProperty maxTargetRange = new FloatProperty("MaxTargetRange", 15.0f, 6.0f, 20.0f,
            () -> mode.getValue() == 1);

    private final Queue<TimedPacket> packetQueue = new ConcurrentLinkedQueue<>();
    private Vec3 vec3 = null;
    private AbstractClientPlayer dynamicTarget = null;
    private long lastDisableTime = -1;
    private boolean lastHurt = false;
    private long lastStartBlinkTime = -1;
    private boolean dynamicBlinking = false;

    public FakeLag() {
        super("FakeLag", false);
    }

    @Override
    public void onEnabled() {
        packetQueue.clear();
        vec3 = null;
        dynamicTarget = null;
        lastDisableTime = -1;
        lastHurt = false;
        lastStartBlinkTime = -1;
        dynamicBlinking = false;
    }

    @Override
    public void onDisabled() {
        sendPacket(false);
        vec3 = null;
        dynamicBlinking = false;
    }

    // ── public API ────────────────────────────────────────────────────────────

    public boolean isLagging() {
        return isEnabled() && !packetQueue.isEmpty();
    }

    /** Backward compat — used by Regen and others. */
    public boolean isActive() {
        return isLagging();
    }

    public void forceFlush() {
        sendPacket(false);
    }

    @Override
    public String[] getSuffix() {
        return new String[]{mode.getModeString(), String.valueOf(packetQueue.size())};
    }

    // ── tick ─────────────────────────────────────────────────────────────────

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE) return;
        if (mc.thePlayer == null) {
            sendPacket(false);
            return;
        }

        if (mode.getValue() == 0) {
            sendPacket(true);
        } else {
            if (!isNullCheck()) {
                sendPacket(false);
                lastDisableTime = System.currentTimeMillis();
                lastStartBlinkTime = -1;
                return;
            }

            if (stopOnHurt.getValue()
                    && lastDisableTime != -1
                    && System.currentTimeMillis() - lastDisableTime <= stopOnHurtTime.getValue().longValue()) {
                if (dynamicBlinking) {
                    dynamicBlinking = false;
                    sendPacket(false);
                }
            }

            if (dynamicBlinking) {
                if (lastStartBlinkTime != -1
                        && System.currentTimeMillis() - lastStartBlinkTime > delay.getValue().longValue()) {
                    dynamicBlinking = false;
                    lastStartBlinkTime = System.currentTimeMillis();
                    sendPacket(false);
                } else if (!lastHurt && mc.thePlayer.hurtTime > 0 && stopOnHurt.getValue()) {
                    lastDisableTime = System.currentTimeMillis();
                    dynamicBlinking = false;
                    sendPacket(false);
                }
            }

            if (dynamicTarget != null) {
                double distance = distanceTo(mc.thePlayer, dynamicTarget);

                if (dynamicBlinking && distance < stopRange.getValue()) {
                    dynamicBlinking = false;
                    sendPacket(false);
                } else if (!dynamicBlinking
                        && distance > stopRange.getValue()
                        && distance < startRange.getValue()) {
                    lastStartBlinkTime = System.currentTimeMillis();
                    dynamicBlinking = true;
                } else if (dynamicBlinking && distance > startRange.getValue()) {
                    dynamicBlinking = false;
                    sendPacket(false);
                } else if (distance > maxTargetRange.getValue()) {
                    dynamicTarget = null;
                    dynamicBlinking = false;
                    sendPacket(false);
                }
            } else {
                if (!packetQueue.isEmpty()) {
                    dynamicBlinking = false;
                    sendPacket(false);
                }
            }

            lastHurt = mc.thePlayer.hurtTime > 0;
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (event.getType() != EventType.SEND) return;
        if (mc.thePlayer == null) return;

        Packet<?> packet = event.getPacket();

        if (packet instanceof C00Handshake
                || packet instanceof C00PacketLoginStart
                || packet instanceof C00PacketServerQuery
                || packet instanceof C01PacketEncryptionResponse
                || packet instanceof C01PacketChatMessage) {
            return;
        }

        if (event.isCancelled()) return;

        if (mode.getValue() == 1) {
            if (!dynamicBlinking) return;
        }

        packetQueue.add(new TimedPacket(packet, System.currentTimeMillis()));
        event.setCancelled(true);
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (mode.getValue() != 0) return;
        if (!drawRealPosition.getValue()) return;
        if (vec3 == null) return;
        if (mc.gameSettings.thirdPersonView == 0) return;
        Blink.drawBox(vec3);
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (mode.getValue() != 1) return;
        if (!(event.getTarget() instanceof AbstractClientPlayer)) return;
        AbstractClientPlayer attacked = (AbstractClientPlayer) event.getTarget();
        if (ignoreTeammates.getValue() && TeamUtil.isSameTeam(attacked)) return;
        dynamicTarget = attacked;
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        packetQueue.clear();
        vec3 = null;
        dynamicTarget = null;
        dynamicBlinking = false;
        lastDisableTime = -1;
        lastHurt = false;
        lastStartBlinkTime = -1;
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private void sendPacket(boolean useDelay) {
        try {
            while (!packetQueue.isEmpty()) {
                boolean shouldSend;

                if (!useDelay) {
                    shouldSend = true;
                } else {
                    shouldSend = packetQueue.element().getCold().getCum(
                            delay.getValue().longValue()
                    );
                }

                if (shouldSend) {
                    Packet<?> packet = packetQueue.remove().getPacket();
                    if (packet == null) continue;

                    if (mode.getValue() == 0) {
                        updateTrackedPos(packet);
                    }

                    PacketUtil.sendPacketNoEvent(packet);
                } else {
                    break;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void updateTrackedPos(Packet<?> packet) {
        if (packet instanceof net.minecraft.network.play.client.C03PacketPlayer.C06PacketPlayerPosLook) {
            net.minecraft.network.play.client.C03PacketPlayer.C06PacketPlayerPosLook p =
                    (net.minecraft.network.play.client.C03PacketPlayer.C06PacketPlayerPosLook) packet;
            vec3 = new Vec3(p.getPositionX(), p.getPositionY(), p.getPositionZ());
        } else if (packet instanceof net.minecraft.network.play.client.C03PacketPlayer.C04PacketPlayerPosition) {
            net.minecraft.network.play.client.C03PacketPlayer.C04PacketPlayerPosition p =
                    (net.minecraft.network.play.client.C03PacketPlayer.C04PacketPlayerPosition) packet;
            vec3 = new Vec3(p.getPositionX(), p.getPositionY(), p.getPositionZ());
        }
    }

    private static double distanceTo(net.minecraft.entity.Entity a, net.minecraft.entity.Entity b) {
        double dx = a.posX - b.posX;
        double dy = a.posY - b.posY;
        double dz = a.posZ - b.posZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static boolean isNullCheck() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.thePlayer != null && mc.theWorld != null && mc.thePlayer.ticksExisted > 0;
    }
}