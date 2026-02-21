package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;
import myau.event.EventTarget;
import myau.events.Render2DEvent;
import myau.events.Render3DEvent;
import myau.mixin.IAccessorEntityRenderer;
import myau.module.Module;
import myau.property.properties.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.entity.Entity;
import org.lwjgl.opengl.GL11;

import java.nio.IntBuffer;

@ModuleInfo(category = ModuleCategory.RENDER)
public class RearView extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty corner = new ModeProperty("Corner", 1,
            new String[]{"Top Left", "Top Right", "Bottom Left", "Bottom Right"});
    public final ModeProperty size = new ModeProperty("Size", 1,
            new String[]{"Small", "Medium", "Large"});
    public final BooleanProperty border = new BooleanProperty("Border", true);

    private static final float[] SCALES = {0.15F, 0.22F, 0.30F};
    private static final float PADDING = 8.0F;
    private static final float BORDER_WIDTH = 1.5F;

    private Framebuffer fbo;
    private int fboW, fboH;
    private boolean busy;

    private float savedYaw, savedPrevYaw, savedPitch, savedPrevPitch;
    private float savedHeadYaw, savedPrevHeadYaw;
    private int savedF5;

    public RearView() {
        super("RearView", false);
    }

    @Override
    public void onEnabled() {
        fbo = null;
    }

    @Override
    public void onDisabled() {
        killFbo();
    }

    private void killFbo() {
        if (fbo != null) {
            fbo.deleteFramebuffer();
            fbo = null;
        }
    }

    private float getScale() {
        int idx = size.getValue();
        return (idx >= 0 && idx < SCALES.length) ? SCALES[idx] : SCALES[1];
    }

    private void prepareFbo() {
        ScaledResolution sr = new ScaledResolution(mc);
        int sf = sr.getScaleFactor();
        float sc = getScale();

        int wantW = Math.max(64, (int)(mc.displayWidth * sc));
        int wantH = Math.max(64, (int)(mc.displayHeight * sc));

        if (fbo == null || fboW != wantW || fboH != wantH) {
            killFbo();
            fboW = wantW;
            fboH = wantH;
            fbo = new Framebuffer(fboW, fboH, true);
            fbo.setFramebufferFilter(GL11.GL_LINEAR);
        }
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!isEnabled() || busy) return;
        if (mc.theWorld == null || mc.thePlayer == null) return;

        Entity view = mc.getRenderViewEntity();
        if (view == null) return;

        float pt = event.getPartialTicks();

        prepareFbo();
        if (fbo == null) return;

        busy = true;
        save(view);

        IntBuffer vpBuf = org.lwjgl.BufferUtils.createIntBuffer(16);
        GL11.glGetInteger(GL11.GL_VIEWPORT, vpBuf);
        int[] vp = new int[4];
        vpBuf.get(vp);

        try {
            view.rotationYaw = savedYaw + 180.0F;
            view.prevRotationYaw = savedPrevYaw + 180.0F;
            view.rotationPitch = savedPitch;
            view.prevRotationPitch = savedPrevPitch;

            if (view == mc.thePlayer) {
                mc.thePlayer.rotationYawHead = savedHeadYaw + 180.0F;
                mc.thePlayer.prevRotationYawHead = savedPrevHeadYaw + 180.0F;
            }

            mc.gameSettings.thirdPersonView = 0;

            fbo.bindFramebuffer(true);
            GL11.glViewport(0, 0, fboW, fboH);

            GlStateManager.clearColor(0, 0, 0, 1);
            GlStateManager.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

            GlStateManager.matrixMode(GL11.GL_PROJECTION);
            GlStateManager.pushMatrix();
            GlStateManager.loadIdentity();
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GlStateManager.pushMatrix();
            GlStateManager.loadIdentity();

            ((IAccessorEntityRenderer) mc.entityRenderer).callSetupCameraTransform(pt, 0);
            GlStateManager.enableDepth();
            GlStateManager.depthMask(true);
            ((IAccessorEntityRenderer) mc.entityRenderer).callRenderWorldPass(2, pt, 0L);

            GlStateManager.matrixMode(GL11.GL_PROJECTION);
            GlStateManager.popMatrix();
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GlStateManager.popMatrix();

            mc.getFramebuffer().bindFramebuffer(true);
            GL11.glViewport(vp[0], vp[1], vp[2], vp[3]);

        } catch (Exception ignored) {
            try {
                mc.getFramebuffer().bindFramebuffer(true);
                GL11.glViewport(vp[0], vp[1], vp[2], vp[3]);
            } catch (Exception e) {}
        } finally {
            restore(view);
            busy = false;
        }

        ((IAccessorEntityRenderer) mc.entityRenderer).callSetupCameraTransform(pt, 0);
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!isEnabled() || fbo == null || busy) return;
        if (mc.theWorld == null || mc.thePlayer == null) return;

        ScaledResolution sr = new ScaledResolution(mc);
        float sw = sr.getScaledWidth();
        float sh = sr.getScaledHeight();
        float sc = getScale();
        float w = sw * sc;
        float h = sh * sc;

        float x, y;
        switch (corner.getValue()) {
            case 0:  x = PADDING;          y = PADDING;          break;
            case 1:  x = sw - w - PADDING; y = PADDING;          break;
            case 2:  x = PADDING;          y = sh - h - PADDING; break;
            case 3:  x = sw - w - PADDING; y = sh - h - PADDING; break;
            default: x = sw - w - PADDING; y = PADDING;          break;
        }

        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        if (border.getValue()) {
            GL11.glDisable(GL11.GL_TEXTURE_2D);

            // shadow
            GL11.glColor4f(0.0F, 0.0F, 0.0F, 0.5F);
            quad(x - 3, y - 3, x + w + 3, y + h + 3);

            // dark border
            GL11.glColor4f(0.12F, 0.12F, 0.12F, 0.95F);
            quad(x - BORDER_WIDTH, y - BORDER_WIDTH,
                 x + w + BORDER_WIDTH, y + h + BORDER_WIDTH);

            GL11.glEnable(GL11.GL_TEXTURE_2D);
        }

        // mirror texture
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 0.95F);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, fbo.framebufferTexture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);

        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0, 1); GL11.glVertex2f(x, y);
        GL11.glTexCoord2f(1, 1); GL11.glVertex2f(x + w, y);
        GL11.glTexCoord2f(1, 0); GL11.glVertex2f(x + w, y + h);
        GL11.glTexCoord2f(0, 0); GL11.glVertex2f(x, y + h);
        GL11.glEnd();

        // "REAR" label
        if (border.getValue()) {
            String label = "REAR";
            int labelW = mc.fontRendererObj.getStringWidth(label);
            float labelX = x + (w - labelW) / 2.0F;
            float labelY = y + 3;
            mc.fontRendererObj.drawStringWithShadow(label, labelX, labelY, 0xAAFFFFFF);
        }

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL11.glColor4f(1, 1, 1, 1);

        GL11.glPopAttrib();
        GL11.glPopMatrix();
    }

    private void quad(float x1, float y1, float x2, float y2) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x1, y1);
        GL11.glVertex2f(x2, y1);
        GL11.glVertex2f(x2, y2);
        GL11.glVertex2f(x1, y2);
        GL11.glEnd();
    }

    private void save(Entity e) {
        savedYaw = e.rotationYaw;
        savedPrevYaw = e.prevRotationYaw;
        savedPitch = e.rotationPitch;
        savedPrevPitch = e.prevRotationPitch;
        savedF5 = mc.gameSettings.thirdPersonView;
        if (e == mc.thePlayer) {
            savedHeadYaw = mc.thePlayer.rotationYawHead;
            savedPrevHeadYaw = mc.thePlayer.prevRotationYawHead;
        }
    }

    private void restore(Entity e) {
        e.rotationYaw = savedYaw;
        e.prevRotationYaw = savedPrevYaw;
        e.rotationPitch = savedPitch;
        e.prevRotationPitch = savedPrevPitch;
        mc.gameSettings.thirdPersonView = savedF5;
        if (e == mc.thePlayer) {
            mc.thePlayer.rotationYawHead = savedHeadYaw;
            mc.thePlayer.prevRotationYawHead = savedPrevHeadYaw;
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{corner.getModeString()};
    }
}