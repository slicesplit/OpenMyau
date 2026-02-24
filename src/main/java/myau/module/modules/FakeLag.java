package myau.module.modules;

import myau.enums.ModuleCategory;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.AttackEvent;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.module.ModuleInfo;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.util.PacketUtil;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.handshake.client.C00Handshake;
import net.minecraft.network.play.client.*;
import net.minecraft.network.play.server.*;
import net.minecraft.network.status.client.C00PacketServerQuery;
import net.minecraft.network.status.client.C01PacketPing;
import net.minecraft.network.status.server.S01PacketPong;

import java.util.ArrayDeque;
import java.util.Queue;

@ModuleInfo(category = ModuleCategory.COMBAT)
public class FakeLag extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    public final IntProperty delay = new IntProperty("Delay", 550, 0, 1000);
    public final IntProperty recoilTime = new IntProperty("RecoilTime", 750, 0, 2000);
    public final BooleanProperty blinkOnAction = new BooleanProperty("BlinkOnAction", true);
    public final BooleanProperty pauseOnNoMove = new BooleanProperty("PauseOnNoMove", true);

    private static final class QueueData {
        final Packet<?> packet;
        final long timestamp;
        QueueData(Packet<?> p, long t) { packet = p; timestamp = t; }
    }

    private final Queue<QueueData> packetQueue = new ArrayDeque<>();
    private long recoilUntil = 0;
    private boolean ignoreWholeTick = false;

    public FakeLag() {
        super("FakeLag", false);
    }

    @Override
    public void onEnabled() {
        packetQueue.clear();
        recoilUntil = 0;
        ignoreWholeTick = false;
    }

    @Override
    public void onDisabled() {
        flush(true);
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE || mc.thePlayer == null) return;

        if (mc.thePlayer.isDead || mc.thePlayer.getHealth() <= 0) {
            flush(true);
            ignoreWholeTick = true;
            return;
        }

        if (((myau.mixin.IAccessorMinecraft)(Object)mc).isIntegratedServerRunning() || ((myau.mixin.IAccessorMinecraft)(Object)mc).getCurrentServerData() == null) {
            flush(true);
            ignoreWholeTick = true;
            return;
        }

        if (System.currentTimeMillis() >= recoilUntil) {
            handlePackets(false);
        }

        ignoreWholeTick = false;
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (mc.thePlayer == null || ignoreWholeTick) return;
        if (event.getType() != EventType.SEND) return;

        Packet<?> packet = event.getPacket();

        // Never queue these
        if (packet instanceof C00Handshake
                || packet instanceof C00PacketServerQuery
                || packet instanceof C01PacketPing
                || packet instanceof C01PacketChatMessage
                || packet instanceof S01PacketPong) {
            return;
        }

        // Flush on inventory actions
        if (packet instanceof C0EPacketClickWindow || packet instanceof C0DPacketCloseWindow) {
            flush(true);
            return;
        }

        // Flush on block placement, digging, sign, resource pack
        if (packet instanceof C08PacketPlayerBlockPlacement
                || packet instanceof C07PacketPlayerDigging
                || packet instanceof C12PacketUpdateSign
                || packet instanceof C19PacketResourcePackStatus) {
            flush(true);
            return;
        }

        // Flush on attack if blinkOnAction is enabled (skip friends/bots)
        if (blinkOnAction.getValue() && packet instanceof C02PacketUseEntity) {
            C02PacketUseEntity useEntity = (C02PacketUseEntity) packet;
            if (mc.theWorld != null) {
                net.minecraft.entity.Entity attacked = useEntity.getEntityFromWorld(mc.theWorld);
                if (attacked instanceof EntityPlayer) {
                    EntityPlayer attackedPlayer = (EntityPlayer) attacked;
                    if (TeamUtil.isFriend(attackedPlayer) || TeamUtil.isBot(attackedPlayer)) return;
                }
            }
            flush(true);
            return;
        }

        // Flush if not moving and pauseOnNoMove is enabled
        if (pauseOnNoMove.getValue() && packet instanceof C03PacketPlayer) {
            C03PacketPlayer move = (C03PacketPlayer) packet;
            if (!move.isMoving()) {
                flush(true);
                return;
            }
        }

        if (mc.thePlayer.isDead || mc.thePlayer.getHealth() <= 0) {
            return;
        }

        if (System.currentTimeMillis() < recoilUntil) return;

        if (((myau.mixin.IAccessorMinecraft)(Object)mc).isIntegratedServerRunning() || ((myau.mixin.IAccessorMinecraft)(Object)mc).getCurrentServerData() == null) return;

        event.setCancelled(true);
        synchronized (packetQueue) {
            packetQueue.add(new QueueData(packet, System.currentTimeMillis()));
        }
    }

    @EventTarget
    public void onPacketReceive(PacketEvent event) {
        if (event.getType() != EventType.RECEIVE) return;
        Packet<?> packet = event.getPacket();

        // Flush on server position correction
        if (packet instanceof S08PacketPlayerPosLook) {
            flush(true);
            return;
        }

        // Flush on velocity (knockback)
        if (packet instanceof S12PacketEntityVelocity) {
            S12PacketEntityVelocity vel = (S12PacketEntityVelocity) packet;
            if (mc.thePlayer != null && mc.thePlayer.getEntityId() == vel.getEntityID()) {
                flush(true);
            }
            return;
        }

        // Flush on explosion knockback
        if (packet instanceof S27PacketExplosion) {
            S27PacketExplosion exp = (S27PacketExplosion) packet;
            if (exp.func_149149_c() != 0f || exp.func_149144_d() != 0f || exp.func_149147_e() != 0f) {
                flush(true);
            }
        }
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        // Skip flush if attacking a friend or bot
        if (event.getTarget() instanceof EntityPlayer) {
            EntityPlayer target = (EntityPlayer) event.getTarget();
            if (TeamUtil.isFriend(target) || TeamUtil.isBot(target)) return;
        }
        // Flush on attack if blinkOnAction enabled
        if (blinkOnAction.getValue()) {
            flush(true);
        }
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        flush(false);
    }

    private void flush(boolean resetRecoil) {
        if (resetRecoil) {
            recoilUntil = System.currentTimeMillis() + recoilTime.getValue();
        }
        handlePackets(true);
        ignoreWholeTick = true;
    }

    private void handlePackets(boolean clear) {
        synchronized (packetQueue) {
            long threshold = System.currentTimeMillis() - delay.getValue();
            java.util.Iterator<QueueData> it = packetQueue.iterator();
            while (it.hasNext()) {
                QueueData qd = it.next();
                if (clear || qd.timestamp <= threshold) {
                    PacketUtil.sendPacketSafe(qd.packet);
                    it.remove();
                }
            }
        }
    }

    @Override
    public String[] getSuffix() {
        int size;
        synchronized (packetQueue) { size = packetQueue.size(); }
        return new String[]{String.valueOf(size)};
    }
}
