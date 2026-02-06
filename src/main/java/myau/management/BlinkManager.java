package myau.management;

import myau.enums.BlinkModules;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.handshake.client.C00Handshake;
import net.minecraft.network.login.client.C00PacketLoginStart;
import net.minecraft.network.login.client.C01PacketEncryptionResponse;
import net.minecraft.network.play.client.C00PacketKeepAlive;
import net.minecraft.network.play.client.C01PacketChatMessage;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C0FPacketConfirmTransaction;
import net.minecraft.network.status.client.C00PacketServerQuery;
import net.minecraft.network.status.client.C01PacketPing;

import java.util.Deque;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * BlinkManager - Handles packet blinking with Grim AC bypass
 * 
 * GRIM BYPASS FEATURES:
 * - Smart transaction handling (prevents BadPacketsM)
 * - Periodic keepalive release (prevents disconnect)
 * - Chunked packet release (prevents rubberband)
 * - Position smoothing (prevents teleport flags)
 */
public class BlinkManager {
    public static Minecraft mc = Minecraft.getMinecraft();
    public BlinkModules blinkModule = BlinkModules.NONE;
    public boolean blinking = false;
    public Deque<Packet<?>> blinkedPackets = new ConcurrentLinkedDeque<>();
    
    // Grim bypass state
    private long lastKeepAliveTime = 0L;
    private long blinkStartTime = 0L;
    private int transactionsSinceLastRelease = 0;
    private static final int MAX_BLINK_TIME = 5000; // 5 seconds max to prevent disconnect
    private static final int KEEPALIVE_INTERVAL = 15000; // Send keepalive every 15 seconds

    public boolean offerPacket(Packet<?> packet) {
        // GRIM BYPASS: Never block critical packets
        if (this.blinkModule == BlinkModules.NONE || packet instanceof C00PacketKeepAlive || packet instanceof C01PacketChatMessage) {
            return false;
        }
        
        // GRIM BYPASS: Smart transaction handling
        if (packet instanceof C0FPacketConfirmTransaction) {
            transactionsSinceLastRelease++;
            
            // Release transactions periodically to prevent BadPacketsM
            // Grim expects transactions to be responded to within a reasonable time
            if (transactionsSinceLastRelease > 10 || this.blinkedPackets.isEmpty()) {
                // Let this transaction through immediately
                transactionsSinceLastRelease = 0;
                return false;
            }
        }
        
        // GRIM BYPASS: Auto-release if blinking for too long
        if (System.currentTimeMillis() - blinkStartTime > MAX_BLINK_TIME) {
            // Force release to prevent disconnect
            releasePacketsChunked();
            return false;
        }
        
        this.blinkedPackets.offer(packet);
        return true;
    }

    public boolean setBlinkState(boolean state, BlinkModules module) {
        if (module == BlinkModules.NONE) {
            return false;
        }
        if (state) {
            this.blinkModule = module;
            this.blinking = true;
            this.blinkStartTime = System.currentTimeMillis();
            this.lastKeepAliveTime = System.currentTimeMillis();
            this.transactionsSinceLastRelease = 0;
        } else {
            if(blinkModule != module){
                return false;
            }
            this.blinking = false;
            if (Minecraft.getMinecraft().getNetHandler() != null && this.blinkedPackets.isEmpty()) {
                this.blinkModule = BlinkModules.NONE;
                return true;
            }
            
            // GRIM BYPASS: Release packets in chunks to prevent rubberband
            releasePacketsChunked();
            
            this.blinkedPackets.clear();
            this.blinkModule = BlinkModules.NONE;
            this.transactionsSinceLastRelease = 0;
        }
        return true;
    }
    
    /**
     * GRIM BYPASS: Release packets in smart chunks to prevent rubberband/teleport
     * Instead of sending all packets at once, we send them in batches with delay
     */
    private void releasePacketsChunked() {
        if (blinkedPackets.isEmpty() || mc.getNetHandler() == null) {
            return;
        }
        
        int packetCount = blinkedPackets.size();
        
        // STRATEGY: Release packets based on count
        if (packetCount <= 20) {
            // Small amount: Send all immediately
            for (Packet<?> packet : blinkedPackets) {
                PacketUtil.sendPacketNoEvent(packet);
            }
        } else if (packetCount <= 100) {
            // Medium amount: Send in 2 batches
            releaseBatch(packetCount / 2);
            new Thread(() -> {
                try {
                    Thread.sleep(50); // 50ms delay
                    releaseBatch(Integer.MAX_VALUE);
                } catch (InterruptedException e) {
                    // Fallback: send remaining immediately
                    while (!blinkedPackets.isEmpty()) {
                        PacketUtil.sendPacketNoEvent(blinkedPackets.poll());
                    }
                }
            }).start();
        } else {
            // Large amount: Send in multiple chunks with position smoothing
            releaseWithSmoothing();
        }
    }
    
    /**
     * Release a specific number of packets from the queue
     */
    private void releaseBatch(int count) {
        int sent = 0;
        while (!blinkedPackets.isEmpty() && sent < count) {
            PacketUtil.sendPacketNoEvent(blinkedPackets.poll());
            sent++;
        }
    }
    
    /**
     * GRIM BYPASS: Release large amounts of packets with position smoothing
     * This prevents teleport flags by gradually sending position updates
     */
    private void releaseWithSmoothing() {
        // Convert to list for processing
        LinkedList<Packet<?>> packets = new LinkedList<>(blinkedPackets);
        blinkedPackets.clear();
        
        // Separate movement packets from others
        LinkedList<C03PacketPlayer> movementPackets = new LinkedList<>();
        LinkedList<Packet<?>> otherPackets = new LinkedList<>();
        
        for (Packet<?> packet : packets) {
            if (packet instanceof C03PacketPlayer) {
                movementPackets.add((C03PacketPlayer) packet);
            } else {
                otherPackets.add(packet);
            }
        }
        
        // Send non-movement packets immediately
        for (Packet<?> packet : otherPackets) {
            PacketUtil.sendPacketNoEvent(packet);
        }
        
        // GRIM BYPASS: Sample movement packets to prevent teleport
        // Instead of sending 200 movement packets, send every 5th packet
        int sampleRate = Math.max(1, movementPackets.size() / 40); // Max 40 movement packets
        
        int index = 0;
        for (C03PacketPlayer packet : movementPackets) {
            if (index % sampleRate == 0 || index == movementPackets.size() - 1) {
                // Send this packet
                PacketUtil.sendPacketNoEvent(packet);
            }
            index++;
        }
        
        // Always send the last movement packet to sync final position
        if (!movementPackets.isEmpty()) {
            PacketUtil.sendPacketNoEvent(movementPackets.getLast());
        }
    }

    public BlinkModules getBlinkingModule() {
        return this.blinkModule;
    }

    public long countMovement() {
        return this.blinkedPackets.stream().filter(packet -> packet instanceof C03PacketPlayer).count();
    }

    public boolean isBlinking() {
        return blinking;
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (event.getPacket() instanceof C00Handshake
                || event.getPacket() instanceof C00PacketLoginStart
                || event.getPacket() instanceof C00PacketServerQuery
                || event.getPacket() instanceof C01PacketPing
                || event.getPacket() instanceof C01PacketEncryptionResponse) {
            this.setBlinkState(false, this.blinkModule);
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() == EventType.POST) {
            if (mc.thePlayer.isDead) {
                this.setBlinkState(false, this.blinkModule);
            }
        }
    }
}
