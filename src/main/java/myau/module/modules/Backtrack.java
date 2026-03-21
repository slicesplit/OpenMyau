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
import myau.utility.backtrack.TimedPacket;
import myau.utility.render.Animation;
import myau.utility.render.Easing;
import myau.util.PacketUtil;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.*;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

@ModuleInfo(category = ModuleCategory.COMBAT)
public class Backtrack extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    // ── settings (exact Raven A+ settings) ───────────────────────────────────
    public final FloatProperty minLatency = new FloatProperty("Min latency", 50f, 10f, 1000f);
    public final FloatProperty maxLatency = new FloatProperty("Max latency", 100f, 10f, 1000f);
    public final FloatProperty minDistance = new FloatProperty("Min distance", 0.0f, 0.0f, 3.0f);
    public final FloatProperty maxDistance = new FloatProperty("Max distance", 6.0f, 0.0f, 10.0f);
    public final FloatProperty stopOnTargetHurtTime = new FloatProperty("Stop on target HurtTime", -1f, -1f, 10f);
    public final FloatProperty stopOnSelfHurtTime = new FloatProperty("Stop on self HurtTime", -1f, -1f, 10f);
    public final BooleanProperty drawRealPosition = new BooleanProperty("Draw real position", true);

    // ── state (exact Raven A+ state) ──────────────────────────────────────────
    private final Queue<TimedPacket> packetQueue = new ConcurrentLinkedQueue<>();
    private final List<Packet<?>> skipPackets = new ArrayList<>();

    private Animation animationX;
    private Animation animationY;
    private Animation animationZ;

    private Vec3 vec3 = null;
    private EntityPlayer target = null;
    private int currentLatency = 0;

    public Backtrack() {
        super("Backtrack", false);
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onEnabled() {
        packetQueue.clear();
        skipPackets.clear();
        vec3 = null;
        target = null;
        currentLatency = 0;
        animationX = null;
        animationY = null;
        animationZ = null;
    }

    @Override
    public void onDisabled() {
        releaseAll();
        target = null;
        vec3 = null;
        currentLatency = 0;
    }

    // ── suffix ────────────────────────────────────────────────────────────────

    @Override
    public String[] getSuffix() {
        return new String[]{
                (currentLatency == 0 ? maxLatency.getValue().intValue() : currentLatency) + "ms"
        };
    }

    // ── tick ─────────────────────────────────────────────────────────────────

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE) return;
        if (mc.thePlayer == null) return;

        // Mirrors Raven's onPreUpdate distance check
        if (vec3 != null) {
            double distance = distanceTo(vec3, mc.thePlayer);
            if (distance > maxDistance.getValue().doubleValue()
                    || distance < minDistance.getValue().doubleValue()) {
                currentLatency = 0;
            }
        }

        // Drain expired packets — mirrors Raven's onPreTick
        while (!packetQueue.isEmpty()) {
            try {
                if (packetQueue.element().getCold().getCum(currentLatency)) {
                    Packet<?> packet = packetQueue.remove().getPacket();
                    skipPackets.add(packet);
                    PacketUtil.processIncomingPacket(packet);
                } else {
                    break;
                }
            } catch (NullPointerException ignored) {
            }
        }

        // Update vec3 to target's current position when queue is empty
        if (packetQueue.isEmpty() && target != null) {
            vec3 = new Vec3(target.posX, target.posY, target.posZ);
        }
    }

    // ── render ────────────────────────────────────────────────────────────────

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (target == null || vec3 == null || target.isDead) return;

        Vec3 pos = currentLatency > 0 ? vec3 : target.getPositionVector();

        if (animationX == null || animationY == null || animationZ == null) {
            animationX = new Animation(Easing.EASE_OUT_CIRC, 300);
            animationY = new Animation(Easing.EASE_OUT_CIRC, 300);
            animationZ = new Animation(Easing.EASE_OUT_CIRC, 300);
            animationX.setValue(pos.xCoord);
            animationY.setValue(pos.yCoord);
            animationZ.setValue(pos.zCoord);
        }

        animationX.run(pos.xCoord);
        animationY.run(pos.yCoord);
        animationZ.run(pos.zCoord);

        if (drawRealPosition.getValue()) {
            Vec3 animated = new Vec3(
                    animationX.getValue(),
                    animationY.getValue(),
                    animationZ.getValue()
            );
            Blink.drawBox(animated);
        }
    }

    // ── attack ────────────────────────────────────────────────────────────────

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!(event.getTarget() instanceof EntityPlayer)) return;
        EntityPlayer attacked = (EntityPlayer) event.getTarget();
        if (TeamUtil.isBot(attacked) || TeamUtil.isFriend(attacked)) return;

        Vec3 targetPos = new Vec3(attacked.posX, attacked.posY, attacked.posZ);

        if (target == null || attacked != target) {
            vec3 = targetPos;
            if (animationX != null && animationY != null && animationZ != null) {
                long duration = target == null ? 0 :
                        Math.min(500, Math.max(100,
                                (long) (distanceTo(targetPos, target) * 50)));
                animationX.setDuration(duration);
                animationY.setDuration(duration);
                animationZ.setDuration(duration);
            }
        } else {
            if (animationX != null && animationY != null && animationZ != null) {
                animationX.setDuration(100);
                animationY.setDuration(100);
                animationZ.setDuration(100);
            }
        }

        target = attacked;

        if (vec3 != null) {
            double distance = distanceTo(vec3, mc.thePlayer);
            if (distance > maxDistance.getValue().doubleValue()
                    || distance < minDistance.getValue().doubleValue()) return;
        }

        currentLatency = (int) (Math.random()
                * (maxLatency.getValue().doubleValue() - minLatency.getValue().doubleValue())
                + minLatency.getValue().doubleValue());
    }

    // ── packet hook ───────────────────────────────────────────────────────────

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (event.getType() != EventType.RECEIVE) return;
        if (mc.thePlayer == null || mc.thePlayer.ticksExisted < 20) {
            packetQueue.clear();
            return;
        }

        Packet<?> p = event.getPacket();

        if (skipPackets.contains(p)) {
            skipPackets.remove(p);
            return;
        }

        if (target != null
                && stopOnTargetHurtTime.getValue().intValue() != -1
                && target.hurtTime == stopOnTargetHurtTime.getValue().intValue()) {
            releaseAll();
            return;
        }
        if (stopOnSelfHurtTime.getValue().intValue() != -1
                && mc.thePlayer.hurtTime == stopOnSelfHurtTime.getValue().intValue()) {
            releaseAll();
            return;
        }

        if (event.isCancelled()) return;
        if (target == null) {
            releaseAll();
            return;
        }

        if (p instanceof S19PacketEntityStatus
                || p instanceof S02PacketChat
                || p instanceof S0BPacketAnimation
                || p instanceof S06PacketUpdateHealth) {
            return;
        }

        if (p instanceof S08PacketPlayerPosLook || p instanceof S40PacketDisconnect) {
            releaseAll();
            target = null;
            vec3 = null;
            return;
        }

        if (p instanceof S13PacketDestroyEntities) {
            S13PacketDestroyEntities destroy = (S13PacketDestroyEntities) p;
            for (int id : destroy.getEntityIDs()) {
                if (id == target.getEntityId()) {
                    target = null;
                    vec3 = null;
                    releaseAll();
                    return;
                }
            }
        }

        if (p instanceof S14PacketEntity) {
            S14PacketEntity wrapper = (S14PacketEntity) p;
            if (wrapper.getEntity(mc.theWorld) == target && vec3 != null) {
                vec3 = new Vec3(
                        vec3.xCoord + wrapper.func_149062_c() / 32.0,
                        vec3.yCoord + wrapper.func_149061_d() / 32.0,
                        vec3.zCoord + wrapper.func_149064_e() / 32.0
                );
            }
        } else if (p instanceof S18PacketEntityTeleport) {
            S18PacketEntityTeleport wrapper = (S18PacketEntityTeleport) p;
            if (wrapper.getEntityId() == target.getEntityId()) {
                vec3 = new Vec3(
                        wrapper.getX() / 32.0,
                        wrapper.getY() / 32.0,
                        wrapper.getZ() / 32.0
                );
            }
        }

        packetQueue.add(new TimedPacket(p));
        event.setCancelled(true);
    }

    // ── world load ────────────────────────────────────────────────────────────

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        releaseAll();
        target = null;
        vec3 = null;
        currentLatency = 0;
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private void releaseAll() {
        if (!packetQueue.isEmpty()) {
            for (TimedPacket timedPacket : packetQueue) {
                Packet<?> packet = timedPacket.getPacket();
                skipPackets.add(packet);
                PacketUtil.processIncomingPacket(packet);
            }
            packetQueue.clear();
        }
    }

    private static double distanceTo(Vec3 from, net.minecraft.entity.Entity entity) {
        double dx = from.xCoord - entity.posX;
        double dy = from.yCoord - entity.posY;
        double dz = from.zCoord - entity.posZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}