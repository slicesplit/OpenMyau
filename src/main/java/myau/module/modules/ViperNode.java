package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;

import myau.Myau;
import myau.enums.BlinkModules;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.*;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.mixin.IAccessorRenderManager;
import myau.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.*;
import net.minecraft.network.play.server.*;
import net.minecraft.util.AxisAlignedBB;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * ViperNode - Production-Grade Packet Interceptor
 * 
 * Based on Blink module architecture with proper packet management.
 * Delays outgoing movement packets to create lag advantage without teleporting back.
 * 
 * Features:
 * - Proper packet queue management (no teleport back on disable)
 * - Automatic packet release to prevent desync
 * - Visual breadcrumb trail
 * - Configurable delay and auto-release
 */
@ModuleInfo(category = ModuleCategory.COMBAT)
public class ViperNode extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    public final ModeProperty direction = new ModeProperty("Direction", 0, new String[]{"Outgoing Only", "Bi-directional"});
    public final ModeProperty type = new ModeProperty("Type", 1, new String[]{"All", "Movement Only"});
    public final BooleanProperty breadcrumbs = new BooleanProperty("Breadcrumbs", true);
    public final IntProperty maxDelay = new IntProperty("Max Delay (ms)", 500, 100, 2000);
    public final IntProperty autoSend = new IntProperty("Auto Send", 20, 0, 100);
    
    private final List<Packet<?>> incomingPackets = new ArrayList<>();
    private final List<Packet<?>> outgoingPackets = new ArrayList<>();
    private final List<double[]> breadcrumbPositions = new ArrayList<>();
    
    private double startX, startY, startZ;
    private long enabledTime = 0L;
    private int ticksSinceEnabled = 0;

    public ViperNode() {
        super("ViperNode", false);
    }

    @Override
    public void onEnabled() {
        if (mc.thePlayer == null) {
            return;
        }
        
        Myau.blinkModuleEnabled = BlinkModules.VIPERNODE;
        
        incomingPackets.clear();
        outgoingPackets.clear();
        breadcrumbPositions.clear();
        
        startX = mc.thePlayer.posX;
        startY = mc.thePlayer.posY;
        startZ = mc.thePlayer.posZ;
        
        enabledTime = System.currentTimeMillis();
        ticksSinceEnabled = 0;
    }

    @Override
    public void onDisabled() {
        // CRITICAL: Release all packets in order to prevent teleport back
        releasePackets();
        
        Myau.blinkModuleEnabled = BlinkModules.NONE;
        
        breadcrumbPositions.clear();
    }

    private void releasePackets() {
        if (mc.getNetHandler() == null) {
            return;
        }
        
        // Release incoming packets first (server state updates)
        for (Packet<?> packet : incomingPackets) {
            try {
                packet.processPacket(mc.getNetHandler().getNetworkManager().getNetHandler());
            } catch (Exception e) {
                // Ignore processing errors
            }
        }
        incomingPackets.clear();
        
        // Then release outgoing packets (client movements)
        for (Packet<?> packet : outgoingPackets) {
            mc.getNetHandler().addToSendQueue(packet);
        }
        outgoingPackets.clear();
    }

    @EventTarget(priority = Priority.HIGHEST)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }

        // Handle incoming packets
        if (event.getType() == EventType.RECEIVE) {
            if (direction.getModeString().equals("Bi-directional")) {
                // Only delay entity and world packets, never delay critical packets
                if (event.getPacket() instanceof S0EPacketSpawnObject ||
                    event.getPacket() instanceof S11PacketSpawnExperienceOrb ||
                    event.getPacket() instanceof S2CPacketSpawnGlobalEntity ||
                    event.getPacket() instanceof S0FPacketSpawnMob ||
                    event.getPacket() instanceof S10PacketSpawnPainting ||
                    event.getPacket() instanceof S0CPacketSpawnPlayer ||
                    event.getPacket() instanceof S14PacketEntity ||
                    event.getPacket() instanceof S18PacketEntityTeleport ||
                    event.getPacket() instanceof S19PacketEntityHeadLook ||
                    event.getPacket() instanceof S19PacketEntityStatus ||
                    event.getPacket() instanceof S1BPacketEntityAttach ||
                    event.getPacket() instanceof S1CPacketEntityMetadata ||
                    event.getPacket() instanceof S12PacketEntityVelocity) {
                    
                    incomingPackets.add(event.getPacket());
                    event.setCancelled(true);
                }
            }
            
            // Never delay these critical packets - they cause flags
            if (event.getPacket() instanceof S08PacketPlayerPosLook) {
                // Server teleport - must clear and disable immediately
                this.setEnabled(false);
                return;
            }
            
            if (event.getPacket() instanceof S40PacketDisconnect) {
                // Disconnect - clear immediately
                this.setEnabled(false);
                return;
            }
        }
        
        // Handle outgoing packets
        if (event.getType() == EventType.SEND) {
            boolean shouldCancel = false;
            
            if (type.getModeString().equals("Movement Only")) {
                // Only cancel movement packets
                if (event.getPacket() instanceof C03PacketPlayer) {
                    shouldCancel = true;
                }
            } else {
                // Cancel all packets except critical ones
                if (!(event.getPacket() instanceof C0FPacketConfirmTransaction) &&
                    !(event.getPacket() instanceof C00PacketKeepAlive) &&
                    !(event.getPacket() instanceof C16PacketClientStatus)) {
                    shouldCancel = true;
                }
            }
            
            if (shouldCancel) {
                outgoingPackets.add(event.getPacket());
                event.setCancelled(true);
            }
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || event.getType() != EventType.PRE) {
            return;
        }
        
        ticksSinceEnabled++;
        
        // Add breadcrumb position
        if (breadcrumbs.getValue()) {
            breadcrumbPositions.add(new double[]{mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ});
            
            // Limit breadcrumb count
            if (breadcrumbPositions.size() > 200) {
                breadcrumbPositions.remove(0);
            }
        }
        
        // Auto release based on packet count
        if (autoSend.getValue() > 0 && outgoingPackets.size() >= autoSend.getValue()) {
            this.setEnabled(false);
            return;
        }
        
        // Auto release based on time delay
        long currentDelay = System.currentTimeMillis() - enabledTime;
        if (currentDelay >= maxDelay.getValue()) {
            this.setEnabled(false);
            return;
        }
    }
    
    @EventTarget
    public void onWorldLoad(LoadWorldEvent event) {
        // Clear packets on world change to prevent issues
        if (this.isEnabled()) {
            this.setEnabled(false);
        }
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || !breadcrumbs.getValue()) {
            return;
        }

        if (breadcrumbPositions.isEmpty()) {
            return;
        }

        double renderX = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosX();
        double renderY = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosY();
        double renderZ = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosZ();

        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glLineWidth(2.0f);

        GL11.glBegin(GL11.GL_LINE_STRIP);
        
        // Color gradient from blue (start) to red (current)
        for (int i = 0; i < breadcrumbPositions.size(); i++) {
            double[] pos = breadcrumbPositions.get(i);
            float progress = (float) i / breadcrumbPositions.size();
            
            // Blue to cyan to red gradient
            Color color = new Color(
                (int)(progress * 255),
                (int)((1 - Math.abs(progress - 0.5) * 2) * 255),
                (int)((1 - progress) * 255),
                180
            );
            
            RenderUtil.glColor(color);
            GL11.glVertex3d(pos[0] - renderX, pos[1] - renderY, pos[2] - renderZ);
        }
        
        GL11.glEnd();

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glPopMatrix();
    }
}
