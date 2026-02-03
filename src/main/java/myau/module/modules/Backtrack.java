package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.*;
import myau.module.Module;
import myau.property.properties.*;
import myau.mixin.IAccessorRenderManager;
import myau.util.RenderUtil;
import myau.util.RotationUtil;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.util.AxisAlignedBB;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class Backtrack extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"MANUAL", "LAG_BASED"});
    public final IntProperty ticks = new IntProperty("ticks", 10, 1, 20);
    public final BooleanProperty renderPreviousTicks = new BooleanProperty("render-previous-ticks", true);
    public final BooleanProperty renderServerPos = new BooleanProperty("render-server-pos", true);
    public final ColorProperty color = new ColorProperty("color", 0xFF0000);
    public final IntProperty latency = new IntProperty("latency", 100, 0, 500);

    private final Map<Integer, LinkedList<PositionData>> entityPositions = new ConcurrentHashMap<>();
    private final Map<Integer, Vec3d> serverPositions = new ConcurrentHashMap<>();
    private final LinkedList<Packet<?>> delayedPackets = new LinkedList<>();
    private long lastPacketTime = 0L;
    private boolean delayingPackets = false;

    public Backtrack() {
        super("Backtrack", false);
    }

    @Override
    public void onEnabled() {
        entityPositions.clear();
        serverPositions.clear();
        delayedPackets.clear();
        lastPacketTime = System.currentTimeMillis();
        delayingPackets = false;
    }

    @Override
    public void onDisabled() {
        releaseDelayedPackets();
        entityPositions.clear();
        serverPositions.clear();
    }

    @EventTarget(Priority.HIGHEST)
    public void onPacketReceive(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        Packet<?> packet = event.getPacket();
        
        if (packet instanceof S14PacketEntity) {
            S14PacketEntity entityPacket = (S14PacketEntity) packet;
            int entityId = entityPacket.getEntity(mc.theWorld) != null ? entityPacket.getEntity(mc.theWorld).getEntityId() : -1;
            
            if (entityId != -1 && entityPacket.getEntity(mc.theWorld) instanceof EntityPlayer) {
                EntityPlayer player = (EntityPlayer) entityPacket.getEntity(mc.theWorld);
                updateServerPosition(player);
            }
        } else if (packet instanceof S18PacketEntityTeleport) {
            S18PacketEntityTeleport teleportPacket = (S18PacketEntityTeleport) packet;
            int entityId = teleportPacket.getEntityId();
            
            if (mc.theWorld.getEntityByID(entityId) instanceof EntityPlayer) {
                EntityPlayer player = (EntityPlayer) mc.theWorld.getEntityByID(entityId);
                if (player != null) {
                    serverPositions.put(entityId, new Vec3d(
                        teleportPacket.getX() / 32.0,
                        teleportPacket.getY() / 32.0,
                        teleportPacket.getZ() / 32.0
                    ));
                }
            }
        }
    }

    @EventTarget(Priority.LOWEST)
    public void onPacketSend(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.SEND || mc.thePlayer == null) {
            return;
        }

        if (this.mode.getValue() != 1) {
            return;
        }

        Packet<?> packet = event.getPacket();
        
        if (packet instanceof C03PacketPlayer) {
            if (shouldDelayPackets()) {
                if (!delayingPackets) {
                    delayingPackets = true;
                    lastPacketTime = System.currentTimeMillis();
                }

                if (System.currentTimeMillis() - lastPacketTime < this.latency.getValue()) {
                    delayedPackets.add(packet);
                    event.setCancelled(true);
                } else {
                    releaseDelayedPackets();
                    delayingPackets = false;
                }
            } else {
                if (delayingPackets) {
                    releaseDelayedPackets();
                    delayingPackets = false;
                }
            }
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == mc.thePlayer || player.isDead) {
                continue;
            }

            LinkedList<PositionData> positions = entityPositions.computeIfAbsent(player.getEntityId(), k -> new LinkedList<>());
            
            positions.addFirst(new PositionData(
                player.posX, player.posY, player.posZ,
                player.rotationYaw, player.rotationPitch,
                player.rotationYawHead, player.limbSwing, player.limbSwingAmount
            ));

            while (positions.size() > this.ticks.getValue()) {
                positions.removeLast();
            }
        }

        entityPositions.keySet().removeIf(id -> mc.theWorld.getEntityByID(id) == null);
        serverPositions.keySet().removeIf(id -> mc.theWorld.getEntityByID(id) == null);
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        if (this.renderPreviousTicks.getValue() && this.mode.getValue() == 0) {
            renderHistoricalPositions();
        }

        if (this.renderServerPos.getValue() && this.mode.getValue() == 1) {
            renderServerPositions();
        }
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled() || this.mode.getValue() != 0 || mc.thePlayer == null) {
            return;
        }

        if (event.getTarget() instanceof EntityPlayer) {
            EntityPlayer target = (EntityPlayer) event.getTarget();
            LinkedList<PositionData> positions = entityPositions.get(target.getEntityId());
            
            if (positions != null && !positions.isEmpty()) {
                PositionData backtrackPos = selectOptimalPosition(positions, target);
                if (backtrackPos != null && shouldUseBacktrack(target, backtrackPos)) {
                    applyBacktrackPosition(target, backtrackPos);
                }
            }
        }
    }

    private void updateServerPosition(EntityPlayer player) {
        if (player != null) {
            serverPositions.put(player.getEntityId(), new Vec3d(player.posX, player.posY, player.posZ));
        }
    }

    private boolean shouldDelayPackets() {
        KillAura killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        if (killAura == null || !killAura.isEnabled()) {
            return false;
        }

        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == mc.thePlayer || player.isDead) {
                continue;
            }

            double distance = mc.thePlayer.getDistanceToEntity(player);
            if (distance <= 6.0 && isValidTarget(player)) {
                return true;
            }
        }

        return false;
    }

    private boolean isValidTarget(EntityPlayer player) {
        if (player == mc.thePlayer || player.isDead || player.getHealth() <= 0) {
            return false;
        }

        if (TeamUtil.isFriend(player)) {
            return false;
        }

        return RotationUtil.angleToEntity(player) <= 180.0F;
    }

    private void releaseDelayedPackets() {
        while (!delayedPackets.isEmpty()) {
            Packet<?> packet = delayedPackets.poll();
            if (packet != null && mc.getNetHandler() != null) {
                mc.getNetHandler().getNetworkManager().sendPacket(packet);
            }
        }
    }

    private PositionData selectOptimalPosition(LinkedList<PositionData> positions, EntityPlayer target) {
        if (positions.isEmpty()) {
            return null;
        }

        double currentDistance = mc.thePlayer.getDistanceToEntity(target);
        
        for (PositionData pos : positions) {
            double backtrackDistance = mc.thePlayer.getDistanceSq(pos.x, pos.y, pos.z);
            if (Math.sqrt(backtrackDistance) < currentDistance - 0.5) {
                return pos;
            }
        }

        return positions.getFirst();
    }

    private boolean shouldUseBacktrack(EntityPlayer target, PositionData backtrackPos) {
        double currentDist = mc.thePlayer.getDistanceToEntity(target);
        double backtrackDist = Math.sqrt(mc.thePlayer.getDistanceSq(backtrackPos.x, backtrackPos.y, backtrackPos.z));
        
        return backtrackDist < currentDist && backtrackDist <= 6.0;
    }

    private void applyBacktrackPosition(EntityPlayer target, PositionData pos) {
        target.setPosition(pos.x, pos.y, pos.z);
        target.rotationYaw = pos.yaw;
        target.rotationPitch = pos.pitch;
        target.rotationYawHead = pos.headYaw;
    }

    private void renderHistoricalPositions() {
        for (Map.Entry<Integer, LinkedList<PositionData>> entry : entityPositions.entrySet()) {
            EntityPlayer player = (EntityPlayer) mc.theWorld.getEntityByID(entry.getKey());
            
            if (player == null || player == mc.thePlayer || !isValidTarget(player)) {
                continue;
            }

            LinkedList<PositionData> positions = entry.getValue();
            if (positions.isEmpty()) {
                continue;
            }

            Color baseColor = new Color(this.color.getValue());
            
            for (int i = 0; i < positions.size(); i++) {
                PositionData pos = positions.get(i);
                float alpha = 1.0F - ((float) i / positions.size()) * 0.8F;
                
                renderPlayerAtPosition(player, pos, new Color(
                    baseColor.getRed(),
                    baseColor.getGreen(),
                    baseColor.getBlue(),
                    (int) (alpha * 100)
                ));
            }
        }
    }

    private void renderServerPositions() {
        for (Map.Entry<Integer, Vec3d> entry : serverPositions.entrySet()) {
            EntityPlayer player = (EntityPlayer) mc.theWorld.getEntityByID(entry.getKey());
            
            if (player == null || player == mc.thePlayer || !isValidTarget(player)) {
                continue;
            }

            Vec3d serverPos = entry.getValue();
            double renderX = serverPos.x - ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosX();
            double renderY = serverPos.y - ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosY();
            double renderZ = serverPos.z - ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosZ();

            AxisAlignedBB box = new AxisAlignedBB(
                renderX - 0.3, renderY, renderZ - 0.3,
                renderX + 0.3, renderY + 1.8, renderZ + 0.3
            );

            Color serverColor = new Color(this.color.getValue());
            RenderUtil.enableRenderState();
            RenderUtil.drawBoundingBox(box, serverColor.getRed(), 
                serverColor.getGreen(), serverColor.getBlue(), 255, 2.0F);
            RenderUtil.disableRenderState();
        }
    }

    private void renderPlayerAtPosition(EntityPlayer player, PositionData pos, Color color) {
        double renderX = pos.x - ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosX();
        double renderY = pos.y - ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosY();
        double renderZ = pos.z - ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosZ();

        AxisAlignedBB box = new AxisAlignedBB(
            renderX - 0.3, renderY, renderZ - 0.3,
            renderX + 0.3, renderY + 1.8, renderZ + 0.3
        );
        
        RenderUtil.enableRenderState();
        RenderUtil.drawFilledBox(box, color.getRed(), color.getGreen(), color.getBlue());
        RenderUtil.drawBoundingBox(box, color.getRed(), color.getGreen(), color.getBlue(), color.getAlpha(), 1.5F);
        RenderUtil.disableRenderState();
    }

    public LinkedList<PositionData> getEntityPositions(int entityId) {
        return entityPositions.get(entityId);
    }

    private static class PositionData {
        final double x, y, z;
        final float yaw, pitch, headYaw, limbSwing, limbSwingAmount;

        PositionData(double x, double y, double z, float yaw, float pitch, 
                    float headYaw, float limbSwing, float limbSwingAmount) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.headYaw = headYaw;
            this.limbSwing = limbSwing;
            this.limbSwingAmount = limbSwingAmount;
        }
    }

    private static class Vec3d {
        final double x, y, z;

        Vec3d(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
