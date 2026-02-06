package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C03PacketPlayer;

import java.util.LinkedList;
import java.util.Queue;

/**
 * FakeLag - Simulates lag by adding a delay to the packets you send to the server.
 * 
 * Mode:
 * - Latency: Adds a constant amount of delay to your packets.
 * - Dynamic: Dynamically adjusts your connection speed to give you advantages in combat.
 * - Repel: Tunes FakeLag with the goal of keeping your opponent as far away from you as possible.
 * 
 * Transmission Offset: Higher values may make your connection more unstable.
 * Delay: The amount of delay (in milliseconds) to wait before sending any given packet to the server.
 * 
 * LAGRANGE 1000MS BYPASS:
 * - Accounts for base 1000ms Lagrange latency
 * - Prevents disconnect from stacking delays
 * - Smart packet timing to avoid Grim flags
 */
public class FakeLag extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // LAGRANGE COMPENSATION
    private static final int LAGRANGE_BASE_PING = 1000; // Your Lagrange constant latency
    private static final int MAX_TOTAL_DELAY = 1500; // Max 1.5s total delay (Lagrange + FakeLag)
    private static final int SAFE_QUEUE_SIZE = 30; // Max packets before force release

    // Mode Selection
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"Latency", "Dynamic", "Repel"});
    
    // Settings - LAGRANGE: Reduced max delay since we already have 1000ms base
    public final IntProperty delay = new IntProperty("delay", 100, 0, 300);
    public final IntProperty transmissionOffset = new IntProperty("transmission-offset", 50, 0, 150);

    private final Queue<PacketData> delayedPackets = new LinkedList<>();
    private long lastReleaseTime = 0L;
    private EntityPlayer lastTarget = null;

    public FakeLag() {
        super("FakeLag", false);
    }

    @Override
    public void onEnabled() {
        delayedPackets.clear();
        lastReleaseTime = System.currentTimeMillis();
        lastTarget = null;
    }

    @Override
    public void onDisabled() {
        // Release all packets to prevent rubberband
        releaseAllPackets();
        delayedPackets.clear();
    }

    @EventTarget(Priority.HIGHEST)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.SEND || mc.thePlayer == null) {
            return;
        }

        Packet<?> packet = event.getPacket();

        // Only delay movement packets
        if (packet instanceof C03PacketPlayer) {
            // CRITICAL: Cancel the packet so we can delay it
            event.setCancelled(true);
            
            long currentDelay = calculateDelay();
            long releaseTime = System.currentTimeMillis() + currentDelay;
            
            // Add to queue with release time
            delayedPackets.add(new PacketData(packet, releaseTime));
            
            // IMPORTANT: Immediately try to process packets
            // This ensures smooth lag simulation instead of teleporting
            processDelayedPacketsImmediate();
        }
    }
    
    /**
     * Immediate packet processing - called right after adding to queue
     * This makes FakeLag work like real lag instead of Blink
     */
    private void processDelayedPacketsImmediate() {
        long currentTime = System.currentTimeMillis();
        
        // Try to release any ready packets immediately
        while (!delayedPackets.isEmpty()) {
            PacketData packetData = delayedPackets.peek();
            
            if (packetData != null && currentTime >= packetData.releaseTime) {
                delayedPackets.poll();
                
                if (mc.getNetHandler() != null && mc.getNetHandler().getNetworkManager() != null) {
                    try {
                        mc.getNetHandler().getNetworkManager().sendPacket(packetData.packet);
                    } catch (Exception e) {
                        // Silently handle
                    }
                }
            } else {
                break; // No more ready packets
            }
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null) {
            return;
        }

        // CRITICAL: Process delayed packets EVERY tick to simulate real lag
        // Not just when we have packets ready - continuously release them
        processDelayedPackets();
    }

    // ==================== Mode Logic ====================

    private long calculateDelay() {
        long baseDelay = this.delay.getValue();
        
        // LAGRANGE BYPASS: Ensure total delay doesn't exceed safe limits
        // We already have 1000ms base, so FakeLag adds less
        long calculatedDelay;
        
        switch (mode.getModeString()) {
            case "Latency":
                // Constant delay
                calculatedDelay = baseDelay;
                break;
                
            case "Dynamic":
                // Dynamic delay based on combat state
                calculatedDelay = calculateDynamicDelay(baseDelay);
                break;
                
            case "Repel":
                // Repel mode: Keep enemies away
                calculatedDelay = calculateRepelDelay(baseDelay);
                break;
                
            default:
                calculatedDelay = baseDelay;
        }
        
        // LAGRANGE BYPASS: Cap total delay to prevent disconnect
        // Total delay = Lagrange (1000ms) + FakeLag delay
        long maxAdditionalDelay = MAX_TOTAL_DELAY - LAGRANGE_BASE_PING;
        return Math.min(calculatedDelay, maxAdditionalDelay);
    }

    private long calculateDynamicDelay(long baseDelay) {
        // Find closest target
        EntityPlayer target = findClosestEnemy();
        
        if (target == null) {
            return baseDelay / 2; // Less delay when not in combat
        }
        
        double distance = mc.thePlayer.getDistanceToEntity(target);
        
        // Increase delay when enemy is close (gives advantage)
        if (distance < 3.0) {
            return baseDelay + transmissionOffset.getValue();
        } else if (distance < 5.0) {
            return baseDelay;
        } else {
            return baseDelay / 2;
        }
    }

    private long calculateRepelDelay(long baseDelay) {
        EntityPlayer target = findClosestEnemy();
        
        if (target == null) {
            return baseDelay / 2;
        }
        
        double distance = mc.thePlayer.getDistanceToEntity(target);
        double prevDistance = lastTarget != null ? mc.thePlayer.getDistanceToEntity(lastTarget) : distance;
        
        lastTarget = target;
        
        // If enemy is getting closer, add more lag to push them back
        if (distance < prevDistance) {
            return baseDelay + (transmissionOffset.getValue() * 2);
        } else {
            return baseDelay;
        }
    }

    // ==================== Packet Management ====================

    private void processDelayedPackets() {
        long currentTime = System.currentTimeMillis();
        
        // FIXED: Release packets in order, like real lag would
        // Real lag doesn't hold packets forever - it delays them then sends
        int released = 0;
        while (!delayedPackets.isEmpty() && released < 10) { // Max 10 per tick to prevent spam
            PacketData packetData = delayedPackets.peek();
            
            if (packetData != null && currentTime >= packetData.releaseTime) {
                delayedPackets.poll();
                
                if (mc.getNetHandler() != null && mc.getNetHandler().getNetworkManager() != null) {
                    try {
                        // Send the packet now
                        mc.getNetHandler().getNetworkManager().sendPacket(packetData.packet);
                        released++;
                    } catch (Exception e) {
                        // Silently handle packet send errors
                    }
                }
            } else {
                break; // No more packets ready to send yet
            }
        }
        
        // LAGRANGE BYPASS: Much stricter queue management due to base 1000ms delay
        if (delayedPackets.size() > SAFE_QUEUE_SIZE) {
            // Too many packets queued, release immediately to prevent disconnect
            // With Lagrange 1000ms base, we can't afford large queues
            releaseAllPackets();
        }
        
        // LAGRANGE BYPASS: Force release old packets earlier
        if (!delayedPackets.isEmpty()) {
            PacketData oldest = delayedPackets.peek();
            // With Lagrange, packets are already delayed 1000ms
            // So FakeLag packets over 400ms old = 1400ms total delay = risky
            if (oldest != null && currentTime - oldest.captureTime > 400) {
                // Force release to prevent disconnect (total delay would be 1400ms+)
                int forceRelease = 0;
                while (!delayedPackets.isEmpty() && forceRelease < 10) {
                    PacketData old = delayedPackets.poll();
                    if (old != null && mc.getNetHandler() != null && mc.getNetHandler().getNetworkManager() != null) {
                        try {
                            mc.getNetHandler().getNetworkManager().sendPacket(old.packet);
                            forceRelease++;
                        } catch (Exception e) {
                            // Silently handle
                        }
                    }
                }
            }
        }
    }

    private void releaseAllPackets() {
        while (!delayedPackets.isEmpty()) {
            PacketData packetData = delayedPackets.poll();
            
            if (packetData != null && mc.getNetHandler() != null && mc.getNetHandler().getNetworkManager() != null) {
                try {
                    mc.getNetHandler().getNetworkManager().sendPacket(packetData.packet);
                } catch (Exception e) {
                    // Silently handle
                }
            }
        }
        lastReleaseTime = System.currentTimeMillis();
    }

    // ==================== Utility Methods ====================

    private EntityPlayer findClosestEnemy() {
        EntityPlayer closest = null;
        double closestDistance = Double.MAX_VALUE;
        
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == mc.thePlayer || player.isDead || player.isInvisible()) {
                continue;
            }
            
            double distance = mc.thePlayer.getDistanceToEntity(player);
            if (distance < closestDistance && distance < 20.0) {
                closestDistance = distance;
                closest = player;
            }
        }
        
        return closest;
    }

    // ==================== Data Classes ====================

    private static class PacketData {
        final Packet<?> packet;
        final long captureTime;
        final long releaseTime;

        PacketData(Packet<?> packet, long releaseTime) {
            this.packet = packet;
            this.captureTime = System.currentTimeMillis();
            this.releaseTime = releaseTime;
        }
    }
    
    @Override
    public String[] getSuffix() {
        return new String[]{String.format("%s (%dms)", mode.getModeString(), delay.getValue())};
    }
}
