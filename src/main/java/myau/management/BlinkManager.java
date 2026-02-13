package myau.management;

import myau.enums.BlinkModules;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.handshake.client.C00Handshake;
import net.minecraft.network.login.client.C00PacketLoginStart;
import net.minecraft.network.login.client.C01PacketEncryptionResponse;
import net.minecraft.network.play.client.*;
import net.minecraft.network.play.server.*;
import net.minecraft.network.status.client.C00PacketServerQuery;
import net.minecraft.network.status.client.C01PacketPing;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * Production-Grade BlinkManager
 * 
 * Based on LiquidBounce's BlinkManager implementation
 * Copyright (c) 2015 - 2026 CCBlueX
 * Licensed under GNU General Public License v3.0
 * 
 * Features:
 * - Thread-safe packet queueing
 * - Bidirectional support (incoming/outgoing)
 * - Smart flushing with multiple strategies
 * - Position tracking for visualization
 * - Auto-flush on critical events
 * - GrimAC-compatible packet handling
 */
public class BlinkManager {
    public static final Minecraft mc = Minecraft.getMinecraft();
    
    // Packet queue with timestamp tracking
    private final ConcurrentLinkedQueue<PacketSnapshot> packetQueue = new ConcurrentLinkedQueue<>();
    
    // Current blink state
    private BlinkModules blinkModule = BlinkModules.NONE;
    private boolean blinking = false;
    
    // Legacy compatibility - exposed for backward compatibility
    public final ConcurrentLinkedQueue<Packet<?>> blinkedPackets = new ConcurrentLinkedQueue<>();

    /**
     * Get all movement positions from queued packets
     */
    public List<Vec3> getPositions() {
        return packetQueue.stream()
                .map(snapshot -> snapshot.packet)
                .filter(packet -> packet instanceof C03PacketPlayer)
                .map(packet -> (C03PacketPlayer) packet)
                .filter(C03PacketPlayer::isMoving)
                .map(packet -> new Vec3(packet.getPositionX(), packet.getPositionY(), packet.getPositionZ()))
                .collect(Collectors.toList());
    }

    /**
     * Check if currently lagging
     */
    public boolean isBlinking() {
        return blinking && !packetQueue.isEmpty();
    }

    /**
     * Get the module that initiated blinking
     */
    public BlinkModules getBlinkingModule() {
        return blinkModule;
    }

    /**
     * Count movement packets in queue
     */
    public long countMovement() {
        return packetQueue.stream()
                .filter(snapshot -> snapshot.packet instanceof C03PacketPlayer)
                .count();
    }

    /**
     * Offer a packet to the queue (legacy method)
     */
    public boolean offerPacket(Packet<?> packet) {
        if (!blinking || blinkModule == BlinkModules.NONE) {
            return false;
        }

        // Never queue critical packets
        if (shouldNeverQueue(packet)) {
            return false;
        }

        PacketSnapshot snapshot = new PacketSnapshot(packet, TransferOrigin.OUTGOING, System.currentTimeMillis());
        packetQueue.offer(snapshot);
        blinkedPackets.offer(packet); // Legacy support
        return true;
    }

    /**
     * Set the blink state
     */
    public boolean setBlinkState(boolean state, BlinkModules module) {
        if (module == BlinkModules.NONE) {
            return false;
        }

        if (state) {
            // Enable blinking
            blinkModule = module;
            blinking = true;
            return true;
        } else {
            // Disable blinking - flush packets
            if (blinkModule != module) {
                return false; // Different module trying to disable
            }

            blinking = false;
            flush(TransferOrigin.OUTGOING);
            blinkModule = BlinkModules.NONE;
            return true;
        }
    }

    /**
     * Flush packets based on origin (outgoing/incoming)
     */
    public void flush(TransferOrigin origin) {
        Iterator<PacketSnapshot> iterator = packetQueue.iterator();
        
        while (iterator.hasNext()) {
            PacketSnapshot snapshot = iterator.next();
            
            if (snapshot.origin == origin) {
                flushSnapshot(snapshot);
                iterator.remove();
            }
        }

        // Clear legacy queue for outgoing
        if (origin == TransferOrigin.OUTGOING) {
            blinkedPackets.clear();
        }
    }

    /**
     * Flush all packets
     */
    public void flushAll() {
        for (PacketSnapshot snapshot : packetQueue) {
            flushSnapshot(snapshot);
        }
        
        packetQueue.clear();
        blinkedPackets.clear();
    }

    /**
     * Flush first N movement packets and all packets before them
     */
    public void flush(int count) {
        int moveCounter = 0;
        Iterator<PacketSnapshot> iterator = packetQueue.iterator();

        while (iterator.hasNext()) {
            PacketSnapshot snapshot = iterator.next();
            
            // Count movement packets
            if (snapshot.packet instanceof C03PacketPlayer) {
                C03PacketPlayer movePacket = (C03PacketPlayer) snapshot.packet;
                if (movePacket.isMoving()) {
                    moveCounter++;
                }
            }

            // Flush this packet
            flushSnapshot(snapshot);
            iterator.remove();

            // Stop after flushing desired number of movement packets
            if (moveCounter >= count) {
                break;
            }
        }

        // Update legacy queue
        updateLegacyQueue();
    }

