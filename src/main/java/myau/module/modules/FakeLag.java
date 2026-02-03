package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.TimerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C03PacketPlayer;

import java.util.LinkedList;
import java.util.Queue;

public class FakeLag extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"LATENCY", "DYNAMIC", "REPEL"});
    public final IntProperty delay = new IntProperty("delay", 100, 0, 1000);
    public final FloatProperty transmissionOffset = new FloatProperty("transmission-offset", 0.5F, 0.0F, 1.0F);
    public final IntProperty maxQueuedPackets = new IntProperty("max-queued", 3, 1, 15);
    public final BooleanProperty smartRelease = new BooleanProperty("smart-release", true);
    public final IntProperty minReleaseInterval = new IntProperty("min-release-interval", 45, 25, 100, () -> this.smartRelease.getValue());
    public final BooleanProperty safeMode = new BooleanProperty("safe-mode", true);
    public final BooleanProperty grimBypass = new BooleanProperty("grim-bypass", true);
    public final BooleanProperty transactionTiming = new BooleanProperty("transaction-timing", true, () -> this.grimBypass.getValue());

    private final Queue<PacketData> delayedPackets = new LinkedList<>();
    private final TimerUtil releaseTimer = new TimerUtil();
    private long dynamicDelay = 0L;
    private boolean inCombat = false;
    private long lastReleaseTime = 0L;
    private long lastPacketTime = 0L;
    private int ticksSinceLastPacket = 0;
    private int packetsThisSecond = 0;
    private long lastSecondReset = 0L;
    private boolean shouldSkipNextPacket = false;

    public FakeLag() {
        super("FakeLag", false);
    }

    @Override
    public void onEnabled() {
        delayedPackets.clear();
        releaseTimer.reset();
        dynamicDelay = 0L;
        inCombat = false;
        lastReleaseTime = System.currentTimeMillis();
        lastPacketTime = System.currentTimeMillis();
        ticksSinceLastPacket = 0;
    }

    @Override
    public void onDisabled() {
        releaseAllPackets();
        delayedPackets.clear();
    }

    @EventTarget(Priority.HIGHEST)
    public void onPacketSend(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.SEND || mc.thePlayer == null) {
            return;
        }

        Packet<?> packet = event.getPacket();

        if (shouldDelayPacket(packet)) {
            long currentTime = System.currentTimeMillis();
            long currentDelay = calculateDelay();
            
            if (this.safeMode.getValue()) {
                if (delayedPackets.size() >= this.maxQueuedPackets.getValue()) {
                    releaseOldestPacket();
                }
                
                if (this.smartRelease.getValue() && currentTime - lastReleaseTime > this.minReleaseInterval.getValue()) {
                    releaseOldestPacket();
                    lastReleaseTime = currentTime;
                }
                
                if (ticksSinceLastPacket > 3) {
                    releaseAllPackets();
                }
            }
            
            if (currentDelay > 0 && delayedPackets.size() < this.maxQueuedPackets.getValue()) {
                delayedPackets.add(new PacketData(packet, currentTime + currentDelay));
                event.setCancelled(true);
                lastPacketTime = currentTime;
                ticksSinceLastPacket = 0;
            }
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null) {
            return;
        }

        ticksSinceLastPacket++;
        
        if (this.safeMode.getValue() && ticksSinceLastPacket > 3 && !delayedPackets.isEmpty()) {
            releaseAllPackets();
        }

        updateCombatState();
        processDelayedPackets();
    }

    private boolean shouldDelayPacket(Packet<?> packet) {
        if (!(packet instanceof C03PacketPlayer)) {
            return false;
        }

        if (mc.thePlayer.ticksExisted < 100) {
            return false;
        }

        if (this.grimBypass.getValue()) {
            long currentTime = System.currentTimeMillis();
            
            if (currentTime - lastSecondReset > 1000) {
                packetsThisSecond = 0;
                lastSecondReset = currentTime;
            }
            
            packetsThisSecond++;
            
            if (packetsThisSecond > 18) {
                return false;
            }
            
            if (shouldSkipNextPacket) {
                shouldSkipNextPacket = false;
                return false;
            }
            
            if (packetsThisSecond % 4 == 0) {
                shouldSkipNextPacket = true;
            }
        }

        return true;
    }

    private long calculateDelay() {
        switch (this.mode.getValue()) {
            case 0:
                return calculateLatencyDelay();
            case 1:
                return calculateDynamicDelay();
            case 2:
                return calculateRepelDelay();
            default:
                return 0L;
        }
    }

    private long calculateLatencyDelay() {
        long baseDelay = this.delay.getValue();
        
        if (this.safeMode.getValue() && baseDelay > 150) {
            baseDelay = 150;
        }
        
        if (this.grimBypass.getValue()) {
            if (baseDelay > 100) {
                baseDelay = 100;
            }
            
            if (this.transactionTiming.getValue()) {
                long timeSinceLastRelease = System.currentTimeMillis() - lastReleaseTime;
                if (timeSinceLastRelease < 40) {
                    baseDelay = Math.min(baseDelay, 40);
                }
            }
        }
        
        double offset = this.transmissionOffset.getValue();
        
        if (offset > 0.01F) {
            double variance = baseDelay * offset;
            double randomOffset = (Math.random() - 0.5) * variance;
            return (long) (baseDelay + randomOffset);
        }
        
        return baseDelay;
    }

    private long calculateDynamicDelay() {
        if (!inCombat) {
            return Math.min(50L, this.delay.getValue() / 2);
        }

        EntityPlayer nearestEnemy = findNearestEnemy();
        if (nearestEnemy == null) {
            return this.delay.getValue() / 2;
        }

        double distance = mc.thePlayer.getDistanceToEntity(nearestEnemy);
        double velocityTowards = calculateVelocityTowards(nearestEnemy);

        long calculatedDelay;
        if (velocityTowards > 0.1) {
            calculatedDelay = (long) (this.delay.getValue() * 1.5);
        } else if (distance < 3.0) {
            calculatedDelay = this.delay.getValue();
        } else {
            calculatedDelay = this.delay.getValue() / 2;
        }
        
        if (this.safeMode.getValue() && calculatedDelay > 150) {
            calculatedDelay = 150;
        }
        
        return calculatedDelay;
    }

    private long calculateRepelDelay() {
        EntityPlayer nearestEnemy = findNearestEnemy();
        if (nearestEnemy == null) {
            return 0L;
        }

        double distance = mc.thePlayer.getDistanceToEntity(nearestEnemy);
        double velocityTowards = calculateVelocityTowards(nearestEnemy);

        if (velocityTowards < -0.05) {
            return 0L;
        }

        if (distance < 4.0 && velocityTowards > 0.05) {
            double repelFactor = 1.0 - (distance / 4.0);
            long calculatedDelay = (long) (this.delay.getValue() * repelFactor);
            
            if (this.safeMode.getValue() && calculatedDelay > 150) {
                calculatedDelay = 150;
            }
            
            return calculatedDelay;
        }

        return 0L;
    }

    private void updateCombatState() {
        EntityPlayer nearestEnemy = findNearestEnemy();
        inCombat = nearestEnemy != null && mc.thePlayer.getDistanceToEntity(nearestEnemy) < 6.0;
    }

    private void processDelayedPackets() {
        long currentTime = System.currentTimeMillis();
        
        if (this.smartRelease.getValue() && currentTime - lastReleaseTime > this.minReleaseInterval.getValue() && !delayedPackets.isEmpty()) {
            releaseOldestPacket();
            lastReleaseTime = currentTime;
        }
        
        while (!delayedPackets.isEmpty()) {
            PacketData packetData = delayedPackets.peek();
            
            if (packetData == null) {
                delayedPackets.poll();
                continue;
            }

            if (currentTime >= packetData.releaseTime) {
                delayedPackets.poll();
                sendPacketDirect(packetData.packet);
                lastReleaseTime = currentTime;
            } else {
                break;
            }
        }
    }

    private void releaseAllPackets() {
        while (!delayedPackets.isEmpty()) {
            PacketData packetData = delayedPackets.poll();
            if (packetData != null) {
                sendPacketDirect(packetData.packet);
            }
        }
        lastReleaseTime = System.currentTimeMillis();
        ticksSinceLastPacket = 0;
    }
    
    private void releaseOldestPacket() {
        if (!delayedPackets.isEmpty()) {
            PacketData packetData = delayedPackets.poll();
            if (packetData != null) {
                sendPacketDirect(packetData.packet);
            }
        }
    }

    private void sendPacketDirect(Packet<?> packet) {
        if (mc.getNetHandler() != null && mc.getNetHandler().getNetworkManager() != null) {
            mc.getNetHandler().getNetworkManager().sendPacket(packet);
        }
    }

    private EntityPlayer findNearestEnemy() {
        EntityPlayer nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == mc.thePlayer || player.isDead) {
                continue;
            }

            if (!isValidEnemy(player)) {
                continue;
            }

            double distance = mc.thePlayer.getDistanceToEntity(player);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = player;
            }
        }

        return nearest;
    }

    private boolean isValidEnemy(EntityPlayer player) {
        if (player == mc.thePlayer || player.isDead || player.getHealth() <= 0) {
            return false;
        }

        KillAura killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        if (killAura != null && killAura.isEnabled()) {
            if (!killAura.players.getValue()) {
                return false;
            }
            if (killAura.teams.getValue() && player.isOnSameTeam(mc.thePlayer)) {
                return false;
            }
        }

        return mc.thePlayer.getDistanceToEntity(player) <= 10.0;
    }

    private double calculateVelocityTowards(EntityPlayer target) {
        double deltaX = target.posX - mc.thePlayer.posX;
        double deltaZ = target.posZ - mc.thePlayer.posZ;
        double distance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);

        if (distance < 0.01) {
            return 0.0;
        }

        double normalizedX = deltaX / distance;
        double normalizedZ = deltaZ / distance;

        return (mc.thePlayer.motionX * normalizedX + mc.thePlayer.motionZ * normalizedZ);
    }

    public int getQueuedPackets() {
        return delayedPackets.size();
    }

    @Override
    public String[] getSuffix() {
        return new String[]{String.format("§7[§e%d§7]", delayedPackets.size())};
    }

    private static class PacketData {
        final Packet<?> packet;
        final long releaseTime;

        PacketData(Packet<?> packet, long releaseTime) {
            this.packet = packet;
            this.releaseTime = releaseTime;
        }
    }
}
