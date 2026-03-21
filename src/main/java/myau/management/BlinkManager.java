package myau.management;

import myau.enums.BlinkModules;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.handshake.client.C00Handshake;
import net.minecraft.network.login.client.C00PacketLoginStart;
import net.minecraft.network.login.client.C01PacketEncryptionResponse;
import net.minecraft.network.play.client.C01PacketChatMessage;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.status.client.C00PacketServerQuery;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class BlinkManager {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final Queue<Packet<?>> blinkedPackets = new ConcurrentLinkedQueue<>();
    private final Queue<Packet<?>> skipPackets = new ConcurrentLinkedQueue<>();

    private boolean blinking = false;
    private BlinkModules currentModule = BlinkModules.NONE;

    // ── new API ──────────────────────────────────────────────────────────────

    public void enable() {
        blinkedPackets.clear();
        skipPackets.clear();
        blinking = true;
    }

    public void disable() {
        blinking = false;
        flush();
        currentModule = BlinkModules.NONE;
    }

    public boolean isBlinking() {
        return blinking;
    }

    public boolean hasPackets() {
        return !blinkedPackets.isEmpty();
    }

    public int size() {
        return blinkedPackets.size();
    }

    public void flush() {
        synchronized (blinkedPackets) {
            for (Packet<?> packet : blinkedPackets) {
                skipPackets.add(packet);
                PacketUtil.sendPacketNoEvent(packet);
            }
        }
        blinkedPackets.clear();
    }

    public void pulse() {
        flush();
        enable();
    }

    // ── backward-compat API (KillAura, NoFall, HUD) ──────────────────────────

    public boolean setBlinkState(boolean state, BlinkModules module) {
        if (state) {
            currentModule = module;
            enable();
        } else {
            disable();
        }
        return true;
    }

    public BlinkModules getBlinkingModule() {
        return blinking ? currentModule : BlinkModules.NONE;
    }

    public long countMovement() {
        long count = 0;
        for (Packet<?> packet : blinkedPackets) {
            if (packet instanceof C03PacketPlayer) {
                count++;
            }
        }
        return count;
    }

    public boolean offerPacket(Packet<?> packet) {
        if (!blinking || currentModule == BlinkModules.NONE) {
            return false;
        }
        if (shouldNeverQueue(packet)) {
            return false;
        }
        blinkedPackets.offer(packet);
        return true;
    }

    // ── packet event hook ────────────────────────────────────────────────────

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (event.getType() != EventType.SEND) return;
        Packet<?> packet = event.getPacket();

        if (skipPackets.contains(packet)) {
            skipPackets.remove(packet);
            return;
        }

        if (!blinking) return;
        if (mc.thePlayer == null || mc.thePlayer.isDead) {
            disable();
            return;
        }

        if (shouldNeverQueue(packet)) return;
        if (packet.getClass().getSimpleName().startsWith("S")) return;

        blinkedPackets.add(packet);
        event.setCancelled(true);
    }

    @EventTarget
    public void onWorldChange(LoadWorldEvent event) {
        blinkedPackets.clear();
        skipPackets.clear();
        blinking = false;
        currentModule = BlinkModules.NONE;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE) return;
        if (mc.thePlayer == null) return;
        if (blinking && mc.thePlayer.isDead) {
            disable();
        }
    }

    private boolean shouldNeverQueue(Packet<?> packet) {
        return packet instanceof C00Handshake
                || packet instanceof C00PacketLoginStart
                || packet instanceof C00PacketServerQuery
                || packet instanceof C01PacketEncryptionResponse
                || packet instanceof C01PacketChatMessage;
    }
}