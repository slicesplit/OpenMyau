package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.PacketEvent;
import myau.events.Render3DEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ColorProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.PacketUtil;
import myau.util.TimerUtil;
import myau.util.RenderUtil;
import myau.mixin.IAccessorRenderManager;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.*;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * FakeLag Module
 * 
 * Based on LiquidBounce's implementation (https://github.com/CCBlueX/LiquidBounce)
 * Copyright (c) 2015 - 2026 CCBlueX
 * Licensed under GNU General Public License v3.0
 * 
 * Adapted for Myau client with permission from CCBlueX
 * 
 * Holds back packets to prevent you from being hit by an enemy.
 */
@ModuleInfo(category = ModuleCategory.MISC)
public class FakeLag extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // Settings
    public final IntProperty minRange = new IntProperty("min-range", 2, 0, 10);
    public final IntProperty maxRange = new IntProperty("max-range", 5, 0, 10);
    public final IntProperty minDelay = new IntProperty("min-delay", 300, 0, 1000);
    public final IntProperty maxDelay = new IntProperty("max-delay", 600, 0, 1000);
    public final IntProperty recoilTime = new IntProperty("recoil-time", 250, 0, 1000);
    public final ModeProperty mode = new ModeProperty("mode", 1, new String[]{"Constant", "Dynamic"});
    
    // Flush options
    public final BooleanProperty flushOnEntityInteract = new BooleanProperty("flush-entity-interact", true);
    public final BooleanProperty flushOnBlockInteract = new BooleanProperty("flush-block-interact", true);
    public final BooleanProperty flushOnAction = new BooleanProperty("flush-action", false);
    
    // Rendering (self only)
    public final BooleanProperty renderSelf = new BooleanProperty("render-self", true);
    public final ColorProperty color = new ColorProperty("color", 0x87CEEB); // Sky blue

    private final TimerUtil chronometer = new TimerUtil();
    private int nextDelay;
    private boolean isEnemyNearby = false;
    
    // Self position tracking for rendering
    private Vec3 startPosition = null;
    
    // Store server position tracking
    private final List<Vec3> positions = new ArrayList<>();

    public FakeLag() {
        super("FakeLag", false);
        this.chronometer.reset();
        this.nextDelay = randomDelay();
    }

    @Override
    public void onEnabled() {
        positions.clear();
        chronometer.reset();
        nextDelay = randomDelay();
        isEnemyNearby = false;
        
        // Store starting position for rendering
        if (mc.thePlayer != null) {
            startPosition = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
        }
    }

    @Override
    public void onDisabled() {
        // Flush all packets when disabled
        Myau.blinkManager.setBlinkState(false, myau.enums.BlinkModules.FAKELAG);
        positions.clear();
        isEnemyNearby = false;
        startPosition = null;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE || mc.thePlayer == null) {
            return;
        }

        // Check for enemies in range
        isEnemyNearby = findEnemy() != null;
    }
    
    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || !renderSelf.getValue()) {
            return;
        }
        
        // Only render if we're actively lagging (blinking)
        if (!Myau.blinkManager.isBlinking() || startPosition == null) {
            return;
        }
        
        double renderX = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosX();
        double renderY = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosY();
        double renderZ = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosZ();
        
        // Render self at server position (start position)
        double x = startPosition.xCoord - renderX;
        double y = startPosition.yCoord - renderY;
        double z = startPosition.zCoord - renderZ;
        
        // Create bounding box at server position (where server thinks we are)
        AxisAlignedBB box = new AxisAlignedBB(
            x - 0.3, y, z - 0.3,
            x + 0.3, y + 1.8, z + 0.3
        );
        
        Color c = new Color(color.getValue());
        
        // Render filled box
        RenderUtil.drawFilledBox(box, c.getRed(), c.getGreen(), c.getBlue());
        
        // Render outline
        RenderUtil.drawBoundingBox(box, c.getRed(), c.getGreen(), c.getBlue(), 180, 2.0F);
    }

    @EventTarget(Priority.HIGHEST)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.SEND || mc.thePlayer == null) {
            return;
        }

        if (mc.thePlayer.isDead || mc.thePlayer.isInWater() || mc.currentScreen != null) {
            // Flush on death, water, or GUI open
            Myau.blinkManager.setBlinkState(false, myau.enums.BlinkModules.FAKELAG);
            positions.clear();
            startPosition = null; // Clear server position
            chronometer.reset(); // Reset timer
            return;
        }

        Packet<?> packet = event.getPacket();

        // Handle recoil time
        if (!chronometer.hasTimeElapsed(recoilTime.getValue())) {
            return;
        }

        // Check if we should flush based on delay
        if (isAboveTime(nextDelay)) {
            nextDelay = randomDelay();
            Myau.blinkManager.setBlinkState(false, myau.enums.BlinkModules.FAKELAG);
            positions.clear();
            startPosition = null; // Clear server position when flushing
            chronometer.reset(); // Reset timer after flush
            return;
        }

        // Flush on specific packet types
        if (shouldFlushOnPacket(packet)) {
            chronometer.reset();
            Myau.blinkManager.setBlinkState(false, myau.enums.BlinkModules.FAKELAG);
            positions.clear();
            startPosition = null; // Clear server position when flushing
            return;
        }

        // Never queue these critical packets
        if (packet instanceof C00PacketKeepAlive 
            || packet instanceof C01PacketChatMessage
            || packet instanceof C0FPacketConfirmTransaction) {
            return;
        }

        // Handle mode-specific logic
        String currentMode = mode.getModeString();
        
        if (currentMode.equals("Constant")) {
            // In constant mode, always queue packets
            queuePacket(event);
        } else if (currentMode.equals("Dynamic")) {
            // In dynamic mode, only queue if enemy is nearby and conditions are met
            if (!isEnemyNearby) {
                return;
            }

            // Track position if this is a movement packet
            if (packet instanceof C03PacketPlayer) {
                C03PacketPlayer movePacket = (C03PacketPlayer) packet;
                if (movePacket.isMoving()) {
                    Vec3 position = new Vec3(movePacket.getPositionX(), movePacket.getPositionY(), movePacket.getPositionZ());
                    positions.add(position);
                }
            }

            Vec3 serverPosition = positions.isEmpty() ? mc.thePlayer.getPositionVector() : positions.get(0);
            AxisAlignedBB playerBox = mc.thePlayer.getEntityBoundingBox().offset(
                serverPosition.xCoord - mc.thePlayer.posX,
                serverPosition.yCoord - mc.thePlayer.posY,
                serverPosition.zCoord - mc.thePlayer.posZ
            );

            List<Entity> entities = getEntitiesInRange(serverPosition, maxRange.getValue());

            if (entities.isEmpty()) {
                return;
            }

            // Check if any entity intersects with our server position
            boolean intersects = false;
            for (Entity entity : entities) {
                if (entity.getEntityBoundingBox().intersectsWith(playerBox)) {
                    intersects = true;
                    break;
                }
            }

            // Calculate distances
            double serverDistance = Double.MAX_VALUE;
            double clientDistance = Double.MAX_VALUE;

            for (Entity entity : entities) {
                double serverDist = entity.getPositionVector().distanceTo(serverPosition);
                double clientDist = mc.thePlayer.getDistanceToEntity(entity);
                
                if (serverDist < serverDistance) {
                    serverDistance = serverDist;
                }
                if (clientDist < clientDistance) {
                    clientDistance = clientDist;
                }
            }

            // If server position is not closer than client position, or if intersecting, don't queue
            if (serverDistance < clientDistance || intersects) {
                return;
            }

            queuePacket(event);
        }
    }

    private void queuePacket(PacketEvent event) {
        // Start blinking if not already
        if (!Myau.blinkManager.isBlinking()) {
            // Store server position when starting to blink
            if (mc.thePlayer != null) {
                startPosition = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
            }
            Myau.blinkManager.setBlinkState(true, myau.enums.BlinkModules.FAKELAG);
        }

        // Cancel the event so the packet gets queued by BlinkManager
        event.setCancelled(true);
        
        // Manually offer to blink manager
        if (Myau.blinkManager.offerPacket(event.getPacket())) {
            // Packet was queued successfully
        } else {
            // Packet was not queued, send it normally
            PacketUtil.sendPacketNoEvent(event.getPacket());
        }
    }

    private boolean shouldFlushOnPacket(Packet<?> packet) {
        // Entity interaction packets
        if (flushOnEntityInteract.getValue() && 
            (packet instanceof C02PacketUseEntity || packet instanceof C0APacketAnimation)) {
            return true;
        }

        // Block interaction packets
        if (flushOnBlockInteract.getValue() && 
            (packet instanceof C08PacketPlayerBlockPlacement || packet instanceof C12PacketUpdateSign)) {
            return true;
        }

        // Player action packets
        if (flushOnAction.getValue() && packet instanceof C07PacketPlayerDigging) {
            return true;
        }

        return false;
    }

    private EntityPlayer findEnemy() {
        float range = maxRange.getValue();
        EntityPlayer closest = null;
        double closestDistance = Double.MAX_VALUE;

        for (Object obj : mc.theWorld.playerEntities) {
            if (!(obj instanceof EntityPlayer)) continue;
            
            EntityPlayer player = (EntityPlayer) obj;
            
            // Skip self, dead players, and teammates
            if (player == mc.thePlayer || player.isDead || player.isInvisible()) {
                continue;
            }

            double distance = mc.thePlayer.getDistanceToEntity(player);
            if (distance <= range && distance < closestDistance) {
                closestDistance = distance;
                closest = player;
            }
        }

        return closest;
    }

    private List<Entity> getEntitiesInRange(Vec3 position, double range) {
        List<Entity> result = new ArrayList<>();

        for (Object obj : mc.theWorld.loadedEntityList) {
            if (!(obj instanceof Entity)) continue;
            
            Entity entity = (Entity) obj;
            
            // Skip self and invalid entities
            if (entity == mc.thePlayer || entity.isDead) {
                continue;
            }

            // Check if it's an attackable entity (player or mob)
            if (entity instanceof EntityPlayer) {
                EntityPlayer player = (EntityPlayer) entity;
                if (player.isInvisible()) {
                    continue;
                }
            }

            double distance = entity.getPositionVector().distanceTo(position);
            if (distance <= range) {
                result.add(entity);
            }
        }

        return result;
    }

    private boolean isAboveTime(long delay) {
        // Use the new BlinkManager's isAboveTime method
        return Myau.blinkManager.isAboveTime(delay);
    }

    private int randomDelay() {
        int min = minDelay.getValue();
        int max = maxDelay.getValue();
        if (min >= max) {
            return min;
        }
        return min + (int) (Math.random() * (max - min));
    }

    @Override
    public String[] getSuffix() {
        return new String[]{String.format("%s (%d-%dms)", mode.getModeString(), minDelay.getValue(), maxDelay.getValue())};
    }
}
