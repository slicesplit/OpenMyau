package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.*;
import myau.module.Module;
import myau.property.properties.*;
import myau.util.TeamUtil;
import myau.util.RenderUtil;
import myau.mixin.IAccessorRenderManager;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.*;
import net.minecraft.network.play.client.*;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

import java.awt.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * NewBacktrack - GrimAC Compatible Backtrack
 * 
 * GRIM-SAFE DESIGN:
 * - Only tracks server-side entity positions
 * - NO packet cancellation (prevents bad packets flag)
 * - NO camera manipulation
 * - Pure visual tracking for hit optimization
 */
@ModuleInfo(category = ModuleCategory.COMBAT)
public class NewBacktrack extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    // Range settings
    public final FloatProperty minRange = new FloatProperty("min-range", 2.5f, 0.0f, 8.0f);
    public final FloatProperty maxRange = new FloatProperty("max-range", 4.0f, 0.0f, 8.0f);
    
    // Timing settings
    public final IntProperty maxTrackTime = new IntProperty("max-track-time", 200, 50, 1000);
    public final IntProperty cooldownTime = new IntProperty("cooldown-time", 100, 0, 500);
    
    // Behavior
    public final BooleanProperty pauseOnHurtTime = new BooleanProperty("pause-on-hurt", false);
    public final IntProperty hurtTimePause = new IntProperty("hurt-time-pause", 3, 0, 10, () -> pauseOnHurtTime.getValue());
    public final FloatProperty activationChance = new FloatProperty("chance", 80.0f, 0.0f, 100.0f);
    
    // Mode
    public final ModeProperty targetMode = new ModeProperty("target-mode", 0, new String[]{"Attack", "Range"});
    public final IntProperty attackTimeWindow = new IntProperty("attack-time-window", 1000, 0, 5000);
    
    // Rendering
    public final BooleanProperty renderServerPos = new BooleanProperty("render-server-pos", true);
    public final ColorProperty color = new ColorProperty("color", 0x87CEEB); // Sky blue

    // Tracking data per player
    private final Map<Integer, TrackedPlayer> trackedPlayers = new ConcurrentHashMap<>();
    private final Random random = new Random();
    
    private EntityPlayer currentTarget = null;
    private long lastAttackTime = 0L;
    private long nextBacktrackAllowed = 0L;
    private boolean shouldTrack = false;

    public NewBacktrack() {
        super("NewBacktrack", false);
    }

    @Override
    public void onEnabled() {
        trackedPlayers.clear();
        currentTarget = null;
        lastAttackTime = 0L;
        nextBacktrackAllowed = 0L;
        shouldTrack = false;
    }

    @Override
    public void onDisabled() {
        trackedPlayers.clear();
        currentTarget = null;
        shouldTrack = false;
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || event.getType() != EventType.RECEIVE) {
            return;
        }

        Packet packet = event.getPacket();
        
        // CRITICAL: Reset on server teleport/disconnect (prevents desync)
        if (packet instanceof S08PacketPlayerPosLook || packet instanceof S40PacketDisconnect) {
            trackedPlayers.clear();
            currentTarget = null;
            return;
        }

        // Reset on death
        if (packet instanceof S06PacketUpdateHealth) {
            S06PacketUpdateHealth healthPacket = (S06PacketUpdateHealth) packet;
            if (healthPacket.getHealth() <= 0) {
                trackedPlayers.clear();
                currentTarget = null;
                return;
            }
        }

        // GRIM-SAFE: Only track entity positions, DON'T CANCEL PACKETS
        // This prevents "bad packets" flag while still allowing hit optimization
        if (packet instanceof S14PacketEntity) {
            handleEntityMovement((S14PacketEntity) packet);
        } else if (packet instanceof S18PacketEntityTeleport) {
            handleEntityTeleport((S18PacketEntityTeleport) packet);
        }
    }

    private void handleEntityMovement(S14PacketEntity packet) {
        int entityId = packet.getEntity(mc.theWorld).getEntityId();
        
        TrackedPlayer tracked = trackedPlayers.get(entityId);
        if (tracked == null) return;

        // Update server position based on movement
        double dx = packet.func_149062_c() / 32.0; // getDeltaX
        double dy = packet.func_149061_d() / 32.0; // getDeltaY
        double dz = packet.func_149064_e() / 32.0; // getDeltaZ
        
        tracked.updatePosition(
            tracked.serverX + dx,
            tracked.serverY + dy,
            tracked.serverZ + dz
        );
    }

    private void handleEntityTeleport(S18PacketEntityTeleport packet) {
        int entityId = packet.getEntityId();
        
        TrackedPlayer tracked = trackedPlayers.get(entityId);
        if (tracked == null) return;

        // Update to exact teleport position
        tracked.updatePosition(
            packet.getX() / 32.0,
            packet.getY() / 32.0,
            packet.getZ() / 32.0
        );
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        long currentTime = System.currentTimeMillis();

        // Clean up old tracked players
        trackedPlayers.entrySet().removeIf(entry -> {
            TrackedPlayer tracked = entry.getValue();
            Entity entity = mc.theWorld.getEntityByID(entry.getKey());
            
            // Remove if entity is gone or tracking expired
            return entity == null || !entity.isEntityAlive() || 
                   (currentTime - tracked.startTrackTime) > maxTrackTime.getValue();
        });

        // Update current target tracking
        if ("Range".equals(targetMode.getModeString())) {
            updateRangeTarget();
        }
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }

        lastAttackTime = System.currentTimeMillis();

        if ("Attack".equals(targetMode.getModeString()) && event.getTarget() instanceof EntityPlayer) {
            updateAttackTarget((EntityPlayer) event.getTarget());
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        if ("Range".equals(targetMode.getModeString())) {
            updateRangeTarget();
        }
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        trackedPlayers.clear();
        currentTarget = null;
    }
    
    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null || !renderServerPos.getValue()) {
            return;
        }
        
        if (currentTarget == null) {
            return;
        }

        TrackedPlayer tracked = trackedPlayers.get(currentTarget.getEntityId());
        if (tracked == null) {
            return;
        }

        // Render the smooth server position
        Vec3 renderPos = tracked.getSmoothedPosition();
        
        double renderX = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosX();
        double renderY = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosY();
        double renderZ = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosZ();
        
        double x = renderPos.xCoord - renderX;
        double y = renderPos.yCoord - renderY;
        double z = renderPos.zCoord - renderZ;
        
        // Create bounding box at server position
        AxisAlignedBB box = new AxisAlignedBB(
            x - 0.3, y, z - 0.3,
            x + 0.3, y + 1.8, z + 0.3
        );
        
        Color c = new Color(color.getValue());
        
        // Render with transparency
        RenderUtil.drawFilledBox(box, c.getRed(), c.getGreen(), c.getBlue());
        RenderUtil.drawBoundingBox(box, c.getRed(), c.getGreen(), c.getBlue(), 180, 2.0F);
    }

    private void updateAttackTarget(EntityPlayer target) {
        if (!shouldTrackPlayer(target)) {
            return;
        }

        currentTarget = target;
        ensureTracking(target);
    }

    private void updateRangeTarget() {
        EntityPlayer nearest = findNearestEnemy();
        
        if (nearest == null || !shouldTrackPlayer(nearest)) {
            currentTarget = null;
            return;
        }

        currentTarget = nearest;
        ensureTracking(nearest);
    }

    private void ensureTracking(EntityPlayer player) {
        if (player == null) return;

        int entityId = player.getEntityId();
        
        trackedPlayers.computeIfAbsent(entityId, id -> 
            new TrackedPlayer(player.posX, player.posY, player.posZ)
        );
    }

    private boolean shouldTrackPlayer(EntityPlayer player) {
        if (player == null || player.isDead || player.getHealth() <= 0) {
            return false;
        }

        if (TeamUtil.isFriend(player)) {
            return false;
        }

        long currentTime = System.currentTimeMillis();
        
        // Check cooldown
        if (currentTime < nextBacktrackAllowed) {
            return false;
        }

        // Check attack window
        long timeSinceAttack = currentTime - lastAttackTime;
        if (timeSinceAttack > attackTimeWindow.getValue()) {
            return false;
        }

        // Check hurt time pause
        if (pauseOnHurtTime.getValue() && player.hurtTime >= hurtTimePause.getValue()) {
            return false;
        }

        // Check range
        double dist = mc.thePlayer.getDistanceToEntity(player);
        if (dist < minRange.getValue() || dist > maxRange.getValue()) {
            return false;
        }

        // Check activation chance
        if (random.nextInt(100) >= activationChance.getValue()) {
            return false;
        }

        return true;
    }

    private EntityPlayer findNearestEnemy() {
        EntityPlayer nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (Object obj : mc.theWorld.playerEntities) {
            if (!(obj instanceof EntityPlayer)) continue;
            
            EntityPlayer player = (EntityPlayer) obj;
            
            if (player == mc.thePlayer || TeamUtil.isFriend(player)) {
                continue;
            }

            if (player.isDead || player.getHealth() <= 0) {
                continue;
            }

            double dist = mc.thePlayer.getDistanceToEntity(player);
            if (dist < nearestDist && dist <= maxRange.getValue()) {
                nearest = player;
                nearestDist = dist;
            }
        }

        return nearest;
    }

    /**
     * Tracks a player's server-side position with smooth interpolation
     */
    private static class TrackedPlayer {
        // Server position (from packets)
        double serverX, serverY, serverZ;
        
        // Smoothed render position
        double smoothX, smoothY, smoothZ;
        
        // Tracking metadata
        final long startTrackTime;
        long lastUpdateTime;
        
        TrackedPlayer(double x, double y, double z) {
            this.serverX = x;
            this.serverY = y;
            this.serverZ = z;
            
            this.smoothX = x;
            this.smoothY = y;
            this.smoothZ = z;
            
            this.startTrackTime = System.currentTimeMillis();
            this.lastUpdateTime = this.startTrackTime;
        }
        
        void updatePosition(double x, double y, double z) {
            this.serverX = x;
            this.serverY = y;
            this.serverZ = z;
            this.lastUpdateTime = System.currentTimeMillis();
        }
        
        Vec3 getSmoothedPosition() {
            // Ultra-smooth interpolation for buttery rendering
            double dx = serverX - smoothX;
            double dy = serverY - smoothY;
            double dz = serverZ - smoothZ;
            double distSq = dx * dx + dy * dy + dz * dz;
            
            // Only smooth if position changed (anti-flicker)
            if (distSq > 0.0004) { // 0.02 blocks squared
                // Adaptive smoothing based on distance
                double distance = Math.sqrt(distSq);
                double smoothFactor;
                
                if (distance < 0.1) {
                    smoothFactor = 0.08; // Ultra-smooth for tiny movements
                } else if (distance < 0.5) {
                    smoothFactor = 0.12; // Smooth for small movements
                } else if (distance < 2.0) {
                    smoothFactor = 0.18; // Balanced for medium movements
                } else {
                    smoothFactor = 0.25; // Faster for large movements
                }
                
                // Cubic easing for silk-smooth transitions
                double t = smoothFactor;
                double eased;
                if (t < 0.5) {
                    eased = 4.0 * t * t * t;
                } else {
                    double f = 2.0 * t - 2.0;
                    eased = 0.5 * f * f * f + 1.0;
                }
                
                smoothX += dx * eased;
                smoothY += dy * eased;
                smoothZ += dz * eased;
            }
            
            return new Vec3(smoothX, smoothY, smoothZ);
        }
    }
}
