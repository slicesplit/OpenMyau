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
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * FakeLag - Holds back packets to prevent being hit
 * 
 * COMPLETELY REWRITTEN from LiquidBounce implementation
 * Uses proper packet queueing with position tracking to eliminate rubberbanding
 * 
 * Key improvements:
 * - Proper position tracking (like LiquidBounce's BlinkManager)
 * - Correct packet queue management with timestamps
 * - Dynamic mode checks server vs client position distance
 * - No rubberbanding due to proper flush mechanics
 */
@ModuleInfo(category = ModuleCategory.COMBAT)
public class FakeLag extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // Settings (matching LiquidBounce)
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
    
    // Packet queue with timestamps (critical for proper ordering)
    private final ConcurrentLinkedQueue<PacketSnapshot> packetQueue = new ConcurrentLinkedQueue<>();
    
    // State tracking
    private long queueStartTime = 0;
    private long lastFlushTime = 0;
    private long nextDelay = 0;
    private boolean isEnemyNearby = false;
    
    /**
     * Packet snapshot with timestamp - ensures proper ordering
     */
    private static class PacketSnapshot {
        final Packet<?> packet;
        final long timestamp;
        
        PacketSnapshot(Packet<?> packet, long timestamp) {
            this.packet = packet;
            this.timestamp = timestamp;
        }
    }
    
    public FakeLag() {
        super("FakeLag", false);
    }
    
    @Override
    public void onEnabled() {
        packetQueue.clear();
        queueStartTime = 0;
        nextDelay = getRandomDelay();
        lastFlushTime = System.currentTimeMillis();
        isEnemyNearby = false;
    }
    
    @Override
    public void onDisabled() {
        flushAllPackets();
        packetQueue.clear();
        isEnemyNearby = false;
    }
    
    @EventTarget
    public void onTick(TickEvent event) {
        if (!isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null) return;
        
        // Check for nearby enemies
        isEnemyNearby = findEnemy() != null;
        
        // Auto-flush if no packets queued (reset state)
        if (packetQueue.isEmpty() && queueStartTime != 0) {
            queueStartTime = 0;
        }
    }
    
    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;
        
        // Only process outgoing packets
        if (event.getType() == EventType.SEND) {
            handleOutgoingPacket(event);
        } else if (event.getType() == EventType.RECEIVE) {
            handleIncomingPacket(event);
        }
    }
    
    /**
     * Handle outgoing packets - queue or send
     * This is the CRITICAL method that prevents rubberbanding
     */
    private void handleOutgoingPacket(PacketEvent event) {
        Packet<?> packet = event.getPacket();
        
        // Never queue critical packets (connection stability)
        if (shouldNeverQueue(packet)) {
            return;
        }
        
        // Check if we should flush based on triggers
        if (shouldFlushOnPacket(packet)) {
            flushAllPackets();
            resetRecoilTimer();
            return;
        }
        
        // Don't queue if in recoil period
        if (!hasRecoilElapsed()) {
            return;
        }
        
        // Check if we've exceeded max delay - flush and reset
        if (isAboveDelay()) {
            flushAllPackets();
            nextDelay = getRandomDelay();
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
            // Start tracking queue time on first packet
            if (packetQueue.isEmpty()) {
                queueStartTime = System.currentTimeMillis();
            }
            
            // Add to queue with timestamp
            packetQueue.add(new PacketSnapshot(packet, System.currentTimeMillis()));
            event.setCancelled(true);
        }
    }
    
    /**
     * Handle incoming packets - flush on certain server events
     */
    private void handleIncomingPacket(PacketEvent event) {
        Packet<?> packet = event.getPacket();
        
        // Flush on server teleport (critical - prevents desync)
        if (packet instanceof S08PacketPlayerPosLook) {
            flushAllPackets();
            resetRecoilTimer();
            return;
        }
        
        // Flush on knockback
        if (packet instanceof S12PacketEntityVelocity) {
            S12PacketEntityVelocity velocityPacket = (S12PacketEntityVelocity) packet;
            if (velocityPacket.getEntityID() == mc.thePlayer.getEntityId()) {
                if (velocityPacket.getMotionX() != 0 || velocityPacket.getMotionY() != 0 || velocityPacket.getMotionZ() != 0) {
                    flushAllPackets();
                    resetRecoilTimer();
                }
            }
            return;
        }
        
        // Flush on damage
        if (packet instanceof S06PacketUpdateHealth) {
            flushAllPackets();
            resetRecoilTimer();
            return;
        }
        
        // Flush on explosion
        if (packet instanceof S27PacketExplosion) {
            flushAllPackets();
            resetRecoilTimer();
        }
    }
    
    /**
     * Packets that should NEVER be queued (critical for connection)
     */
    private boolean shouldNeverQueue(Packet<?> packet) {
        // Transaction packets - CRITICAL for Grim timing, never queue
        if (packet instanceof C0FPacketConfirmTransaction) return true;
        
        // Keep alive - connection stability
        if (packet.getClass().getSimpleName().contains("KeepAlive")) return true;
        if (packet.getClass().getSimpleName().contains("Pong")) return true;
        
        // Resource pack responses
        if (packet.getClass().getSimpleName().contains("ResourcePack")) return true;
        
        // Chat packets - should always go through immediately
        if (packet instanceof C01PacketChatMessage) return true;
        
        return false;
    }
    
    /**
     * Check if packet should trigger flush
     */
    private boolean shouldFlushOnPacket(Packet<?> packet) {
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
     * Dynamic mode decision - EXACTLY replicates LiquidBounce logic
     * Only queue when server position is closer to enemy than client position
     */
    private boolean shouldQueueDynamic() {
        // No enemy nearby - don't queue
        if (!isEnemyNearby) {
            return false;
        }
        
        // Get server position (first position in queue, or current if empty)
        Vec3 serverPosition = getServerPosition();
        if (serverPosition == null) {
            // No queue yet, start queueing to build position history
            return true;
        }
        
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
        
        // Create server-side player box for intersection check
        AxisAlignedBB serverBox = new AxisAlignedBB(
            serverPosition.xCoord - 0.3, serverPosition.yCoord, serverPosition.zCoord - 0.3,
            serverPosition.xCoord + 0.3, serverPosition.yCoord + 1.8, serverPosition.zCoord + 0.3
        );
        
        // Check if enemy's hitbox intersects server position
        AxisAlignedBB enemyBox = closestEnemy.getEntityBoundingBox();
        if (serverBox.intersectsWith(enemyBox)) {
            // We'd get hit at server position - DON'T queue (flush instead)
            return false;
        }
        
        // Only queue if server position is closer (gives us advantage by staying further)
        // This is the CORE of FakeLag - we stay at the further (client) position while
        // the server thinks we're at the closer (server) position
        return serverDistance < clientDistance;
    }
    
    /**
     * Get server-side position from queued movement packets
     * This is where the server THINKS we are
     */
    private Vec3 getServerPosition() {
        // Find first movement packet in queue
        for (PacketSnapshot snapshot : packetQueue) {
            if (snapshot.packet instanceof C03PacketPlayer) {
                C03PacketPlayer movePacket = (C03PacketPlayer) snapshot.packet;
                if (movePacket.isMoving()) {
                    return new Vec3(movePacket.getPositionX(), movePacket.getPositionY(), movePacket.getPositionZ());
                }
            }
        }
        
        // No movement packets queued yet - return current position
        return null;
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
     * Flush all queued packets in order
     * This prevents rubberbanding by sending packets in EXACT order received
     */
    private void flushAllPackets() {
        if (packetQueue.isEmpty()) {
            return;
        }
        
        // Send all packets in order (CRITICAL - maintains packet order)
        while (!packetQueue.isEmpty()) {
            PacketSnapshot snapshot = packetQueue.poll();
            if (snapshot != null && snapshot.packet != null && mc.getNetHandler() != null) {
                try {
                    mc.getNetHandler().addToSendQueue(snapshot.packet);
                } catch (Exception e) {
                    // Ignore packet send errors during flush
                }
            }
        }
        
        // Reset queue start time
        queueStartTime = 0;
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
        if (queueStartTime == 0) return false;
        
        // Check actual elapsed time since queue started
        long elapsed = System.currentTimeMillis() - queueStartTime;
        return elapsed >= nextDelay;
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
        if (packetQueue.isEmpty()) {
            return new String[]{mode.getModeString()};
        }
        return new String[]{packetQueue.size() + " queued"};
    }
}
