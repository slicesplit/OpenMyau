package myau.module.modules;

import myau.Myau;
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
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.handshake.client.C00Handshake;
import net.minecraft.network.play.client.*;
import net.minecraft.network.play.server.*;
import net.minecraft.network.status.client.C00PacketServerQuery;
import net.minecraft.network.status.client.C01PacketPing;
import net.minecraft.network.status.server.S01PacketPong;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * FakeLag — clean port of fdpclient's FakeLag logic.
 *
 * Core behaviour:
 *   • Queues all non-critical outgoing packets up to `delay` ms.
 *   • Flushes (blinks) the queue on: attack, knockback, PosLook correction,
 *     inventory interaction, block placement/digging, scaffold, no-movement,
 *     item use, chest open, world change, or disable.
 *   • After a flush, enters a `recoilTime` ms window during which nothing is
 *     queued (packets pass through freely) — mirrors fdpclient exactly.
 *   • Cap of 40 queued C03s before force-flushing oldest half to prevent
 *     Grim timer accumulation.
 */
@ModuleInfo(category = ModuleCategory.COMBAT)
public class FakeLag extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    public final IntProperty     delay          = new IntProperty("Delay",       550,  0, 1000);
    public final IntProperty     recoilTime     = new IntProperty("RecoilTime",  750,  0, 2000);
    public final BooleanProperty blinkOnAction  = new BooleanProperty("BlinkOnAction",  true);
    public final BooleanProperty pauseOnNoMove  = new BooleanProperty("PauseOnNoMove",  true);
    public final BooleanProperty pauseOnScaffold= new BooleanProperty("PauseOnScaffold",true);
    public final BooleanProperty pauseOnChest   = new BooleanProperty("PauseOnChest",   false);

    // ── internal queue ──────────────────────────────────────────────────────

    private static final class QueueData {
        final Packet<?> packet;
        final long      timestamp;
        QueueData(Packet<?> p, long t) { packet = p; timestamp = t; }
    }

    private final ArrayDeque<QueueData> packetQueue   = new ArrayDeque<>();
    private long    recoilUntil    = 0;
    private boolean ignoreThisTick = false;

    // ── public API for other modules ────────────────────────────────────────

    /** True while FakeLag is holding packets in its queue. */
    public boolean isLagging() {
        synchronized (packetQueue) { return isEnabled() && !packetQueue.isEmpty(); }
    }

    /** True during the post-flush recoil window. */
    public boolean isInRecoil() {
        return isEnabled() && System.currentTimeMillis() < recoilUntil;
    }

    /** True when actively queuing or in recoil. */
    public boolean isActive() {
        return isEnabled() && (isLagging() || isInRecoil());
    }

    /** Hard flush — called by Backtrack / external consumers. */
    public void forceFlush() {
        blink();
    }

    // ── lifecycle ───────────────────────────────────────────────────────────

    public FakeLag() {
        super("FakeLag", false);
    }

    @Override
    public void onEnabled() {
        synchronized (packetQueue) { packetQueue.clear(); }
        recoilUntil    = 0;
        ignoreThisTick = false;
    }

    @Override
    public void onDisabled() {
        blink();
    }

    // ── tick ────────────────────────────────────────────────────────────────

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE || mc.thePlayer == null) return;

        // Hard stops — always flush and skip this tick
        if (mc.thePlayer.isDead || mc.thePlayer.getHealth() <= 0) {
            blink(); return;
        }
        if (isSingleplayer()) {
            blink(); return;
        }

        // Scaffold pause
        if (pauseOnScaffold.getValue()) {
            Scaffold scaffold = (Scaffold) Myau.moduleManager.modules.get(Scaffold.class);
            if (scaffold != null && scaffold.isEnabled()) {
                blink(); return;
            }
        }

        // Chest / inventory GUI open
        if (pauseOnChest.getValue() && mc.currentScreen instanceof GuiContainer) {
            blink(); return;
        }

        // Item use (eating, blocking)
        if (mc.thePlayer.isUsingItem()) {
            blink(); return;
        }

        // In recoil window — pass everything through, queue nothing
        if (System.currentTimeMillis() < recoilUntil) {
            ignoreThisTick = true;
            return;
        }

        // Normal drain — release packets older than `delay`
        ignoreThisTick = false;
        handlePackets(false);
    }

    // ── outgoing packet hook ─────────────────────────────────────────────────

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (event.getType() != EventType.SEND) return;
        if (mc.thePlayer == null) return;

        Packet<?> packet = event.getPacket();

        // Always pass through — protocol-critical or non-gameplay packets
        if (isPassThrough(packet)) return;

        // Dead / singleplayer — don't queue
        if (mc.thePlayer.isDead || mc.thePlayer.getHealth() <= 0) return;
        if (isSingleplayer()) return;

        // In recoil — pass through freely
        if (System.currentTimeMillis() < recoilUntil) return;

        // ignoreThisTick set by tick handler (scaffold, chest, item use, etc.)
        if (ignoreThisTick) return;

        // ── Flush triggers ───────────────────────────────────────────────────

        // Inventory / window interactions
        if (packet instanceof C0EPacketClickWindow || packet instanceof C0DPacketCloseWindow) {
            blink(); return;
        }

        // Block placement, digging, sign, resource pack
        if (packet instanceof C08PacketPlayerBlockPlacement
                || packet instanceof C07PacketPlayerDigging
                || packet instanceof C12PacketUpdateSign
                || packet instanceof C19PacketResourcePackStatus) {
            blink(); return;
        }

        // Attack → flush so position packets reach server before the C02
        if (blinkOnAction.getValue() && packet instanceof C02PacketUseEntity) {
            if (mc.theWorld != null) {
                net.minecraft.entity.Entity attacked =
                        ((C02PacketUseEntity) packet).getEntityFromWorld(mc.theWorld);
                if (attacked instanceof EntityPlayer) {
                    EntityPlayer ap = (EntityPlayer) attacked;
                    if (TeamUtil.isFriend(ap) || TeamUtil.isBot(ap)) return;
                }
            }
            blink(); return;
        }

        // PauseOnNoMove — flush when the player is genuinely stationary.
        // Don't flush during a jump (motionY > 0.1) or fall (motionY < -0.1)
        // as that would reset the server's vertical motion and cause rubberbanding.
        if (pauseOnNoMove.getValue() && packet instanceof C03PacketPlayer) {
            C03PacketPlayer move = (C03PacketPlayer) packet;
            if (!move.isMoving()) {
                boolean jumping = !move.isOnGround() && mc.thePlayer.motionY > 0.1;
                boolean falling = mc.thePlayer.motionY < -0.1;
                boolean still   = move.isOnGround() && Math.abs(mc.thePlayer.motionY) < 0.01;
                if (still && !jumping && !falling) {
                    blink(); return;
                }
            }
        }

        // ── Queue the packet ──────────────────────────────────────────────────

        event.setCancelled(true);
        synchronized (packetQueue) {
            // Cap at 40 queued C03s to prevent Grim timer accumulation.
            // If we're at the cap, flush the oldest half before queuing more.
            if (packet instanceof C03PacketPlayer) {
                int c03Count = 0;
                for (QueueData qd : packetQueue) {
                    if (qd.packet instanceof C03PacketPlayer) c03Count++;
                }
                if (c03Count >= 40) {
                    Iterator<QueueData> it = packetQueue.iterator();
                    while (it.hasNext() && c03Count >= 20) {
                        QueueData qd = it.next();
                        if (qd.packet instanceof C03PacketPlayer) {
                            PacketUtil.sendPacketSafe(qd.packet);
                            it.remove();
                            c03Count--;
                        }
                    }
                }
            }
            packetQueue.add(new QueueData(packet, System.currentTimeMillis()));
        }
    }

    // ── incoming packet hook (knockback / PosLook) ───────────────────────────

    @EventTarget
    public void onPacketReceive(PacketEvent event) {
        if (event.getType() != EventType.RECEIVE) return;
        Packet<?> packet = event.getPacket();

        // Server position correction
        if (packet instanceof S08PacketPlayerPosLook) {
            blink(); return;
        }

        // Knockback from entity velocity
        if (packet instanceof S12PacketEntityVelocity) {
            S12PacketEntityVelocity vel = (S12PacketEntityVelocity) packet;
            if (mc.thePlayer != null && mc.thePlayer.getEntityId() == vel.getEntityID()) {
                blink();
            }
            return;
        }

        // Explosion knockback
        if (packet instanceof S27PacketExplosion) {
            S27PacketExplosion exp = (S27PacketExplosion) packet;
            if (exp.func_149149_c() != 0f || exp.func_149144_d() != 0f || exp.func_149147_e() != 0f) {
                blink();
            }
        }
    }

    // ── attack event (fallback for non-C02-routed hits) ──────────────────────

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!blinkOnAction.getValue()) return;
        if (event.getTarget() instanceof EntityPlayer) {
            EntityPlayer target = (EntityPlayer) event.getTarget();
            if (TeamUtil.isFriend(target) || TeamUtil.isBot(target)) return;
        }
        // Only flush if the C02 path didn't already flush (queue non-empty)
        synchronized (packetQueue) {
            if (!packetQueue.isEmpty()) blink();
        }
    }

    // ── world / disconnect ───────────────────────────────────────────────────

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        // On disconnect (null world) drop the queue without recoil
        synchronized (packetQueue) { packetQueue.clear(); }
        recoilUntil    = 0;
        ignoreThisTick = false;
    }

    // ── suffix ───────────────────────────────────────────────────────────────

    @Override
    public String[] getSuffix() {
        int size;
        synchronized (packetQueue) { size = packetQueue.size(); }
        return new String[]{String.valueOf(size)};
    }

    // ── internals ────────────────────────────────────────────────────────────

    /**
     * Flush all queued packets and enter recoil window.
     * Mirrors fdpclient's `blink()` — simple, no budget math.
     */
    private void blink() {
        recoilUntil    = System.currentTimeMillis() + recoilTime.getValue();
        ignoreThisTick = true;
        handlePackets(true);
    }

    /**
     * Drain packets from the queue.
     *
     * @param clear true  → send everything immediately (flush/blink)
     *              false → send only packets older than `delay` ms (normal drain)
     *
     * Packet ordering: non-C03 packets (e.g. C02 attack) are sent after all C03
     * position packets so Grim sees the position before the attack — same ordering
     * fdpclient uses via its ArrayDeque (insertion order preserved).
     */
    private void handlePackets(boolean clear) {
        synchronized (packetQueue) {
            long threshold = System.currentTimeMillis() - delay.getValue();
            Iterator<QueueData> it = packetQueue.iterator();
            while (it.hasNext()) {
                QueueData qd = it.next();
                if (clear || qd.timestamp <= threshold) {
                    PacketUtil.sendPacketSafe(qd.packet);
                    it.remove();
                }
            }
        }
    }

    /**
     * Packets that always pass through — never queued or dropped.
     * Matches fdpclient's pass-through list exactly.
     */
    private static boolean isPassThrough(Packet<?> p) {
        return p instanceof C00Handshake
            || p instanceof C00PacketServerQuery
            || p instanceof C01PacketPing
            || p instanceof C01PacketChatMessage
            || p instanceof S01PacketPong
            || p instanceof C00PacketKeepAlive
            || p instanceof C0FPacketConfirmTransaction;
    }

    private boolean isSingleplayer() {
        return ((myau.mixin.IAccessorMinecraft)(Object)mc).isIntegratedServerRunning()
            || ((myau.mixin.IAccessorMinecraft)(Object)mc).getCurrentServerData() == null;
    }
}
