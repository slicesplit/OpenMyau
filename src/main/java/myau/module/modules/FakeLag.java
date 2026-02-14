package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.ModeProperty;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.*;
import net.minecraft.network.play.server.*;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Queue;

/**
 * FakeLag - Holds back packets to prevent being hit
 * 
 * Ported from LiquidBounce with full utilities embedded
 * Uses intelligent packet queueing to create lag advantage
 */
@ModuleInfo(category = ModuleCategory.COMBAT)
public class FakeLag extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // Settings
    public final FloatProperty minRange = new FloatProperty("Min-Range", 2.0f, 0.0f, 10.0f);
    public final FloatProperty maxRange = new FloatProperty("Max-Range", 5.0f, 0.0f, 10.0f);
    public final IntProperty minDelay = new IntProperty("Min-Delay", 300, 0, 1000);
    public final IntProperty maxDelay = new IntProperty("Max-Delay", 600, 0, 1000);
    public final IntProperty recoilTime = new IntProperty("Recoil-Time", 250, 0, 1000);
    public final ModeProperty mode = new ModeProperty("Mode", 1, new String[]{"Constant", "Dynamic"});
    
    // Flush triggers
    public final BooleanProperty flushOnEntityInteract = new BooleanProperty("Flush-Entity-Interact", true);
    public final BooleanProperty flushOnBlockInteract = new BooleanProperty("Flush-Block-Interact", true);
    public final BooleanProperty flushOnAction = new BooleanProperty("Flush-Action", true);
    public final BooleanProperty flushOnKnockback = new BooleanProperty("Flush-Knockback", true);
    public final BooleanProperty flushOnDamage = new BooleanProperty("Flush-Damage", true);
    
    // Packet queue
    private final Queue<Packet<?>> queuedPackets = new LinkedList<>();
    private final ArrayList<Vec3> positions = new ArrayList<>();
    
    // State tracking
    private long nextDelay = 0;
    private long lastFlushTime = 0;
    private boolean isEnemyNearby = false;
    
    public FakeLag() {
        super("FakeLag", false);
    }
    
    @Override
    public void onEnabled() {
        queuedPackets.clear();
        positions.clear();
        nextDelay = getRandomDelay();
        lastFlushTime = System.currentTimeMillis();
        isEnemyNearby = false;
    }
    
    @Override
    public void onDisabled() {
        flush();
        queuedPackets.clear();
        positions.clear();
        isEnemyNearby = false;
    }
    
    @EventTarget
    public void onTick(TickEvent event) {
        if (!isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null) return;
        
        // Track current position
        positions.add(new Vec3(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ));
        
        // Limit position history (keep last 100 ticks = 5 seconds)
        while (positions.size() > 100) {
            positions.remove(0);
        }
        
        // Check for nearby enemies
        isEnemyNearby = findEnemy() != null;
    }
    
    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || mc.thePlayer == null) return;
        
        // Only process outgoing packets
        if (event.getType() == EventType.SEND) {
            handleOutgoingPacket(event);
        } else if (event.getType() == EventType.RECEIVE) {
            handleIncomingPacket(event);
        }
    }
    
    /**
     * Handle outgoing packets - queue or send
     */
    private void handleOutgoingPacket(PacketEvent event) {
        Packet<?> packet = event.getPacket();
        
        // Never queue critical packets
        if (shouldNeverQueue(packet)) {
            return;
        }
        
        // Check recoil time
        if (!hasRecoilElapsed()) {
            return;
        }
        
        // Check if we should flush due to delay timeout
        if (isAboveDelay()) {
            nextDelay = getRandomDelay();
            flush();
            return;
        }
        
        // Check flush triggers
        if (shouldFlush(packet)) {
            flush();
            resetRecoilTimer();
            return;
        }
        
        // Decide whether to queue based on mode
        boolean shouldQueue = false;
        
        if (mode.getModeString().equals("Constant")) {
            shouldQueue = true;
        } else if (mode.getModeString().equals("Dynamic")) {
            shouldQueue = shouldQueueDynamic();
        }
        
        if (shouldQueue) {
            queuedPackets.add(packet);
            event.setCancelled(true);
        }
    }
    
    /**
     * Handle incoming packets - flush on certain triggers
     */
    private void handleIncomingPacket(PacketEvent event) {
        Packet<?> packet = event.getPacket();
        
        // Flush on server position update
        if (packet instanceof S08PacketPlayerPosLook) {
            flush();
            resetRecoilTimer();
            return;
        }
        
        // Flush on knockback
        if (flushOnKnockback.getValue() && packet instanceof S12PacketEntityVelocity) {
            S12PacketEntityVelocity velocityPacket = (S12PacketEntityVelocity) packet;
            if (velocityPacket.getEntityID() == mc.thePlayer.getEntityId()) {
                if (velocityPacket.getMotionX() != 0 || velocityPacket.getMotionY() != 0 || velocityPacket.getMotionZ() != 0) {
                    flush();
                    resetRecoilTimer();
                }
            }
            return;
        }
        
        // Flush on damage
        if (flushOnDamage.getValue() && packet instanceof S06PacketUpdateHealth) {
            flush();
            resetRecoilTimer();
            return;
        }
        
        // Flush on explosion
        if (flushOnKnockback.getValue() && packet instanceof S27PacketExplosion) {
            flush();
            resetRecoilTimer();
        }
    }
    
    /**
     * Packets that should NEVER be queued (critical for connection)
     */
    private boolean shouldNeverQueue(Packet<?> packet) {
        // Transaction packets - critical for Grim timing
        if (packet instanceof C0FPacketConfirmTransaction) return true;
        
        // Keep alive - critical for connection
        if (packet.getClass().getSimpleName().contains("KeepAlive")) return true;
        if (packet.getClass().getSimpleName().contains("Pong")) return true;
        
        // Resource pack responses
        if (packet.getClass().getSimpleName().contains("ResourcePack")) return true;
        
        return false;
    }
    
    /**
     * Check if packet should trigger flush
     */
    private boolean shouldFlush(Packet<?> packet) {
        // Entity interact (attack/use)
        if (flushOnEntityInteract.getValue()) {
            if (packet instanceof C02PacketUseEntity) return true;
            if (packet instanceof C0APacketAnimation) return true;
        }
        
        // Block interact
        if (flushOnBlockInteract.getValue()) {
            if (packet instanceof C08PacketPlayerBlockPlacement) return true;
            if (packet instanceof C12PacketUpdateSign) return true;
        }
        
        // Player actions
        if (flushOnAction.getValue()) {
            if (packet instanceof C07PacketPlayerDigging) return true;
        }
        
        return false;
    }
    
    /**
     * Dynamic mode decision - only queue when advantageous
     */
    private boolean shouldQueueDynamic() {
        // No enemy nearby - don't queue
        if (!isEnemyNearby) {
            return false;
        }
        
        // No position history yet
        if (positions.isEmpty()) {
            return true;
        }
        
        // Get server position (oldest queued position)
        Vec3 serverPosition = positions.get(0);
        Vec3 clientPosition = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
        
        // Find closest enemy
        EntityPlayer closestEnemy = findEnemy();
        if (closestEnemy == null) {
            return false;
        }
        
        Vec3 enemyPos = new Vec3(closestEnemy.posX, closestEnemy.posY, closestEnemy.posZ);
        
        // Calculate distances
        double serverDistance = serverPosition.distanceTo(enemyPos);
        double clientDistance = clientPosition.distanceTo(enemyPos);
        
        // Check if enemy would intersect with server position
        if (wouldIntersect(serverPosition, closestEnemy)) {
            return false; // Don't queue if we'd get hit at server position
        }
        
        // Only queue if server position is closer (disadvantageous for us)
        // This creates lag advantage by staying at further position
        return serverDistance < clientDistance;
    }
    
    /**
     * Check if entity would intersect with position
     */
    private boolean wouldIntersect(Vec3 position, EntityPlayer entity) {
        // Player hitbox: 0.6 width, 1.8 height
        double minX = position.xCoord - 0.3;
        double maxX = position.xCoord + 0.3;
        double minY = position.yCoord;
        double maxY = position.yCoord + 1.8;
        double minZ = position.zCoord - 0.3;
        double maxZ = position.zCoord + 0.3;
        
        // Entity hitbox
        double eMinX = entity.posX - 0.3;
        double eMaxX = entity.posX + 0.3;
        double eMinY = entity.posY;
        double eMaxY = entity.posY + 1.8;
        double eMinZ = entity.posZ - 0.3;
        double eMaxZ = entity.posZ + 0.3;
        
        // AABB intersection test
        return maxX > eMinX && minX < eMaxX &&
               maxY > eMinY && minY < eMaxY &&
               maxZ > eMinZ && minZ < eMaxZ;
    }
    
    /**
     * Find nearest enemy within range
     */
    private EntityPlayer findEnemy() {
        if (mc.theWorld == null) return null;
        
        EntityPlayer closest = null;
        double closestDist = maxRange.getValue();
        
        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (!(entity instanceof EntityPlayer)) continue;
            
            EntityPlayer player = (EntityPlayer) entity;
            
            // Skip self and teammates
            if (player == mc.thePlayer || TeamUtil.isFriend(player)) continue;
            
            // Skip dead/invalid
            if (player.isDead || player.getHealth() <= 0) continue;
            
            double dist = mc.thePlayer.getDistanceToEntity(player);
            
            // Within range
            if (dist >= minRange.getValue() && dist <= maxRange.getValue()) {
                if (dist < closestDist) {
                    closest = player;
                    closestDist = dist;
                }
            }
        }
        
        return closest;
    }
    
    /**
     * Flush all queued packets
     */
    private void flush() {
        while (!queuedPackets.isEmpty()) {
            Packet<?> packet = queuedPackets.poll();
            if (packet != null && mc.getNetHandler() != null) {
                mc.getNetHandler().addToSendQueue(packet);
            }
        }
        
        // Clear position history on flush
        positions.clear();
    }
    
    /**
     * Check if recoil time has elapsed
     */
    private boolean hasRecoilElapsed() {
        return (System.currentTimeMillis() - lastFlushTime) >= recoilTime.getValue();
    }
    
    /**
     * Check if we're above the delay threshold
     */
    private boolean isAboveDelay() {
        if (positions.isEmpty()) return false;
        
        // Approximate time based on position count (20 ticks/sec = 50ms/tick)
        long approximateTime = positions.size() * 50;
        return approximateTime >= nextDelay;
    }
    
    /**
     * Reset recoil timer
     */
    private void resetRecoilTimer() {
        lastFlushTime = System.currentTimeMillis();
    }
    
    /**
     * Get random delay between min and max
     */
    private long getRandomDelay() {
        int min = minDelay.getValue();
        int max = maxDelay.getValue();
        if (min >= max) return min;
        return min + (long)(Math.random() * (max - min));
    }
    
    /**
     * Get suffix for display
     */
    @Override
    public String[] getSuffix() {
        if (queuedPackets.isEmpty()) {
            return new String[]{mode.getModeString()};
        }
        return new String[]{queuedPackets.size() + " queued"};
    }
}
