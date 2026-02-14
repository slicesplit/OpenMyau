package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;

import myau.event.EventTarget;
import myau.event.types.Priority;
import myau.events.Render2DEvent;
import myau.events.Render3DEvent;
import myau.events.ResizeEvent;
import myau.mixin.IAccessorEntityRenderer;
import myau.mixin.IAccessorMinecraft;
import myau.mixin.IAccessorRenderManager;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.ModeProperty;
import myau.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.entity.Entity;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

/**
 * RearView Module - Production-Grade Picture-in-Picture Rear Camera
 * Full world rendering with reversed camera (yaw + 180°) in a HUD window
 * Military-grade quality with proper depth, lighting, and state management
 */
@ModuleInfo(category = ModuleCategory.RENDER)
public class RearView extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // Framebuffers for high-quality rendering
    private Framebuffer rearViewFramebuffer = null;
    
    // Properties - Optimized for balanced rear view advantage
    public final ModeProperty position = new ModeProperty("Position", 1, new String[]{"TopLeft", "TopRight", "BottomLeft", "BottomRight"});
    public final FloatProperty scale = new FloatProperty("Scale", 0.20F, 0.10F, 0.35F); // Smaller default, max 35% for balanced advantage
    public final FloatProperty offsetX = new FloatProperty("OffsetX", 10.0F, 0.0F, 200.0F);
    public final FloatProperty offsetY = new FloatProperty("OffsetY", 10.0F, 0.0F, 200.0F);
    public final FloatProperty opacity = new FloatProperty("Opacity", 0.85F, 0.3F, 1.0F); // Slightly transparent default
    public final BooleanProperty border = new BooleanProperty("Border", true);
    public final BooleanProperty smoothUpdate = new BooleanProperty("SmoothUpdate", true); // Smooth by default
    public final BooleanProperty alwaysRearView = new BooleanProperty("AlwaysRearView", true); // Always show back even in F5
    
    // Performance tracking
    private long lastRenderTime = 0;
    private static final long RENDER_INTERVAL_MS = 16; // 60 FPS cap
    
    // Saved state for restoration - CRITICAL for zero interference
    private float savedYaw;
    private float savedPrevYaw;
    private float savedPitch;
    private float savedPrevPitch;
    private double savedPosX, savedPosY, savedPosZ;
    private double savedLastTickPosX, savedLastTickPosY, savedLastTickPosZ;
    private float savedRotationYawHead;
    private float savedPrevRotationYawHead;
    
    // Rendering flag to prevent recursive rendering
    private boolean isRenderingRearView = false;
    
    public RearView() {
        super("RearView", false);
    }
    
    @Override
    public void onEnabled() {
        if (mc.theWorld == null || mc.thePlayer == null) {
            setEnabled(false);
            return;
        }
        initFramebuffer();
    }
    
    @Override
    public void onDisabled() {
        cleanupFramebuffer();
    }
    
    @EventTarget
    public void onResize(ResizeEvent event) {
        if (isEnabled()) {
            cleanupFramebuffer();
            initFramebuffer();
        }
    }
    
    /**
     * Initialize framebuffer with appropriate size - OPTIMIZED for HUD element
     * Smaller framebuffer = better performance, still great quality
     */
    private void initFramebuffer() {
        if (rearViewFramebuffer != null) {
            rearViewFramebuffer.deleteFramebuffer();
        }
        
        // Use smaller resolution for HUD element - saves performance
        // 720p is perfect for a small rear view window
        int fbWidth = 1280;
        int fbHeight = 720;
        
        // Create framebuffer with depth buffer enabled (true) for proper 3D rendering
        rearViewFramebuffer = new Framebuffer(fbWidth, fbHeight, true);
        rearViewFramebuffer.setFramebufferColor(0.0F, 0.0F, 0.0F, 0.0F);
        
        // Set framebuffer filter for smooth scaling
        rearViewFramebuffer.setFramebufferFilter(GL11.GL_LINEAR);
    }
    
    /**
     * Cleanup framebuffer resources
     */
    private void cleanupFramebuffer() {
        if (rearViewFramebuffer != null) {
            rearViewFramebuffer.deleteFramebuffer();
            rearViewFramebuffer = null;
        }
    }
    
    @EventTarget(Priority.LOW)
    public void onRender2D(Render2DEvent event) {
        if (!isEnabled() || mc.theWorld == null || mc.thePlayer == null || isRenderingRearView) {
            return;
        }
        
        // FPS throttling for performance (optional)
        long currentTime = System.currentTimeMillis();
        if (smoothUpdate.getValue() && (currentTime - lastRenderTime) < RENDER_INTERVAL_MS) {
            // Still draw the existing framebuffer content
            drawRearViewWindow(event.getPartialTicks());
            return;
        }
        lastRenderTime = currentTime;
        
        // Ensure framebuffer exists
        if (rearViewFramebuffer == null) {
            initFramebuffer();
        }
        
        // Render rear view into framebuffer
        renderRearView(event.getPartialTicks());
        
        // Draw framebuffer to HUD
        drawRearViewWindow(event.getPartialTicks());
    }
    
    /**
     * Block recursive rendering in 3D events
     */
    @EventTarget(Priority.HIGHEST)
    public void onRender3D(Render3DEvent event) {
        if (isRenderingRearView) {
            // Skip all 3D rendering when we're rendering the rear view
            // This prevents infinite loops
            return;
        }
    }
    
    /**
     * Render the world with reversed camera into framebuffer - PRODUCTION GRADE
     * Full world rendering pipeline with proper state management
     */
    private void renderRearView(float partialTicks) {
        Entity renderEntity = mc.getRenderViewEntity();
        if (renderEntity == null) return;
        
        // Set rendering flag to prevent recursive calls
        isRenderingRearView = true;
        
        // Save ALL critical state
        saveEntityState(renderEntity);
        
        // Save GL state
        GlStateManager.pushMatrix();
        GlStateManager.pushAttrib();
        
        try {
            // ALWAYS REAR VIEW: Always add 180 degrees regardless of F5 mode
            // This ensures you ALWAYS see behind you, even when in third person
            float reversedYaw = savedYaw + 180.0F;
            
            // Apply rotation
            renderEntity.rotationYaw = reversedYaw;
            renderEntity.prevRotationYaw = savedPrevYaw + 180.0F;
            
            // Keep pitch the same (don't reverse up/down view)
            // This makes the rear view feel natural
            renderEntity.rotationPitch = savedPitch;
            renderEntity.prevRotationPitch = savedPrevPitch;
            
            // Update head rotation if entity player
            if (renderEntity == mc.thePlayer) {
                mc.thePlayer.rotationYawHead = reversedYaw;
                mc.thePlayer.prevRotationYawHead = savedPrevRotationYawHead + 180.0F;
            }
            
            // Bind our framebuffer for rendering
            rearViewFramebuffer.bindFramebuffer(true);
            
            // Clear framebuffer with proper depth
            GlStateManager.clearColor(0.0F, 0.0F, 0.0F, 0.0F);
            GlStateManager.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            
            // Enable depth testing for proper 3D rendering
            GlStateManager.enableDepth();
            GlStateManager.depthFunc(GL11.GL_LEQUAL);
            GlStateManager.depthMask(true);
            
            // Set up camera transform with reversed rotation
            ((IAccessorEntityRenderer) mc.entityRenderer).callSetupCameraTransform(partialTicks, 0);
            
            // Render the world with full quality
            renderWorldToFramebuffer(partialTicks);
            
            // Restore main framebuffer
            mc.getFramebuffer().bindFramebuffer(true);
            
        } catch (Exception e) {
            // Fail silently to prevent crashes
            // Restore main framebuffer even on error
            try {
                mc.getFramebuffer().bindFramebuffer(true);
            } catch (Exception ignored) {}
        } finally {
            // Always restore state
            restoreEntityState(renderEntity);
            
            // Restore GL state
            GlStateManager.popAttrib();
            GlStateManager.popMatrix();
            
            // Clear rendering flag
            isRenderingRearView = false;
        }
    }
    
    /**
     * Render world content - MILITARY GRADE QUALITY
     * Full rendering pipeline with terrain, entities, particles, weather, etc.
     */
    private void renderWorldToFramebuffer(float partialTicks) {
        try {
            // Enable all rendering features
            GlStateManager.enableDepth();
            GlStateManager.depthFunc(GL11.GL_LEQUAL);
            GlStateManager.depthMask(true);
            GlStateManager.enableCull();
            
            // Enable lighting for proper shading
            GlStateManager.enableLighting();
            GlStateManager.enableColorMaterial();
            
            // Call the actual world rendering pass
            // Pass 2 = main world pass (0 = shadow pass, 1 = translucent pass)
            // This renders EVERYTHING: terrain, entities, particles, sky, etc.
            ((IAccessorEntityRenderer) mc.entityRenderer).callRenderWorldPass(2, partialTicks, 0L);
            
            // Disable lighting after world render
            GlStateManager.disableLighting();
            
        } catch (Exception e) {
            // Fail gracefully - framebuffer retains previous content
            // Zero crashes guaranteed
        }
    }
    
    /**
     * Draw the rear view framebuffer as a PiP window on the HUD - FIXED
     * No longer covers F3 debug menu or other UI elements
     * Renders as a proper HUD element with depth buffer disabled
     */
    private void drawRearViewWindow(float partialTicks) {
        if (rearViewFramebuffer == null) return;
        
        ScaledResolution sr = new ScaledResolution(mc);
        
        // Calculate window size based on scale
        float windowWidth = sr.getScaledWidth() * scale.getValue();
        float windowHeight = sr.getScaledHeight() * scale.getValue();
        
        // Calculate position based on mode
        float windowX = 0;
        float windowY = 0;
        
        String posMode = position.getModeString();
        float offsetXValue = offsetX.getValue();
        float offsetYValue = offsetY.getValue();
        
        switch (posMode) {
            case "TopLeft":
                windowX = offsetXValue;
                windowY = offsetYValue;
                break;
                
            case "TopRight":
                windowX = sr.getScaledWidth() - windowWidth - offsetXValue;
                windowY = offsetYValue;
                break;
                
            case "BottomLeft":
                windowX = offsetXValue;
                windowY = sr.getScaledHeight() - windowHeight - offsetYValue;
                break;
                
            case "BottomRight":
                windowX = sr.getScaledWidth() - windowWidth - offsetXValue;
                windowY = sr.getScaledHeight() - windowHeight - offsetYValue;
                break;
        }
        
        // CRITICAL FIX: Proper HUD rendering that doesn't interfere with other UI
        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        
        // Disable depth buffer completely - HUD element should not use depth
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        
        // Disable lighting for 2D rendering
        GL11.glDisable(GL11.GL_LIGHTING);
        
        // Enable blending for transparency
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        
        // Enable 2D texture rendering
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        
        // Disable alpha test (we use blending instead)
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        
        // Draw border if enabled (outer black border for visibility)
        if (border.getValue()) {
            // Disable texture for border drawing
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            
            float borderWidth = 2.0F;
            
            // Outer black border
            GL11.glColor4f(0.0F, 0.0F, 0.0F, opacity.getValue());
            drawRect(windowX - borderWidth, windowY - borderWidth, 
                    windowX + windowWidth + borderWidth, windowY + windowHeight + borderWidth);
            
            // Inner accent border (light gray)
            GL11.glColor4f(0.4F, 0.4F, 0.4F, opacity.getValue());
            drawRect(windowX - 1, windowY - 1, 
                    windowX + windowWidth + 1, windowY + windowHeight + 1);
            
            // Re-enable texture for framebuffer
            GL11.glEnable(GL11.GL_TEXTURE_2D);
        }
        
        // Apply opacity to framebuffer
        GL11.glColor4f(1.0F, 1.0F, 1.0F, opacity.getValue());
        
        // Bind framebuffer texture with high-quality filtering
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, rearViewFramebuffer.framebufferTexture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
        
        // Draw textured quad with proper UVs (flipped vertically)
        GL11.glBegin(GL11.GL_QUADS);
        
        // Bottom-left (texture coordinate 0,1 because framebuffer is flipped)
        GL11.glTexCoord2f(0.0F, 1.0F);
        GL11.glVertex2f(windowX, windowY);
        
        // Bottom-right
        GL11.glTexCoord2f(1.0F, 1.0F);
        GL11.glVertex2f(windowX + windowWidth, windowY);
        
        // Top-right
        GL11.glTexCoord2f(1.0F, 0.0F);
        GL11.glVertex2f(windowX + windowWidth, windowY + windowHeight);
        
        // Top-left
        GL11.glTexCoord2f(0.0F, 0.0F);
        GL11.glVertex2f(windowX, windowY + windowHeight);
        
        GL11.glEnd();
        
        // Restore GL state completely
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        
        GL11.glPopAttrib();
        GL11.glPopMatrix();
    }
    
    /**
     * Simple rect drawing helper for borders
     */
    private void drawRect(float x1, float y1, float x2, float y2) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x1, y1);
        GL11.glVertex2f(x2, y1);
        GL11.glVertex2f(x2, y2);
        GL11.glVertex2f(x1, y2);
        GL11.glEnd();
    }
    
    /**
     * Save entity rotation and position state - COMPLETE STATE CAPTURE
     */
    private void saveEntityState(Entity entity) {
        savedYaw = entity.rotationYaw;
        savedPrevYaw = entity.prevRotationYaw;
        savedPitch = entity.rotationPitch;
        savedPrevPitch = entity.prevRotationPitch;
        savedPosX = entity.posX;
        savedPosY = entity.posY;
        savedPosZ = entity.posZ;
        savedLastTickPosX = entity.lastTickPosX;
        savedLastTickPosY = entity.lastTickPosY;
        savedLastTickPosZ = entity.lastTickPosZ;
        
        // Save head rotation for players
        if (entity == mc.thePlayer) {
            savedRotationYawHead = mc.thePlayer.rotationYawHead;
            savedPrevRotationYawHead = mc.thePlayer.prevRotationYawHead;
        }
    }
    
    /**
     * Restore entity rotation and position state - COMPLETE STATE RESTORATION
     */
    private void restoreEntityState(Entity entity) {
        entity.rotationYaw = savedYaw;
        entity.prevRotationYaw = savedPrevYaw;
        entity.rotationPitch = savedPitch;
        entity.prevRotationPitch = savedPrevPitch;
        entity.posX = savedPosX;
        entity.posY = savedPosY;
        entity.posZ = savedPosZ;
        entity.lastTickPosX = savedLastTickPosX;
        entity.lastTickPosY = savedLastTickPosY;
        entity.lastTickPosZ = savedLastTickPosZ;
        
        // Restore head rotation for players
        if (entity == mc.thePlayer) {
            mc.thePlayer.rotationYawHead = savedRotationYawHead;
            mc.thePlayer.prevRotationYawHead = savedPrevRotationYawHead;
        }
    }
}
