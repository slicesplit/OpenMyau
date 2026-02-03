package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
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

    private final Queue<PacketData> delayedPackets = new LinkedList<>();
    private final TimerUtil releaseTimer = new TimerUtil();
    private long dynamicDelay = 0L;
    private boolean inCombat = false;

    public FakeLag() {
        super("FakeLag", false);
    }

    @Override
    public void onEnabled() {
        delayedPackets.clear();
        releaseTimer.reset();
        dynamicDelay = 0L;
        inCombat = false;
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
            long currentDelay = calculateDelay();
            
            if (currentDelay > 0) {
                delayedPackets.add(new PacketData(packet, System.currentTimeMillis() + currentDelay));
                event.setCancelled(true);
            }
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null) {
            return;
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

        if (velocityTowards > 0.1) {
            return (long) (this.delay.getValue() * 1.5);
        } else if (distance < 3.0) {
            return this.delay.getValue();
        } else {
            return this.delay.getValue() / 2;
        }
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
            return (long) (this.delay.getValue() * repelFactor);
        }

        return 0L;
    }

    private void updateCombatState() {
        EntityPlayer nearestEnemy = findNearestEnemy();
        inCombat = nearestEnemy != null && mc.thePlayer.getDistanceToEntity(nearestEnemy) < 6.0;
    }

    private void processDelayedPackets() {
        long currentTime = System.currentTimeMillis();
        
        while (!delayedPackets.isEmpty()) {
            PacketData packetData = delayedPackets.peek();
            
            if (packetData == null) {
                delayedPackets.poll();
                continue;
            }

            if (currentTime >= packetData.releaseTime) {
                delayedPackets.poll();
                sendPacketDirect(packetData.packet);
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
