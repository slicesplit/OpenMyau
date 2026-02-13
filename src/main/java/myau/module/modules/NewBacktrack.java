package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.*;
import myau.module.Module;
import myau.property.properties.*;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.*;
import net.minecraft.network.play.client.*;
import net.minecraft.util.Vec3;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

@ModuleInfo(category = ModuleCategory.COMBAT)
public class NewBacktrack extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final FloatProperty minRange = new FloatProperty("min-range", 1.0f, 0.0f, 10.0f);
    public final FloatProperty maxRange = new FloatProperty("max-range", 3.0f, 0.0f, 10.0f);
    public final IntProperty minDelay = new IntProperty("min-delay", 100, 0, 1000);
    public final IntProperty maxDelay = new IntProperty("max-delay", 150, 0, 1000);
    public final IntProperty minNextBacktrackDelay = new IntProperty("min-next-backtrack-delay", 0, 0, 2000);
    public final IntProperty maxNextBacktrackDelay = new IntProperty("max-next-backtrack-delay", 10, 0, 2000);
    public final IntProperty trackingBuffer = new IntProperty("tracking-buffer", 500, 0, 2000);
    public final FloatProperty chance = new FloatProperty("chance", 50.0f, 0.0f, 100.0f);
    
    public final BooleanProperty pauseOnHurtTimeEnabled = new BooleanProperty("pause-on-hurttime-enabled", false);
    public final IntProperty pauseOnHurtTime = new IntProperty("pause-on-hurttime", 3, 0, 10, () -> pauseOnHurtTimeEnabled.getValue());
    
    public final ModeProperty targetMode = new ModeProperty("target-mode", 0, new String[]{"Attack", "Range"});
    public final IntProperty lastAttackTimeToWork = new IntProperty("last-attack-time-to-work", 1000, 0, 5000);

    private final Queue<DelayedPacket> delayedPackets = new ConcurrentLinkedQueue<>();
    private final Random random = new Random();
    
    private EntityPlayer target = null;
    private Vec3 trackedPosition = new Vec3(0, 0, 0);
    
    private long lastUpdateTime = 0L;
    private long trackingBufferStartTime = 0L;
    private long lastAttackTime = 0L;
    private long nextBacktrackAllowedTime = 0L;
    
    private int currentDelay = 0;
    private int currentChance = 0;
    private boolean shouldPause = false;

    public NewBacktrack() {
        super("NewBacktrack", false);
    }

    @Override
    public void onEnabled() {
        clear(true, false);
        currentDelay = getRandomDelay();
        currentChance = random.nextInt(101);
    }

    @Override
    public void onDisabled() {
        clear(true, false);
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || event.getType() != EventType.RECEIVE) {
            return;
        }

        Packet packet = event.getPacket();
        
        if (packet instanceof S08PacketPlayerPosLook || packet instanceof S40PacketDisconnect) {
            clear(true, false);
            return;
        }

        if (packet instanceof S06PacketUpdateHealth) {
            S06PacketUpdateHealth healthPacket = (S06PacketUpdateHealth) packet;
            if (healthPacket.getHealth() <= 0) {
                clear(true, false);
                return;
            }
        }

        boolean shouldCancelPackets = shouldCancelPackets();
        boolean hasQueuedPackets = !delayedPackets.isEmpty();

        if (!hasQueuedPackets && !shouldCancelPackets) {
            return;
        }

        if (packet instanceof S18PacketEntityTeleport) {
            if (target != null) {
                S18PacketEntityTeleport teleportPacket = (S18PacketEntityTeleport) packet;
                int entityId = teleportPacket.getEntityId();

                if (entityId == target.getEntityId()) {
                    Vec3 newPos = new Vec3(teleportPacket.getX() / 32.0, teleportPacket.getY() / 32.0, teleportPacket.getZ() / 32.0);
                    trackedPosition = newPos;

                    double currentDist = getSquaredBoxedDistance(target, target.getPositionVector());
                    double trackedDist = getSquaredBoxedDistance(target, trackedPosition);

                    if (trackedDist < currentDist) {
                        flushPackets();
                        return;
                    }
                }
            }

            if (shouldCancelPackets) {
                delayedPackets.add(new DelayedPacket(packet, System.currentTimeMillis()));
                event.setCancelled(true);
            }
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        long currentTime = System.currentTimeMillis();

        if (shouldCancelPackets()) {
            flushOldPackets(currentTime - currentDelay);
        } else if (!delayedPackets.isEmpty()) {
            flushPackets();
            clear(false, true);
        }

        if (delayedPackets.isEmpty()) {
            currentDelay = getRandomDelay();
        }
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }

        lastAttackTime = System.currentTimeMillis();
        currentChance = random.nextInt(101);

        if (!"Attack".equals(targetMode.getModeString())) {
            return;
        }

        if (event.getTarget() instanceof EntityPlayer) {
            processTarget((EntityPlayer) event.getTarget());
        }
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        clear(true, true);
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        if ("Range".equals(targetMode.getModeString())) {
            EntityPlayer enemy = findNearestEnemy();
            
            if (enemy == null) {
                clear(false, true);
                return;
            }

            processTarget(enemy);
        }
    }

    private void processTarget(EntityPlayer enemy) {
        if (enemy == null) {
            return;
        }

        shouldPause = pauseOnHurtTimeEnabled.getValue() && enemy.hurtTime >= pauseOnHurtTime.getValue();

        if (!shouldBacktrack(enemy)) {
            return;
        }

        if (enemy != target) {
            clear(false, false);
            trackedPosition = enemy.getPositionVector();
        }

        target = enemy;
    }

    private boolean shouldBacktrack(EntityPlayer target) {
        if (target == null) {
            return false;
        }

        double distance = getBoxedDistance(target);
        boolean inRange = distance >= minRange.getValue() && distance <= maxRange.getValue();

        if (inRange) {
            trackingBufferStartTime = System.currentTimeMillis();
        }

        long timeSinceInRange = System.currentTimeMillis() - trackingBufferStartTime;
        long timeSinceAttack = System.currentTimeMillis() - lastAttackTime;

        return (inRange || timeSinceInRange < trackingBuffer.getValue()) &&
               shouldBeAttacked(target) &&
               mc.thePlayer.ticksExisted > 10 &&
               currentChance < chance.getValue() &&
               System.currentTimeMillis() >= nextBacktrackAllowedTime &&
               !shouldPause &&
               timeSinceAttack < lastAttackTimeToWork.getValue();
    }

    private boolean shouldCancelPackets() {
        return target != null && target.isEntityAlive() && shouldBacktrack(target);
    }

    private void clear(boolean flushPackets, boolean resetTarget) {
        if (flushPackets) {
            flushPackets();
        }

        delayedPackets.clear();

        if (resetTarget) {
            if (target != null) {
                int minDelay = minNextBacktrackDelay.getValue();
                int maxDelay = maxNextBacktrackDelay.getValue();
                int randomDelay = minDelay + random.nextInt(Math.max(1, maxDelay - minDelay + 1));
                nextBacktrackAllowedTime = System.currentTimeMillis() + randomDelay;
            }

            target = null;
            trackedPosition = new Vec3(0, 0, 0);
        }
    }

    private void flushPackets() {
        while (!delayedPackets.isEmpty()) {
            DelayedPacket delayed = delayedPackets.poll();
            if (delayed != null && mc.getNetHandler() != null) {
                try {
                    delayed.packet.processPacket(mc.getNetHandler());
                } catch (Exception e) {
                }
            }
        }
    }

    private void flushOldPackets(long beforeTime) {
        List<DelayedPacket> toFlush = new ArrayList<>();
        Iterator<DelayedPacket> iterator = delayedPackets.iterator();
        
        while (iterator.hasNext()) {
            DelayedPacket delayed = iterator.next();
            if (delayed.timestamp <= beforeTime) {
                toFlush.add(delayed);
                iterator.remove();
            }
        }

        for (DelayedPacket delayed : toFlush) {
            if (mc.getNetHandler() != null) {
                try {
                    delayed.packet.processPacket(mc.getNetHandler());
                } catch (Exception e) {
                }
            }
        }
    }

    private int getRandomDelay() {
        int min = minDelay.getValue();
        int max = maxDelay.getValue();
        return min + random.nextInt(Math.max(1, max - min + 1));
    }

    private EntityPlayer findNearestEnemy() {
        EntityPlayer nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (Object obj : mc.theWorld.playerEntities) {
            if (!(obj instanceof EntityPlayer)) continue;
            
            EntityPlayer player = (EntityPlayer) obj;
            
            if (player == mc.thePlayer || !shouldBeAttacked(player)) {
                continue;
            }

            double dist = getBoxedDistance(player);
            if (dist < nearestDist && dist <= maxRange.getValue()) {
                nearest = player;
                nearestDist = dist;
            }
        }

        return nearest;
    }

    private boolean shouldBeAttacked(EntityPlayer player) {
        if (player == null || player.isDead || player.getHealth() <= 0) {
            return false;
        }

        if (TeamUtil.isFriend(player)) {
            return false;
        }

        return true;
    }

    private double getBoxedDistance(EntityPlayer player) {
        if (player == null) return Double.MAX_VALUE;
        
        double x = mc.thePlayer.posX;
        double y = mc.thePlayer.posY;
        double z = mc.thePlayer.posZ;
        
        return player.getEntityBoundingBox().calculateIntercept(new Vec3(x, y, z), new Vec3(x, y, z)) != null ? 0 : 
               mc.thePlayer.getDistance(player.posX, player.posY, player.posZ);
    }

    private double getSquaredBoxedDistance(EntityPlayer player, Vec3 pos) {
        if (player == null || pos == null) return Double.MAX_VALUE;
        
        double dx = Math.max(0, Math.max(player.getEntityBoundingBox().minX - pos.xCoord, pos.xCoord - player.getEntityBoundingBox().maxX));
        double dy = Math.max(0, Math.max(player.getEntityBoundingBox().minY - pos.yCoord, pos.yCoord - player.getEntityBoundingBox().maxY));
        double dz = Math.max(0, Math.max(player.getEntityBoundingBox().minZ - pos.zCoord, pos.zCoord - player.getEntityBoundingBox().maxZ));
        
        return dx * dx + dy * dy + dz * dz;
    }

    private static class DelayedPacket {
        final Packet packet;
        final long timestamp;

        DelayedPacket(Packet packet, long timestamp) {
            this.packet = packet;
            this.timestamp = timestamp;
        }
    }
}
