package myau.module.modules;

import myau.Myau;
import myau.enums.BlinkModules;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.client.C03PacketPlayer;

public class Blink extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"DEFAULT", "PULSE", "GRIM"});
    public final IntProperty ticks = new IntProperty("ticks", 20, 0, 1200);
    
    // Grim mode settings
    public final IntProperty grimMaxPackets = new IntProperty("grim-max-packets", 5000, 1000, 10000, () -> this.mode.getValue() == 2);
    public final BooleanProperty grimSmartRelease = new BooleanProperty("grim-smart-release", true, () -> this.mode.getValue() == 2);
    public final IntProperty grimReleaseInterval = new IntProperty("grim-release-interval", 50, 20, 200, () -> this.mode.getValue() == 2 && this.grimSmartRelease.getValue());
    public final BooleanProperty grimPacketOptimization = new BooleanProperty("grim-packet-optimization", true, () -> this.mode.getValue() == 2);
    public final IntProperty grimMaxPacketsPerTick = new IntProperty("grim-max-per-tick", 20, 10, 50, () -> this.mode.getValue() == 2);
    
    // Grim mode state
    private long lastPacketReleaseTime = 0L;
    private int packetsSinceLastRelease = 0;
    private int totalPacketsBuffered = 0;

    public Blink() {
        super("Blink", false);
    }

    @EventTarget(Priority.LOWEST)
    public void onTick(TickEvent event) {
        if (this.isEnabled() && event.getType() == EventType.POST) {
            if (!Myau.blinkManager.getBlinkingModule().equals(BlinkModules.BLINK)) {
                this.setEnabled(false);
            } else {
                // Grim mode
                if (this.mode.getValue() == 2) {
                    handleGrimMode();
                } 
                // Default/Pulse modes
                else if (this.ticks.getValue() > 0 && Myau.blinkManager.countMovement() > (long) this.ticks.getValue()) {
                    switch (this.mode.getValue()) {
                        case 0:
                            this.setEnabled(false);
                            break;
                        case 1:
                            Myau.blinkManager.setBlinkState(false, BlinkModules.BLINK);
                            Myau.blinkManager.setBlinkState(true, BlinkModules.BLINK);
                            break;
                    }
                }
            }
        }
    }
    
    @EventTarget(Priority.HIGH)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || this.mode.getValue() != 2) {
            return;
        }
        
        // Grim mode: Track packet buffering
        if (event.getType() == EventType.SEND && event.getPacket() instanceof C03PacketPlayer) {
            totalPacketsBuffered++;
            packetsSinceLastRelease++;
            
            // Grim bypass: Prevent excessive buffering
            if (totalPacketsBuffered >= this.grimMaxPackets.getValue()) {
                // Force partial release to stay under limit
                releasePacketsBatch(this.grimMaxPacketsPerTick.getValue());
            }
        }
    }
    
    private void handleGrimMode() {
        long currentTime = System.currentTimeMillis();
        
        // Smart release: Periodically release packets to avoid detection
        if (this.grimSmartRelease.getValue()) {
            long timeSinceRelease = currentTime - lastPacketReleaseTime;
            
            if (timeSinceRelease >= this.grimReleaseInterval.getValue()) {
                // Release a small batch of packets
                int batchSize = Math.min(packetsSinceLastRelease, this.grimMaxPacketsPerTick.getValue());
                
                if (batchSize > 0) {
                    releasePacketsBatch(batchSize);
                    lastPacketReleaseTime = currentTime;
                    packetsSinceLastRelease = 0;
                }
            }
        }
        
        // Packet optimization: Remove duplicate position packets
        if (this.grimPacketOptimization.getValue() && totalPacketsBuffered > 100) {
            optimizePacketQueue();
        }
        
        // Check if we should disable based on tick limit
        if (this.ticks.getValue() > 0 && Myau.blinkManager.countMovement() > (long) this.ticks.getValue()) {
            this.setEnabled(false);
        }
    }
    
    private void releasePacketsBatch(int count) {
        // Release specified number of packets from queue
        int released = 0;
        
        while (released < count && !Myau.blinkManager.blinkedPackets.isEmpty()) {
            net.minecraft.network.Packet<?> packet = Myau.blinkManager.blinkedPackets.poll();
            if (packet != null && mc.getNetHandler() != null && mc.getNetHandler().getNetworkManager() != null) {
                mc.getNetHandler().getNetworkManager().sendPacket(packet);
                released++;
                totalPacketsBuffered--;
            }
        }
    }
    
    private void optimizePacketQueue() {
        // Remove duplicate/redundant position packets to reduce queue size
        // This helps maintain 5000+ packet capacity without overwhelming Grim
        
        java.util.LinkedList<net.minecraft.network.Packet<?>> optimizedQueue = new java.util.LinkedList<>();
        net.minecraft.network.Packet<?> lastMovementPacket = null;
        int duplicatesRemoved = 0;
        
        for (net.minecraft.network.Packet<?> packet : Myau.blinkManager.blinkedPackets) {
            if (packet instanceof C03PacketPlayer) {
                C03PacketPlayer movementPacket = (C03PacketPlayer) packet;
                
                // Keep important packets (with look updates or position changes)
                if (movementPacket.isMoving() || movementPacket.getRotating()) {
                    optimizedQueue.add(packet);
                    lastMovementPacket = packet;
                } else if (duplicatesRemoved < 50) {
                    // Skip some duplicate packets
                    duplicatesRemoved++;
                    totalPacketsBuffered--;
                } else {
                    optimizedQueue.add(packet);
                }
            } else {
                optimizedQueue.add(packet);
            }
        }
        
        // Replace queue with optimized version
        Myau.blinkManager.blinkedPackets.clear();
        Myau.blinkManager.blinkedPackets.addAll(optimizedQueue);
    }

    @EventTarget
    public void onWorldLoad(LoadWorldEvent event) {
        this.setEnabled(false);
    }

    @Override
    public void onEnabled() {
        Myau.blinkManager.setBlinkState(false, Myau.blinkManager.getBlinkingModule());
        Myau.blinkManager.setBlinkState(true, BlinkModules.BLINK);
        
        // Reset Grim mode state
        lastPacketReleaseTime = System.currentTimeMillis();
        packetsSinceLastRelease = 0;
        totalPacketsBuffered = 0;
    }

    @Override
    public void onDisabled() {
        Myau.blinkManager.setBlinkState(false, BlinkModules.BLINK);
        
        // Reset state
        lastPacketReleaseTime = 0L;
        packetsSinceLastRelease = 0;
        totalPacketsBuffered = 0;
    }
    
    @Override
    public String[] getSuffix() {
        if (this.mode.getValue() == 2) {
            return new String[]{String.format("§aGRIM §7[§e%d§7]", totalPacketsBuffered)};
        }
        return new String[]{this.mode.getModeString()};
    }
}
