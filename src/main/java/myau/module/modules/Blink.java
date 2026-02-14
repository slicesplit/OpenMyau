package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;

import myau.Myau;
import myau.enums.BlinkModules;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.Render3DEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.mixin.IAccessorRenderManager;
import myau.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.util.AxisAlignedBB;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Blink - Advanced packet manipulation module
 * 
 * Based on LiquidBounce's implementation (https://github.com/CCBlueX/LiquidBounce)
 * Copyright (c) 2015 - 2026 CCBlueX
 * Licensed under GNU General Public License v3.0
 * 
 * Adapted for Myau client with permission from CCBlueX
 * 
 * Features:
 * - Dummy: Spawns a fake player at your position
 * - Ambush: Auto-disable when attacking
 * - Auto Reset: Reset or flush packets after threshold
 * - Breadcrumbs: Visual trail of positions
 */
@ModuleInfo(category = ModuleCategory.MOVEMENT)
public class Blink extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // LiquidBounce Features (Default config from user preferences)
    public final BooleanProperty dummy = new BooleanProperty("dummy", true); // Default: true (fake player on)
    public final BooleanProperty ambush = new BooleanProperty("ambush", true); // Default: true
    public final BooleanProperty autoDisable = new BooleanProperty("auto-disable", true); // Default: true
    
    // Auto Reset (LiquidBounce feature) - Default config from user preferences
    public final BooleanProperty autoReset = new BooleanProperty("auto-reset", true); // Default: enabled
    public final IntProperty resetAfter = new IntProperty("reset-after", 19, 1, 1000, () -> this.autoReset.getValue()); // Default: 19 packets
    public final ModeProperty resetAction = new ModeProperty("reset-action", 1, new String[]{"Reset", "Blink"}, () -> this.autoReset.getValue()); // Default: "Blink" (index 1)
    
    // Visual Options
    public final BooleanProperty breadcrumbs = new BooleanProperty("breadcrumbs", true);
    public final BooleanProperty pulseEffect = new BooleanProperty("pulse-effect", true);
    
    // State
    private EntityOtherPlayerMP fakePlayer = null;
    private final List<BreadcrumbPosition> breadcrumbTrail = new ArrayList<>();
    private double startX, startY, startZ;

    public Blink() {
        super("Blink", false);
    }

    @EventTarget(Priority.LOWEST)
    public void onTick(TickEvent event) {
        if (this.isEnabled() && event.getType() == EventType.POST) {
            if (!Myau.blinkManager.getBlinkingModule().equals(BlinkModules.BLINK)) {
                this.setEnabled(false);
                return;
            }
            
            // Record breadcrumb position
            if (breadcrumbs.getValue() && mc.thePlayer != null) {
                breadcrumbTrail.add(new BreadcrumbPosition(
                    mc.thePlayer.posX, 
                    mc.thePlayer.posY, 
                    mc.thePlayer.posZ
                ));
            }
            
            // LiquidBounce Auto Reset Feature
            if (autoReset.getValue()) {
                // Count movement packets in queue
                int movePackets = countMovementPackets();
                
                if (movePackets > resetAfter.getValue()) {
                    if (resetAction.getModeString().equals("Reset")) {
                        // Reset: Teleport back to first position and clear movement packets
                        resetToStart();
                    } else {
                        // Blink: Flush packets and update dummy position
                        Myau.blinkManager.setBlinkState(false, BlinkModules.BLINK);
                        Myau.blinkManager.setBlinkState(true, BlinkModules.BLINK);
                        
                        // Update dummy position if spawned
                        if (dummy.getValue() && fakePlayer != null) {
                            fakePlayer.setPosition(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
                        }
                    }
                    
                    // Auto disable after reset if enabled
                    if (autoDisable.getValue()) {
                        this.setEnabled(false);
                    }
                }
            }
        }
    }
    
    @EventTarget(Priority.HIGH)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.SEND) {
            return;
        }
        
        // LiquidBounce Ambush Feature: Auto-disable on attack
        if (ambush.getValue()) {
            Object packet = event.getPacket();
            String packetName = packet.getClass().getSimpleName();
            
            // Detect attack packets
            if (packetName.contains("C02PacketUseEntity") || packetName.contains("UseEntity")) {
                this.setEnabled(false);
                return;
            }
        }
    }
    
    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        
        // Render breadcrumbs trail
        if (breadcrumbs.getValue() && !breadcrumbTrail.isEmpty()) {
            renderBreadcrumbs();
        }
    }

    @EventTarget
    public void onWorldLoad(LoadWorldEvent event) {
        this.setEnabled(false);
    }

    @Override
    public void onEnabled() {
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        
        // Store starting position
        startX = mc.thePlayer.posX;
        startY = mc.thePlayer.posY;
        startZ = mc.thePlayer.posZ;
        
        // Clear breadcrumb trail
        breadcrumbTrail.clear();
        
        // LiquidBounce Dummy Feature: Spawn fake player clone
        if (dummy.getValue()) {
            fakePlayer = new EntityOtherPlayerMP(mc.theWorld, mc.thePlayer.getGameProfile());
            fakePlayer.copyLocationAndAnglesFrom(mc.thePlayer);
            fakePlayer.rotationYawHead = mc.thePlayer.rotationYawHead;
            fakePlayer.inventory = mc.thePlayer.inventory;
            // Use random negative ID to avoid conflicts
            mc.theWorld.addEntityToWorld(-100000 - (int)(Math.random() * 10000), fakePlayer);
        }
        
        // Start blinking
        Myau.blinkManager.setBlinkState(false, Myau.blinkManager.getBlinkingModule());
        Myau.blinkManager.setBlinkState(true, BlinkModules.BLINK);
    }

    @Override
    public void onDisabled() {
        // Remove dummy player clone
        if (fakePlayer != null && mc.theWorld != null) {
            mc.theWorld.removeEntity(fakePlayer);
            fakePlayer = null;
        }
        
        // Clear breadcrumbs
        breadcrumbTrail.clear();
        
        // Stop blinking and release all queued packets
        Myau.blinkManager.setBlinkState(false, BlinkModules.BLINK);
    }
    
    // ==================== Rendering ====================
    
    private void renderBreadcrumbs() {
        RenderUtil.enableRenderState();
        
        int trailSize = breadcrumbTrail.size();
        
        // LiquidBounce-style pulse effect
        float pulse = 1.0F;
        if (pulseEffect.getValue()) {
            long time = System.currentTimeMillis();
            pulse = (float) (0.8F + Math.sin(time / 200.0) * 0.2F); // Pulse between 0.6 and 1.0
        }
        
        for (int i = 0; i < trailSize; i++) {
            BreadcrumbPosition pos = breadcrumbTrail.get(i);
            
            double renderX = pos.x - ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosX();
            double renderY = pos.y - ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosY();
            double renderZ = pos.z - ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosZ();
            
            // Size based on pulse effect
            double size = 0.1 * pulse;
            
            // Create small box at breadcrumb position
            AxisAlignedBB box = new AxisAlignedBB(
                renderX - size, renderY, renderZ - size,
                renderX + size, renderY + size * 2, renderZ + size
            );
            
            // Fade alpha based on position in trail
            float alpha = 1.0F - ((float) i / trailSize) * 0.7F;
            alpha *= pulse; // Apply pulse to alpha
            int alphaValue = (int) (alpha * 255);
            
            // LiquidBounce-style color (light blue)
            int red = 135;
            int green = 206;
            int blue = 235;
            
            // Render breadcrumb with semi-transparent fill
            RenderUtil.drawFilledBox(box, red, green, blue);
            RenderUtil.drawBoundingBox(box, red, green, blue, alphaValue, 2.0F);
            
            // Draw line to next breadcrumb
            if (i < trailSize - 1) {
                BreadcrumbPosition next = breadcrumbTrail.get(i + 1);
                double nextRenderX = next.x - ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosX();
                double nextRenderY = next.y - ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosY();
                double nextRenderZ = next.z - ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosZ();
                
                GL11.glColor4f(red / 255.0F, green / 255.0F, blue / 255.0F, alpha);
                GL11.glBegin(GL11.GL_LINES);
                GL11.glVertex3d(renderX, renderY + size, renderZ);
                GL11.glVertex3d(nextRenderX, nextRenderY + size, nextRenderZ);
                GL11.glEnd();
            }
        }
        
        RenderUtil.disableRenderState();
    }
    
    // ==================== Utility Methods ====================
    
    /**
     * Count movement packets in the blink queue
     * LiquidBounce feature for auto-reset threshold
     */
    private int countMovementPackets() {
        int count = 0;
        for (Object packet : Myau.blinkManager.blinkedPackets) {
            if (packet instanceof C03PacketPlayer) {
                C03PacketPlayer movePacket = (C03PacketPlayer) packet;
                // Only count packets with position data
                if (movePacket.isMoving()) {
                    count++;
                }
            }
        }
        return count;
    }
    
    /**
     * Reset player to starting position
     * LiquidBounce's "Reset" action for auto-reset
     */
    private void resetToStart() {
        if (mc.thePlayer == null) return;
        
        // Teleport player back to start position
        mc.thePlayer.setPosition(startX, startY, startZ);
        
        // Clear only movement packets, keep other packets
        Myau.blinkManager.blinkedPackets.removeIf(packet -> {
            if (packet instanceof C03PacketPlayer) {
                return true; // Remove movement packets
            }
            return false; // Keep other packets
        });
        
        // Clear breadcrumbs after reset
        breadcrumbTrail.clear();
    }
    
    // ==================== Data Classes ====================
    
    private static class BreadcrumbPosition {
        final double x, y, z;
        
        BreadcrumbPosition(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
    
    @Override
    public String[] getSuffix() {
        // Show number of movement packets (LiquidBounce style)
        int movePackets = countMovementPackets();
        int totalPackets = Myau.blinkManager.blinkedPackets.size();
        return new String[]{String.format("§b%d §7/ §e%d", movePackets, totalPackets)};
    }
}
