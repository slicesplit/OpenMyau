package myau.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.AxisAlignedBB;
import org.lwjgl.opengl.GL11;

import java.awt.Color;

/**
 * RenderBoxUtil - Centralized box rendering utility
 * 
 * Provides clean, optimized box rendering for ESP, Backtrack, etc.
 * Default style: Vape V4 light blue boxes with smooth edges
 */
public class RenderBoxUtil {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // Darker blue with more translucency (user requested)
    public static final Color VAPE_LIGHT_BLUE = new Color(70, 130, 180, 90); // Steel blue, 35% opacity (more translucent)
    public static final Color VAPE_LIGHT_BLUE_OUTLINE = new Color(70, 130, 180, 0); // No border (fully transparent)
    
    // Player hitbox dimensions
    public static final double PLAYER_WIDTH = 0.6;
    public static final double PLAYER_HEIGHT = 1.8;
    
    /**
     * Render a Vape V4 style player box at world position
     * 
     * @param x World X coordinate
     * @param y World Y coordinate (foot level)
     * @param z World Z coordinate
     */
    public static void renderPlayerBox(double x, double y, double z) {
        renderPlayerBox(x, y, z, VAPE_LIGHT_BLUE, VAPE_LIGHT_BLUE_OUTLINE);
    }
    
    /**
     * Render a player box at world position with custom colors
     * 
     * @param x World X coordinate
     * @param y World Y coordinate (foot level)
     * @param z World Z coordinate
     * @param fillColor Fill color (with alpha for transparency)
     * @param outlineColor Outline color
     */
    public static void renderPlayerBox(double x, double y, double z, Color fillColor, Color outlineColor) {
        // Calculate render position (relative to camera)
        double renderX = x - mc.getRenderManager().viewerPosX;
        double renderY = y - mc.getRenderManager().viewerPosY;
        double renderZ = z - mc.getRenderManager().viewerPosZ;
        
        // Create bounding box for player hitbox
        AxisAlignedBB box = new AxisAlignedBB(
            renderX - PLAYER_WIDTH / 2, renderY, renderZ - PLAYER_WIDTH / 2,
            renderX + PLAYER_WIDTH / 2, renderY + PLAYER_HEIGHT, renderZ + PLAYER_WIDTH / 2
        );
        
        renderBox(box, fillColor, outlineColor, 1.5F);
    }
    
    /**
     * Render a custom bounding box with Vape V4 style
     * 
     * @param box The bounding box to render
     * @param fillColor Fill color (with alpha)
     * @param outlineColor Outline color
     * @param lineWidth Outline line width
     */
    public static void renderBox(AxisAlignedBB box, Color fillColor, Color outlineColor, float lineWidth) {
        // Setup GL state
        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.disableTexture2D();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.disableLighting();
        GlStateManager.disableCull();
        
        // Render filled box
        if (fillColor != null && fillColor.getAlpha() > 0) {
            renderFilledBox(box, fillColor);
        }
        
        // Render outline (skip if alpha is 0 - no borders)
        if (outlineColor != null && outlineColor.getAlpha() > 0) {
            GL11.glLineWidth(lineWidth);
            renderBoxOutline(box, outlineColor);
        }
        // Note: Default outline color has alpha=0, so borders are disabled by default
        
        // Restore GL state
        GlStateManager.enableCull();
        GlStateManager.enableLighting();
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }
    
