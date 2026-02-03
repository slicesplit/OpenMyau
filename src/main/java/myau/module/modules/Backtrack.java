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

    public final IntProperty ticks = new IntProperty("ticks", 6, 1, 15);
    public final BooleanProperty renderPreviousTicks = new BooleanProperty("render-previous-ticks", true);
    public final ColorProperty color = new ColorProperty("color", 0xFF0000);
    public final BooleanProperty grimReachBypass = new BooleanProperty("grim-reach-bypass", true);
    public final FloatProperty maxReachDistance = new FloatProperty("max-reach-distance", 3.0F, 2.5F, 3.5F, () -> this.grimReachBypass.getValue());

    private final Map<Integer, LinkedList<PositionData>> entityPositions = new ConcurrentHashMap<>();

    public Backtrack() {
        super("Backtrack", false);
    }

    @Override
    public void onEnabled() {
        entityPositions.clear();
    }

    @Override
    public void onDisabled() {
        entityPositions.clear();
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
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        if (this.renderPreviousTicks.getValue()) {
            renderHistoricalPositions();
        }
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
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


    private PositionData selectOptimalPosition(LinkedList<PositionData> positions, EntityPlayer target) {
        if (positions.isEmpty()) {
            return null;
        }

        double currentDistance = mc.thePlayer.getDistanceToEntity(target);
        double maxReach = this.grimReachBypass.getValue() ? this.maxReachDistance.getValue() : 3.5;
        
        PositionData bestPosition = null;
        double bestDistance = currentDistance;
        
        for (PositionData pos : positions) {
            double backtrackDistance = Math.sqrt(mc.thePlayer.getDistanceSq(pos.x, pos.y, pos.z));
            
            if (backtrackDistance < currentDistance && backtrackDistance <= maxReach) {
                if (backtrackDistance < bestDistance) {
                    bestDistance = backtrackDistance;
                    bestPosition = pos;
                }
            }
        }
        
        return bestPosition;
    }

    private boolean shouldUseBacktrack(EntityPlayer target, PositionData backtrackPos) {
        double currentDist = mc.thePlayer.getDistanceToEntity(target);
        double backtrackDist = Math.sqrt(mc.thePlayer.getDistanceSq(backtrackPos.x, backtrackPos.y, backtrackPos.z));
        double maxReach = this.grimReachBypass.getValue() ? this.maxReachDistance.getValue() : 3.5;
        
        if (backtrackDist >= currentDist) {
            return false;
        }
        
        if (backtrackDist > maxReach) {
            return false;
        }
        
        if (this.grimReachBypass.getValue()) {
            double eyeHeight = mc.thePlayer.getEyeHeight();
            double playerEyeY = mc.thePlayer.posY + eyeHeight;
            double targetCenterY = backtrackPos.y + (target.height / 2.0);
            double verticalDist = Math.abs(playerEyeY - targetCenterY);
            
            if (verticalDist > 2.0) {
                return false;
            }
        }
        
        return true;
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
            
            if (player == null || player == mc.thePlayer || player.isDead) {
                continue;
            }
            
            if (TeamUtil.isFriend(player)) {
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
}
