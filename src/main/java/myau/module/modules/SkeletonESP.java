package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;

import myau.Myau;
import myau.event.EventTarget;
import myau.events.Render3DEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import org.lwjgl.opengl.GL11;

import java.awt.*;

/**
 * Skeleton ESP - Renders player skeletons for better visibility
 * Shows the skeletal structure of players with customizable colors and width
 */
@ModuleInfo(category = ModuleCategory.RENDER)
public class SkeletonESP extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    public final IntProperty lineWidth = new IntProperty("Line Width", 2, 1, 10);
    public final BooleanProperty players = new BooleanProperty("Players", true);
    public final BooleanProperty friends = new BooleanProperty("Friends", true);
    public final BooleanProperty enemies = new BooleanProperty("Enemies", true);
    public final BooleanProperty self = new BooleanProperty("Self", false);
    public final BooleanProperty bots = new BooleanProperty("Bots", false);

    public SkeletonESP() {
        super("SkeletonESP", false);
    }

    private boolean shouldRenderPlayer(EntityPlayer entityPlayer) {
        if (entityPlayer.deathTime > 0) {
            return false;
        } else if (mc.getRenderViewEntity().getDistanceToEntity(entityPlayer) > 512.0F) {
            return false;
        } else if (entityPlayer != mc.thePlayer && entityPlayer != mc.getRenderViewEntity()) {
            if (TeamUtil.isBot(entityPlayer)) {
                return this.bots.getValue();
            } else if (TeamUtil.isFriend(entityPlayer)) {
                return this.friends.getValue();
            } else {
                return TeamUtil.isTarget(entityPlayer) ? this.enemies.getValue() : this.players.getValue();
            }
        } else {
            return this.self.getValue() && mc.gameSettings.thirdPersonView != 0;
        }
    }

    private Color getEntityColor(EntityPlayer entityPlayer) {
        if (TeamUtil.isFriend(entityPlayer)) {
            return Myau.friendManager.getColor();
        } else if (TeamUtil.isTarget(entityPlayer)) {
            return Myau.targetManager.getColor();
        } else {
            return TeamUtil.getTeamColor(entityPlayer, 1.0F);
        }
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || mc.theWorld == null) return;

        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == null || !shouldRenderPlayer(player)) continue;

            Color color = getEntityColor(player);
            renderSkeleton(player, color, event.getPartialTicks());
        }
    }

    private void renderSkeleton(EntityPlayer player, Color color, float partialTicks) {
        double x = player.lastTickPosX + (player.posX - player.lastTickPosX) * partialTicks
                - mc.getRenderManager().viewerPosX;
        double y = player.lastTickPosY + (player.posY - player.lastTickPosY) * partialTicks
                - mc.getRenderManager().viewerPosY;
        double z = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * partialTicks
                - mc.getRenderManager().viewerPosZ;

        GL11.glPushMatrix();
        GL11.glTranslated(x, y, z);

        float yaw = player.prevRenderYawOffset + (player.renderYawOffset - player.prevRenderYawOffset) * partialTicks;
        GL11.glRotatef(-yaw, 0.0F, 1.0F, 0.0F);
        GL11.glTranslated(0.0, 0.0, player.isSneaking() ? -0.235 : 0.0);

        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.disableDepth();
        GlStateManager.disableTexture2D();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableLighting();

        GL11.glLineWidth(lineWidth.getValue());
        GL11.glColor4f(color.getRed() / 255.0f, color.getGreen() / 255.0f, color.getBlue() / 255.0f, 1.0f);

        GL11.glBegin(GL11.GL_LINES);

        // Body vertical line (spine)
        GL11.glVertex3d(0.0, player.isSneaking() ? 0.6 : 0.7, player.isSneaking() ? 0.25 : 0.0);
        GL11.glVertex3d(0.0, player.isSneaking() ? 1.55 : 1.7, player.isSneaking() ? -0.1 : 0.0);

        // Legs
        float legSwing = player.prevLimbSwingAmount + (player.limbSwingAmount - player.prevLimbSwingAmount) * partialTicks;
        float legAngle = player.limbSwing;
        
        // Right leg
        double rightLegX = -0.125;
        double rightLegZ = Math.sin(legAngle * 0.6662F) * legSwing * 0.5;
        GL11.glVertex3d(0.0, player.isSneaking() ? 0.6 : 0.7, player.isSneaking() ? 0.25 : 0.0);
        GL11.glVertex3d(rightLegX, player.isSneaking() ? 0.01 : 0.0, rightLegZ);

        // Left leg
        double leftLegX = 0.125;
        double leftLegZ = -Math.sin(legAngle * 0.6662F) * legSwing * 0.5;
        GL11.glVertex3d(0.0, player.isSneaking() ? 0.6 : 0.7, player.isSneaking() ? 0.25 : 0.0);
        GL11.glVertex3d(leftLegX, player.isSneaking() ? 0.01 : 0.0, leftLegZ);

        // Arms
        float armSwing = player.swingProgress;
        
        // Right arm
        double rightArmAngle = Math.sin(legAngle * 0.6662F) * legSwing * 0.8;
        if (player.isSwingInProgress) {
            rightArmAngle = -Math.sin(armSwing * Math.PI) * 0.8;
        }
        GL11.glVertex3d(0.0, player.isSneaking() ? 1.55 : 1.7, player.isSneaking() ? -0.1 : 0.0);
        GL11.glVertex3d(-0.375, player.isSneaking() ? 1.0 : 1.15, rightArmAngle);

        // Left arm
        double leftArmAngle = -Math.sin(legAngle * 0.6662F) * legSwing * 0.8;
        GL11.glVertex3d(0.0, player.isSneaking() ? 1.55 : 1.7, player.isSneaking() ? -0.1 : 0.0);
        GL11.glVertex3d(0.375, player.isSneaking() ? 1.0 : 1.15, leftArmAngle);

        GL11.glEnd();

        GlStateManager.enableTexture2D();
        GlStateManager.enableDepth();
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();

        GL11.glPopMatrix();
    }
}