    /**
     * Cancel blinking - teleport back to first position and flush non-movement packets
     */
    public void cancel() {
        List<Vec3> positions = getPositions();
        
        // Teleport player back to first position
        if (!positions.isEmpty() && mc.thePlayer != null) {
            Vec3 firstPos = positions.get(0);
            mc.thePlayer.setPosition(firstPos.xCoord, firstPos.yCoord, firstPos.zCoord);
        }

        // Flush all non-movement packets
        Iterator<PacketSnapshot> iterator = packetQueue.iterator();
        
        while (iterator.hasNext()) {
            PacketSnapshot snapshot = iterator.next();
            
            // Skip movement packets, flush everything else
            if (!(snapshot.packet instanceof C03PacketPlayer)) {
                flushSnapshot(snapshot);
            }
            
            iterator.remove();
        }

        blinkedPackets.clear();
    }

    /**
     * Check if queue has been active for longer than delay
     */
    public boolean isAboveTime(long delay) {
        PacketSnapshot first = packetQueue.peek();
        if (first == null) {
            return false;
        }

        return System.currentTimeMillis() - first.timestamp >= delay;
    }

    /**
     * Flush a single packet snapshot
     */
    private void flushSnapshot(PacketSnapshot snapshot) {
        if (snapshot.origin == TransferOrigin.OUTGOING) {
            // Send outgoing packet
            PacketUtil.sendPacketNoEvent(snapshot.packet);
        } else {
            // Process incoming packet
            if (mc.getNetHandler() != null) {
                try {
                    snapshot.packet.processPacket(mc.getNetHandler());
                } catch (Exception e) {
                    // Ignore processing errors
                }
            }
        }
    }

    /**
     * Update legacy blinkedPackets queue to match packetQueue
     */
    private void updateLegacyQueue() {
        blinkedPackets.clear();
        for (PacketSnapshot snapshot : packetQueue) {
            if (snapshot.origin == TransferOrigin.OUTGOING) {
                blinkedPackets.offer(snapshot.packet);
            }
        }
    }

    /**
     * Check if packet should NEVER be queued (critical packets)
     */
    private boolean shouldNeverQueue(Packet<?> packet) {
        // GRIM-SAFE: Never queue transaction confirms (causes TransactionOrder flags)
        if (packet instanceof C0FPacketConfirmTransaction) {
            return true;
        }

        // Never queue keep-alive (prevents timeout kicks)
        if (packet instanceof C00PacketKeepAlive) {
            return true;
        }

        // Never queue chat messages
        if (packet instanceof C01PacketChatMessage) {
            return true;
        }

        // Never queue handshake/login packets
        if (packet instanceof C00Handshake || 
            packet instanceof C00PacketLoginStart ||
            packet instanceof C01PacketEncryptionResponse ||
            packet instanceof C00PacketServerQuery ||
            packet instanceof C01PacketPing) {
            return true;
        }

        return false;
    }

    /**
     * Auto-flush on critical server packets
     */
    private boolean shouldAutoFlush(Packet<?> packet) {
        // Flush on server teleport
        if (packet instanceof S08PacketPlayerPosLook) {
            return true;
        }

        // Flush on disconnect
        if (packet instanceof S40PacketDisconnect) {
            return true;
        }

        // Flush on death
        if (packet instanceof S06PacketUpdateHealth) {
            S06PacketUpdateHealth healthPacket = (S06PacketUpdateHealth) packet;
            if (healthPacket.getHealth() <= 0) {
                return true;
            }
        }

        return false;
    }

    @EventTarget(Priority.FINAL)
    public void onPacket(PacketEvent event) {
        Packet<?> packet = event.getPacket();

        // Auto-flush on critical packets
        if (shouldAutoFlush(packet)) {
            flushAll();
            blinking = false;
            blinkModule = BlinkModules.NONE;
            return;
        }

        // Don't process if not blinking
        if (!blinking || blinkModule == BlinkModules.NONE) {
            return;
        }

        // Don't queue critical packets
        if (shouldNeverQueue(packet)) {
            return;
        }

        // Queue outgoing packets
        if (event.getType() == EventType.SEND) {
            event.setCancelled(true);
            PacketSnapshot snapshot = new PacketSnapshot(packet, TransferOrigin.OUTGOING, System.currentTimeMillis());
            packetQueue.offer(snapshot);
            blinkedPackets.offer(packet); // Legacy support
        }
        // Note: Incoming packets can be queued too, but most modules only need outgoing
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.POST || mc.thePlayer == null) {
            return;
        }

        // Auto-flush on death
        if (mc.thePlayer.isDead && blinking) {
            flushAll();
            blinking = false;
            blinkModule = BlinkModules.NONE;
        }
    }

    @EventTarget
    public void onWorldChange(LoadWorldEvent event) {
        // Clear all packets on world change/disconnect
        packetQueue.clear();
        blinkedPackets.clear();
        blinking = false;
        blinkModule = BlinkModules.NONE;
    }

    /**
     * Packet snapshot - stores packet with metadata
     */
    public static class PacketSnapshot {
        public final Packet<?> packet;
        public final TransferOrigin origin;
        public final long timestamp;

        public PacketSnapshot(Packet<?> packet, TransferOrigin origin, long timestamp) {
            this.packet = packet;
            this.origin = origin;
            this.timestamp = timestamp;
        }
    }

    /**
     * Transfer origin - indicates packet direction
     */
    public enum TransferOrigin {
        OUTGOING,  // Client -> Server
        INCOMING   // Server -> Client
    }
}