    /**
     * Render filled box (all 6 faces)
     */
    private static void renderFilledBox(AxisAlignedBB box, Color color) {
        float r = color.getRed() / 255.0F;
        float g = color.getGreen() / 255.0F;
        float b = color.getBlue() / 255.0F;
        float a = color.getAlpha() / 255.0F;
        
        GlStateManager.color(r, g, b, a);
        
        GL11.glBegin(GL11.GL_QUADS);
        
        // Bottom face (Y-)
        GL11.glVertex3d(box.minX, box.minY, box.minZ);
        GL11.glVertex3d(box.maxX, box.minY, box.minZ);
        GL11.glVertex3d(box.maxX, box.minY, box.maxZ);
        GL11.glVertex3d(box.minX, box.minY, box.maxZ);
        
        // Top face (Y+)
        GL11.glVertex3d(box.minX, box.maxY, box.minZ);
        GL11.glVertex3d(box.minX, box.maxY, box.maxZ);
        GL11.glVertex3d(box.maxX, box.maxY, box.maxZ);
        GL11.glVertex3d(box.maxX, box.maxY, box.minZ);
        
        // North face (Z-)
        GL11.glVertex3d(box.minX, box.minY, box.minZ);
        GL11.glVertex3d(box.minX, box.maxY, box.minZ);
        GL11.glVertex3d(box.maxX, box.maxY, box.minZ);
        GL11.glVertex3d(box.maxX, box.minY, box.minZ);
        
        // South face (Z+)
        GL11.glVertex3d(box.minX, box.minY, box.maxZ);
        GL11.glVertex3d(box.maxX, box.minY, box.maxZ);
        GL11.glVertex3d(box.maxX, box.maxY, box.maxZ);
        GL11.glVertex3d(box.minX, box.maxY, box.maxZ);
        
        // West face (X-)
        GL11.glVertex3d(box.minX, box.minY, box.minZ);
        GL11.glVertex3d(box.minX, box.minY, box.maxZ);
        GL11.glVertex3d(box.minX, box.maxY, box.maxZ);
        GL11.glVertex3d(box.minX, box.maxY, box.minZ);
        
        // East face (X+)
        GL11.glVertex3d(box.maxX, box.minY, box.minZ);
        GL11.glVertex3d(box.maxX, box.maxY, box.minZ);
        GL11.glVertex3d(box.maxX, box.maxY, box.maxZ);
        GL11.glVertex3d(box.maxX, box.minY, box.maxZ);
        
        GL11.glEnd();
    }
    
    /**
     * Render box outline (all 12 edges)
     */
    private static void renderBoxOutline(AxisAlignedBB box, Color color) {
        float r = color.getRed() / 255.0F;
        float g = color.getGreen() / 255.0F;
        float b = color.getBlue() / 255.0F;
        float a = color.getAlpha() / 255.0F;
        
        GlStateManager.color(r, g, b, a);
        
        GL11.glBegin(GL11.GL_LINES);
        
        // Bottom face edges
        GL11.glVertex3d(box.minX, box.minY, box.minZ);
        GL11.glVertex3d(box.maxX, box.minY, box.minZ);
        
        GL11.glVertex3d(box.maxX, box.minY, box.minZ);
        GL11.glVertex3d(box.maxX, box.minY, box.maxZ);
        
        GL11.glVertex3d(box.maxX, box.minY, box.maxZ);
        GL11.glVertex3d(box.minX, box.minY, box.maxZ);
        
        GL11.glVertex3d(box.minX, box.minY, box.maxZ);
        GL11.glVertex3d(box.minX, box.minY, box.minZ);
        
        // Top face edges
        GL11.glVertex3d(box.minX, box.maxY, box.minZ);
        GL11.glVertex3d(box.maxX, box.maxY, box.minZ);
        
        GL11.glVertex3d(box.maxX, box.maxY, box.minZ);
        GL11.glVertex3d(box.maxX, box.maxY, box.maxZ);
        
        GL11.glVertex3d(box.maxX, box.maxY, box.maxZ);
        GL11.glVertex3d(box.minX, box.maxY, box.maxZ);
        
        GL11.glVertex3d(box.minX, box.maxY, box.maxZ);
        GL11.glVertex3d(box.minX, box.maxY, box.minZ);
        
        // Vertical edges
        GL11.glVertex3d(box.minX, box.minY, box.minZ);
        GL11.glVertex3d(box.minX, box.maxY, box.minZ);
        
        GL11.glVertex3d(box.maxX, box.minY, box.minZ);
        GL11.glVertex3d(box.maxX, box.maxY, box.minZ);
        
        GL11.glVertex3d(box.maxX, box.minY, box.maxZ);
        GL11.glVertex3d(box.maxX, box.maxY, box.maxZ);
        
        GL11.glVertex3d(box.minX, box.minY, box.maxZ);
        GL11.glVertex3d(box.minX, box.maxY, box.maxZ);
        
        GL11.glEnd();
    }
}
