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
 */
public class FakeLag extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    // Mode Selection
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"Latency", "Dynamic", "Repel"});
    
    // Settings
    public final IntProperty delay = new IntProperty("delay", 100, 0, 500);
    public final IntProperty transmissionOffset = new IntProperty("transmission-offset", 50, 0, 200);

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
            event.setCancelled(true);
            
            long currentDelay = calculateDelay();
            delayedPackets.add(new PacketData(packet, System.currentTimeMillis() + currentDelay));
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null) {
            return;
        }

        // Process delayed packets
        processDelayedPackets();
    }

    // ==================== Mode Logic ====================

    private long calculateDelay() {
        long baseDelay = this.delay.getValue();
        
        switch (mode.getModeString()) {
            case "Latency":
                // Constant delay
                return baseDelay;
                
            case "Dynamic":
                // Dynamic delay based on combat state
                return calculateDynamicDelay(baseDelay);
                
            case "Repel":
                // Repel mode: Keep enemies away
                return calculateRepelDelay(baseDelay);
                
            default:
                return baseDelay;
        }
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
        
        // Release packets whose delay has expired
        while (!delayedPackets.isEmpty()) {
            PacketData packetData = delayedPackets.peek();
            
            if (packetData != null && currentTime >= packetData.releaseTime) {
                delayedPackets.poll();
                
                if (mc.getNetHandler() != null && mc.getNetHandler().getNetworkManager() != null) {
                    try {
                        mc.getNetHandler().getNetworkManager().sendPacket(packetData.packet);
                    } catch (Exception e) {
                        // Silently handle packet send errors
                    }
                }
            } else {
                break; // No more packets ready to send
            }
        }
        
        // Anti-rubberband: Force release old packets
        if (!delayedPackets.isEmpty()) {
            PacketData oldest = delayedPackets.peek();
            if (oldest != null && currentTime - oldest.captureTime > 1000) {
                // Packet is over 1 second old, force release to prevent disconnect
                releaseAllPackets();
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
