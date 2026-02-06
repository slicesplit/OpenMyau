package myau.module.modules;

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
 * Blink - Chokes the packets you send to the server while enabled.
 * 
 * Direction:
 * - Outgoing Only: Only choke outgoing packets. You will see opponent movements, but opponents won't see yours.
 * - Bi-directional: Chokes incoming packets as well. You won't see server updates while module is enabled.
 * 
 * Type:
 * - All: Chokes all packets (movement, chat, etc).
 * - Movement Only: Exclusively chokes movement packets.
 * 
 * Breadcrumbs: Shows a trail of your previous positions.
 * Spawn Fake: Spawns a fake entity of yourself at your starting location.
 * Auto Send: Automatically unchoke packets once threshold is reached.
 */
public class Blink extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // Direction
    public final ModeProperty direction = new ModeProperty("direction", 0, new String[]{"Outgoing Only", "Bi-directional"});
    
    // Type
    public final ModeProperty type = new ModeProperty("type", 1, new String[]{"All", "Movement Only"});
    
    // Visual Options
    public final BooleanProperty breadcrumbs = new BooleanProperty("breadcrumbs", true);
    public final BooleanProperty spawnFake = new BooleanProperty("spawn-fake", true);
    
    // Auto Send
    public final BooleanProperty autoSend = new BooleanProperty("auto-send", false);
    public final IntProperty sendThreshold = new IntProperty("send-threshold", 20, 5, 100, () -> this.autoSend.getValue());
    
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
            
            // Auto send when threshold reached
            if (autoSend.getValue()) {
                int queuedPackets = Myau.blinkManager.blinkedPackets.size();
                if (queuedPackets >= sendThreshold.getValue()) {
                    this.setEnabled(false);
                }
            }
        }
    }
    
    @EventTarget(Priority.HIGH)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        
        // Bi-directional mode: Block incoming packets too
        if (direction.getModeString().equals("Bi-directional") && event.getType() == EventType.RECEIVE) {
            // Allow certain critical packets (keep-alive, etc) to prevent disconnect
            if (!isCriticalPacket(event.getPacket())) {
                event.setCancelled(true);
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
        
        // Spawn fake player
        if (spawnFake.getValue()) {
            fakePlayer = new EntityOtherPlayerMP(mc.theWorld, mc.thePlayer.getGameProfile());
            fakePlayer.copyLocationAndAnglesFrom(mc.thePlayer);
            fakePlayer.rotationYawHead = mc.thePlayer.rotationYawHead;
            fakePlayer.inventory = mc.thePlayer.inventory;
            mc.theWorld.addEntityToWorld(-69420, fakePlayer);
        }
        
        // Start blinking
        Myau.blinkManager.setBlinkState(false, Myau.blinkManager.getBlinkingModule());
        Myau.blinkManager.setBlinkState(true, BlinkModules.BLINK);
    }

    @Override
    public void onDisabled() {
        // Remove fake player
        if (fakePlayer != null && mc.theWorld != null) {
            mc.theWorld.removeEntityFromWorld(-69420);
            fakePlayer = null;
        }
        
        // Clear breadcrumbs
        breadcrumbTrail.clear();
        
        // Stop blinking and release packets
        Myau.blinkManager.setBlinkState(false, BlinkModules.BLINK);
    }
    
    // ==================== Rendering ====================
    
    private void renderBreadcrumbs() {
        RenderUtil.enableRenderState();
        
        int trailSize = breadcrumbTrail.size();
        for (int i = 0; i < trailSize; i++) {
            BreadcrumbPosition pos = breadcrumbTrail.get(i);
            
            double renderX = pos.x - ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosX();
            double renderY = pos.y - ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosY();
            double renderZ = pos.z - ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosZ();
            
            // Create small box at breadcrumb position
            AxisAlignedBB box = new AxisAlignedBB(
                renderX - 0.1, renderY, renderZ - 0.1,
                renderX + 0.1, renderY + 0.2, renderZ + 0.1
            );
            
            // Fade alpha based on position in trail
            float alpha = 1.0F - ((float) i / trailSize) * 0.8F;
            int alphaValue = (int) (alpha * 255);
            
            // Render breadcrumb
            RenderUtil.drawFilledBox(box, 255, 255, 255);
            RenderUtil.drawBoundingBox(box, 255, 255, 255, alphaValue, 2.0F);
            
            // Draw line to next breadcrumb
            if (i < trailSize - 1) {
                BreadcrumbPosition next = breadcrumbTrail.get(i + 1);
                double nextRenderX = next.x - ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosX();
                double nextRenderY = next.y - ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosY();
                double nextRenderZ = next.z - ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosZ();
                
                GL11.glColor4f(1.0F, 1.0F, 1.0F, alpha);
                GL11.glBegin(GL11.GL_LINES);
                GL11.glVertex3d(renderX, renderY + 0.1, renderZ);
                GL11.glVertex3d(nextRenderX, nextRenderY + 0.1, nextRenderZ);
                GL11.glEnd();
            }
        }
        
        RenderUtil.disableRenderState();
    }
    
    // ==================== Utility ====================
    
    private boolean isCriticalPacket(Object packet) {
        // Allow keep-alive and other critical packets to prevent disconnect
        String packetName = packet.getClass().getSimpleName();
        return packetName.contains("KeepAlive") || 
               packetName.contains("Transaction") ||
               packetName.contains("Disconnect");
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
        int queuedPackets = Myau.blinkManager.blinkedPackets.size();
        return new String[]{String.format("§e%d §7packets", queuedPackets)};
    }
}
