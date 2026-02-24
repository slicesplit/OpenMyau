package myau.module.modules;

import myau.Myau;
import myau.enums.ModuleCategory;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.*;
import myau.module.Module;
import myau.module.ModuleInfo;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ColorProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.PacketUtil;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.handshake.client.C00Handshake;
import net.minecraft.network.play.server.*;
import net.minecraft.network.status.client.C00PacketServerQuery;
import net.minecraft.network.status.server.S01PacketPong;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;
import org.lwjgl.opengl.GL11;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@ModuleInfo(category = ModuleCategory.COMBAT)
public class Backtrack extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    // Raytrace flag — set by MixinEntityRenderer around getMouseOver
    public static volatile boolean isRaytracing = false;

    // ── Settings ──────────────────────────────────────────────────────────────

    public final ModeProperty mode = new ModeProperty("Mode", 0, new String[]{"Legacy", "Modern"});
    public final IntProperty maxDelay = new IntProperty("MaxDelay", 200, 0, 2000);
    public final IntProperty minDelay = new IntProperty("MinDelay", 80, 0, 2000);

    // Legacy
    public final ModeProperty legacyPos = new ModeProperty("CachingMode", 0, new String[]{"ClientPos", "ServerPos"});
    public final IntProperty maxCachedPositions = new IntProperty("MaxCachedPositions", 10, 1, 20);

    // Modern
    public final BooleanProperty smart = new BooleanProperty("Smart", true);

    // ESP
    public final ModeProperty espMode = new ModeProperty("ESPMode", 1, new String[]{"None", "Box", "Model", "Wireframe"});
    public final BooleanProperty renderEnabled = new BooleanProperty("Render", true);
    public final ColorProperty espColor = new ColorProperty("ESPColor", 0x00FF00);

    // ── Legacy data ───────────────────────────────────────────────────────────

    private static final class BacktrackData {
        final double x, y, z;
        final long time;
        BacktrackData(double x, double y, double z, long time) {
            this.x = x; this.y = y; this.z = z; this.time = time;
        }
    }

    private final ConcurrentHashMap<UUID, List<BacktrackData>> backtrackedPlayers = new ConcurrentHashMap<>();

    // ── Modern data ───────────────────────────────────────────────────────────

    private static final class QueueData {
        final Packet<?> packet;
        final long time;
        QueueData(Packet<?> p, long t) { packet = p; time = t; }
    }

    private final ConcurrentLinkedQueue<QueueData> packetQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<long[]> positions = new ConcurrentLinkedQueue<>(); // [x bits, y bits, z bits, time]

    private EntityLivingBase target = null;
    private boolean shouldRender = false;
    private int modernDelay = 80;
    private long delayForNextBacktrack = 0L;

    // ── Constructor ───────────────────────────────────────────────────────────

    public Backtrack() {
        super("Backtrack", false);
    }

    @Override
    public void onEnabled() {
        backtrackedPlayers.clear();
        packetQueue.clear();
        positions.clear();
        target = null;
        shouldRender = false;
        isRaytracing = false;
        modernDelay = randomDelay();
    }

    @Override
    public void onDisabled() {
        clearPackets(true);
        backtrackedPlayers.clear();
        target = null;
        isRaytracing = false;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int randomDelay() {
        int lo = Math.min(minDelay.getValue(), maxDelay.getValue());
        int hi = Math.max(minDelay.getValue(), maxDelay.getValue());
        return lo == hi ? lo : lo + (int)(Math.random() * (hi - lo));
    }

    private int supposedDelay() {
        return mode.getModeString().equals("Modern") ? modernDelay : maxDelay.getValue();
    }

    private boolean shouldBacktrack() {
        return mc.thePlayer != null && mc.theWorld != null && target != null
                && mc.thePlayer.getHealth() > 0
                && (target.getHealth() > 0 || Float.isNaN(target.getHealth()))
                && System.currentTimeMillis() >= delayForNextBacktrack;
    }

    // ── Event: Entity movement (Legacy ClientPos) ─────────────────────────────

    @EventTarget
    public void onEntityMove(EntityMovementEvent event) {
        if (!mode.getModeString().equals("Legacy") || !legacyPos.getModeString().equals("ClientPos")) return;
        Entity entity = event.getEntity();
        if (!(entity instanceof EntityPlayer)) return;
        EntityPlayer player = (EntityPlayer) entity;
        if (player == mc.thePlayer) return;
        if (TeamUtil.isFriend(player) || TeamUtil.isBot(player)) return;

        addBacktrackData(player.getUniqueID(), player.posX, player.posY, player.posZ, System.currentTimeMillis());
    }

    // ── Event: Packets ────────────────────────────────────────────────────────

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (mc.theWorld == null) return;
        Packet<?> packet = event.getPacket();

        // ── Legacy mode packet handling ──────────────────────────────────────
        if (mode.getModeString().equals("Legacy") && event.getType() == EventType.RECEIVE) {
            if (packet instanceof S0CPacketSpawnPlayer) {
                S0CPacketSpawnPlayer spawn = (S0CPacketSpawnPlayer) packet;
                addBacktrackData(spawn.getPlayer(),
                        spawn.getX() / 32.0,
                        spawn.getY() / 32.0,
                        spawn.getZ() / 32.0,
                        System.currentTimeMillis());
            } else if (legacyPos.getModeString().equals("ServerPos")) {
                if (packet instanceof S14PacketEntity) {
                    S14PacketEntity p = (S14PacketEntity) packet;
                    Entity entity = p.getEntity(mc.theWorld);
                    if (entity instanceof EntityPlayer && entity != mc.thePlayer) {
                        addBacktrackData(entity.getUniqueID(), entity.posX, entity.posY, entity.posZ, System.currentTimeMillis());
                    }
                } else if (packet instanceof S18PacketEntityTeleport) {
                    S18PacketEntityTeleport p = (S18PacketEntityTeleport) packet;
                    Entity entity = mc.theWorld.getEntityByID(p.getEntityId());
                    if (entity instanceof EntityPlayer && entity != mc.thePlayer) {
                        addBacktrackData(entity.getUniqueID(), entity.posX, entity.posY, entity.posZ, System.currentTimeMillis());
                    }
                }
            }
            return;
        }

        // ── Modern mode packet handling ───────────────────────────────────────
        if (!mode.getModeString().equals("Modern")) return;

        // Never queue on singleplayer or when not on a server
        if (((myau.mixin.IAccessorMinecraft)(Object)mc).isIntegratedServerRunning() || ((myau.mixin.IAccessorMinecraft)(Object)mc).getCurrentServerData() == null) {
            clearPackets(true);
            return;
        }

        // Only handle incoming packets
        if (event.getType() != EventType.RECEIVE) return;

        // NEVER queue critical / always-pass-through packets — these cause kick if delayed
        if (packet instanceof net.minecraft.network.play.server.S01PacketJoinGame
                || packet instanceof net.minecraft.network.play.server.S07PacketRespawn
                || packet instanceof net.minecraft.network.play.server.S08PacketPlayerPosLook
                || packet instanceof net.minecraft.network.play.server.S00PacketKeepAlive
                || packet instanceof net.minecraft.network.play.server.S40PacketDisconnect
                || packet instanceof net.minecraft.network.play.server.S02PacketChat
                || packet instanceof net.minecraft.network.play.server.S37PacketStatistics
                || packet instanceof net.minecraft.network.play.server.S38PacketPlayerListItem
                || packet instanceof S01PacketPong
                || packet instanceof C00Handshake
                || packet instanceof C00PacketServerQuery) return;

        // Pass through: hurt/death sounds — player needs to hear these immediately
        if (packet instanceof S29PacketSoundEffect) {
            String name = ((S29PacketSoundEffect) packet).getSoundName();
            if (name.contains("game.player.hurt") || name.contains("game.player.die")) return;
        }

        // Flush and stop on own death
        if (packet instanceof S06PacketUpdateHealth) {
            if (((S06PacketUpdateHealth) packet).getHealth() <= 0) {
                clearPackets(true);
                return;
            }
        }

        // Flush on target entity destroy
        if (packet instanceof S13PacketDestroyEntities) {
            S13PacketDestroyEntities p = (S13PacketDestroyEntities) packet;
            if (target != null) {
                for (int id : p.getEntityIDs()) {
                    if (id == target.getEntityId()) {
                        clearPackets(true);
                        resetTarget();
                        return;
                    }
                }
            }
        }

        // ONLY queue if we actually have a target — never queue blindly
        if (target == null || !shouldBacktrack()) {
            // No target: flush any stale packets and pass through
            clearPackets(true);
            return;
        }

        // Track target entity position for range calculation
        if (packet instanceof S14PacketEntity) {
            S14PacketEntity p = (S14PacketEntity) packet;
            if (p.getEntity(mc.theWorld) == target) {
                positions.add(new long[]{
                        Double.doubleToRawLongBits(target.posX),
                        Double.doubleToRawLongBits(target.posY),
                        Double.doubleToRawLongBits(target.posZ),
                        System.currentTimeMillis()
                });
            }
        } else if (packet instanceof S18PacketEntityTeleport) {
            S18PacketEntityTeleport p = (S18PacketEntityTeleport) packet;
            if (p.getEntityId() == target.getEntityId()) {
                positions.add(new long[]{
                        Double.doubleToRawLongBits(target.posX),
                        Double.doubleToRawLongBits(target.posY),
                        Double.doubleToRawLongBits(target.posZ),
                        System.currentTimeMillis()
                });
            }
        }

        // Queue the packet (hold it back from the game)
        event.setCancelled(true);
        packetQueue.add(new QueueData(packet, System.currentTimeMillis()));
    }

    // ── Event: Tick ───────────────────────────────────────────────────────────

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE) return;

        if (mode.getModeString().equals("Legacy")) {
            // Prune stale legacy entries
            for (Map.Entry<UUID, List<BacktrackData>> entry : backtrackedPlayers.entrySet()) {
                List<BacktrackData> data = entry.getValue();
                data.removeIf(d -> d.time + maxDelay.getValue() < System.currentTimeMillis());
                if (data.isEmpty()) backtrackedPlayers.remove(entry.getKey());
            }
            return;
        }

        // Modern mode game-loop equivalent
        if (mc.thePlayer == null || mc.theWorld == null) return;

        EntityLivingBase t = target;
        if (mode.getModeString().equals("Modern")) {
            if (shouldBacktrack() && t != null) {
                double dist = mc.thePlayer.getDistanceSq(t.posX + (t.getEntityBoundingBox().maxX - t.getEntityBoundingBox().minX) / 2, t.posY + (t.getEntityBoundingBox().maxY - t.getEntityBoundingBox().minY) / 2, t.posZ + (t.getEntityBoundingBox().maxZ - t.getEntityBoundingBox().minZ) / 2);
                // Within 6 blocks true range
                double trueDist = mc.thePlayer.getDistance(t.posX, t.posY, t.posZ);
                if (trueDist <= 6.0) {
                    shouldRender = true;
                    handleModernPackets();
                } else {
                    clear();
                }
            } else {
                clear();
            }

            // Refresh delay when queue empties
            if (packetQueue.isEmpty() && positions.isEmpty()) {
                modernDelay = randomDelay();
            }
        }
    }

    // ── Event: Attack ─────────────────────────────────────────────────────────

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!mode.getModeString().equals("Modern")) return;
        Entity attacked = event.getTarget();
        if (!(attacked instanceof EntityLivingBase)) return;

        if (target != attacked) {
            clearPackets(true);
            resetTarget();
        }
        target = (EntityLivingBase) attacked;
    }

    // ── Event: World load ─────────────────────────────────────────────────────

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        clearPackets(false);
        backtrackedPlayers.clear();
        target = null;
    }

    // ── Event: 3D Render ──────────────────────────────────────────────────────

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!renderEnabled.getValue() || mc.theWorld == null) return;

        if (mode.getModeString().equals("Legacy")) {
            // Draw trail lines for all tracked players
            int rgb = espColor.getValue();
            float r = ((rgb >> 16) & 0xFF) / 255f;
            float g = ((rgb >> 8) & 0xFF) / 255f;
            float b = (rgb & 0xFF) / 255f;

            for (Object obj : mc.theWorld.loadedEntityList) {
                if (!(obj instanceof EntityPlayer)) continue;
                EntityPlayer player = (EntityPlayer) obj;
                if (player == mc.thePlayer) continue;

                List<BacktrackData> dataList = backtrackedPlayers.get(player.getUniqueID());
                if (dataList == null || dataList.isEmpty()) continue;

                double rpX = mc.getRenderManager().viewerPosX;
                double rpY = mc.getRenderManager().viewerPosY;
                double rpZ = mc.getRenderManager().viewerPosZ;

                GL11.glPushMatrix();
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL11.glEnable(GL11.GL_LINE_SMOOTH);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                mc.entityRenderer.disableLightmap();

                GL11.glBegin(GL11.GL_LINE_STRIP);
                GL11.glColor4f(r, g, b, 1f);

                synchronized (dataList) {
                    for (BacktrackData data : dataList) {
                        GL11.glVertex3d(data.x - rpX, data.y - rpY, data.z - rpZ);
                    }
                }

                GL11.glColor4d(1.0, 1.0, 1.0, 1.0);
                GL11.glEnd();
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDisable(GL11.GL_LINE_SMOOTH);
                GL11.glDisable(GL11.GL_BLEND);
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                GL11.glPopMatrix();
            }
            return;
        }

        // Modern ESP
        if (!mode.getModeString().equals("Modern") || !shouldBacktrack() || !shouldRender || target == null) return;

        int rgb = espColor.getValue();
        float r = ((rgb >> 16) & 0xFF) / 255f;
        float g = ((rgb >> 8) & 0xFF) / 255f;
        float b2 = (rgb & 0xFF) / 255f;

        double rpX = mc.getRenderManager().viewerPosX;
        double rpY = mc.getRenderManager().viewerPosY;
        double rpZ = mc.getRenderManager().viewerPosZ;

        double ex = target.posX - rpX;
        double ey = target.posY - rpY;
        double ez = target.posZ - rpZ;

        String esp = espMode.getModeString().toLowerCase();

        if (esp.equals("box")) {
            AxisAlignedBB bb = target.getEntityBoundingBox().offset(-target.posX + ex + rpX, -target.posY + ey + rpY, -target.posZ + ez + rpZ);
            GL11.glPushMatrix();
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_LINE_SMOOTH);
            GL11.glColor4f(r, g, b2, 1f);
            GL11.glBegin(GL11.GL_LINE_STRIP);
            // bottom face
            GL11.glVertex3d(bb.minX - rpX, bb.minY - rpY, bb.minZ - rpZ);
            GL11.glVertex3d(bb.maxX - rpX, bb.minY - rpY, bb.minZ - rpZ);
            GL11.glVertex3d(bb.maxX - rpX, bb.minY - rpY, bb.maxZ - rpZ);
            GL11.glVertex3d(bb.minX - rpX, bb.minY - rpY, bb.maxZ - rpZ);
            GL11.glVertex3d(bb.minX - rpX, bb.minY - rpY, bb.minZ - rpZ);
            // up to top
            GL11.glVertex3d(bb.minX - rpX, bb.maxY - rpY, bb.minZ - rpZ);
            GL11.glVertex3d(bb.maxX - rpX, bb.maxY - rpY, bb.minZ - rpZ);
            GL11.glVertex3d(bb.maxX - rpX, bb.maxY - rpY, bb.maxZ - rpZ);
            GL11.glVertex3d(bb.minX - rpX, bb.maxY - rpY, bb.maxZ - rpZ);
            GL11.glVertex3d(bb.minX - rpX, bb.maxY - rpY, bb.minZ - rpZ);
            GL11.glEnd();
            // vertical lines
            GL11.glBegin(GL11.GL_LINES);
            GL11.glVertex3d(bb.maxX - rpX, bb.minY - rpY, bb.minZ - rpZ);
            GL11.glVertex3d(bb.maxX - rpX, bb.maxY - rpY, bb.minZ - rpZ);
            GL11.glVertex3d(bb.maxX - rpX, bb.minY - rpY, bb.maxZ - rpZ);
            GL11.glVertex3d(bb.maxX - rpX, bb.maxY - rpY, bb.maxZ - rpZ);
            GL11.glVertex3d(bb.minX - rpX, bb.minY - rpY, bb.maxZ - rpZ);
            GL11.glVertex3d(bb.minX - rpX, bb.maxY - rpY, bb.maxZ - rpZ);
            GL11.glEnd();
            GL11.glColor4d(1, 1, 1, 1);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_LINE_SMOOTH);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glPopMatrix();
        } else if (esp.equals("model")) {
            float partialTicks = event.getPartialTicks();
            GL11.glPushMatrix();
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glColor4f(0.6f, 0.6f, 0.6f, 1f);
            mc.getRenderManager().doRenderEntity(target, ex, ey, ez,
                    target.prevRotationYaw + (target.rotationYaw - target.prevRotationYaw) * partialTicks,
                    partialTicks, true);
            GL11.glPopAttrib();
            GL11.glPopMatrix();
        } else if (esp.equals("wireframe")) {
            float partialTicks = event.getPartialTicks();
            GL11.glPushMatrix();
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_LINE);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_LINE_SMOOTH);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(r, g, b2, 1f);
            mc.getRenderManager().doRenderEntity(target, ex, ey, ez,
                    target.prevRotationYaw + (target.rotationYaw - target.prevRotationYaw) * partialTicks,
                    partialTicks, true);
            GL11.glPopAttrib();
            GL11.glPopMatrix();
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private void handleModernPackets() {
        long threshold = System.currentTimeMillis() - supposedDelay();
        packetQueue.removeIf(qd -> {
            if (qd.time <= threshold) {
                PacketUtil.sendPacketSafe(qd.packet);
                return true;
            }
            return false;
        });
        positions.removeIf(pos -> pos[3] < threshold);
    }

    private void clearPackets(boolean flush) {
        if (flush) {
            for (QueueData qd : packetQueue) {
                PacketUtil.sendPacketSafe(qd.packet);
            }
        }
        packetQueue.clear();
        positions.clear();
        shouldRender = false;
    }

    private void clear() {
        clearPackets(true);
    }

    private void resetTarget() {
        target = null;
        modernDelay = randomDelay();
    }

    // ── Legacy data management ────────────────────────────────────────────────

    private void addBacktrackData(UUID id, double x, double y, double z, long time) {
        List<BacktrackData> data = backtrackedPlayers.computeIfAbsent(id, k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (data) {
            if (data.size() >= maxCachedPositions.getValue()) data.remove(0);
            data.add(new BacktrackData(x, y, z, time));
        }
    }

    // ── Public API for mixins ─────────────────────────────────────────────────

    /**
     * Loops through backtrack data for an entity, temporarily moving it to each
     * cached position and calling action. Used by MixinEntityRenderer raytrace.
     * Returns false from action to continue, true to stop early.
     */
    public void loopThroughBacktrackData(Entity entity, Runnable action) {
        if (!isEnabled() || !(entity instanceof EntityPlayer)) return;
        if (mode.getModeString().equals("Modern")) return;

        EntityPlayer player = (EntityPlayer) entity;
        List<BacktrackData> dataList = backtrackedPlayers.get(player.getUniqueID());
        if (dataList == null || dataList.isEmpty()) return;

        double origX = entity.posX, origY = entity.posY, origZ = entity.posZ;
        double origPrevX = entity.prevPosX, origPrevY = entity.prevPosY, origPrevZ = entity.prevPosZ;

        List<BacktrackData> snapshot;
        synchronized (dataList) { snapshot = new ArrayList<>(dataList); }

        for (int i = snapshot.size() - 1; i >= 0; i--) {
            BacktrackData d = snapshot.get(i);
            entity.posX = d.x; entity.posY = d.y; entity.posZ = d.z;
            entity.prevPosX = d.x; entity.prevPosY = d.y; entity.prevPosZ = d.z;
            entity.setEntityBoundingBox(entity.getEntityBoundingBox().offset(d.x - origX, d.y - origY, d.z - origZ));
            action.run();
            entity.setEntityBoundingBox(entity.getEntityBoundingBox().offset(origX - d.x, origY - d.y, origZ - d.z));
        }

        entity.posX = origX; entity.posY = origY; entity.posZ = origZ;
        entity.prevPosX = origPrevX; entity.prevPosY = origPrevY; entity.prevPosZ = origPrevZ;
    }

    /**
     * Returns an expanded AABB covering all cached backtrack positions.
     * Called from MixinEntity.onGetBoundingBox during raytrace.
     */
    public AxisAlignedBB getExpandedHitbox(Entity entity, AxisAlignedBB original) {
        if (!(entity instanceof EntityPlayer)) return null;
        EntityPlayer player = (EntityPlayer) entity;
        if (player == mc.thePlayer) return null;

        List<BacktrackData> dataList = backtrackedPlayers.get(player.getUniqueID());
        if (dataList == null || dataList.isEmpty()) return null;

        double minX = original.minX, minY = original.minY, minZ = original.minZ;
        double maxX = original.maxX, maxY = original.maxY, maxZ = original.maxZ;
        double origX = entity.posX, origY = entity.posY, origZ = entity.posZ;

        List<BacktrackData> snapshot;
        synchronized (dataList) { snapshot = new ArrayList<>(dataList); }

        for (BacktrackData d : snapshot) {
            double dx = d.x - origX, dy = d.y - origY, dz = d.z - origZ;
            minX = Math.min(minX, original.minX + dx);
            minY = Math.min(minY, original.minY + dy);
            minZ = Math.min(minZ, original.minZ + dz);
            maxX = Math.max(maxX, original.maxX + dx);
            maxY = Math.max(maxY, original.maxY + dy);
            maxZ = Math.max(maxZ, original.maxZ + dz);
        }
        return new AxisAlignedBB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Returns the nearest cached distance to this entity from the local player.
     */
    public double getNearestTrackedDistance(Entity entity) {
        if (!(entity instanceof EntityPlayer) || mc.thePlayer == null) return 0.0;
        List<BacktrackData> dataList = backtrackedPlayers.get(entity.getUniqueID());
        if (dataList == null || dataList.isEmpty()) return 0.0;

        double origX = entity.posX, origY = entity.posY, origZ = entity.posZ;
        double origPX = entity.prevPosX, origPY = entity.prevPosY, origPZ = entity.prevPosZ;

        List<BacktrackData> snapshot;
        synchronized (dataList) { snapshot = new ArrayList<>(dataList); }

        double nearest = Double.MAX_VALUE;
        for (BacktrackData d : snapshot) {
            entity.posX = d.x; entity.posY = d.y; entity.posZ = d.z;
            entity.prevPosX = d.x; entity.prevPosY = d.y; entity.prevPosZ = d.z;
            double dist = entity.getDistanceSq(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
            if (dist < nearest) nearest = dist;
        }
        entity.posX = origX; entity.posY = origY; entity.posZ = origZ;
        entity.prevPosX = origPX; entity.prevPosY = origPY; entity.prevPosZ = origPZ;
        return nearest == Double.MAX_VALUE ? 0.0 : nearest;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{String.valueOf(supposedDelay())};
    }
}
