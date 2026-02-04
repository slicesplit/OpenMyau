package myau.module.modules;

import myau.Myau;
import myau.enums.BlinkModules;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.server.S2DPacketOpenWindow;
import net.minecraft.network.play.server.S2EPacketCloseWindow;
import org.lwjgl.input.Keyboard;

public class RemoteShop extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // Settings
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"NORMAL", "HYPIXEL"});
    public final IntProperty activationKey = new IntProperty("activation-key", Keyboard.KEY_HOME, 0, 256);
    public final BooleanProperty cancelInventory = new BooleanProperty("cancel-inventory", true);
    
    // State tracking
    private boolean keyPressed = false;
    private boolean shopActive = false;
    private Entity shopEntity = null;
    private int windowId = -1;
    
    public RemoteShop() {
        super("RemoteShop", false);
    }
    
    @Override
    public void onEnabled() {
        keyPressed = false;
        shopActive = false;
        shopEntity = null;
        windowId = -1;
    }
    
    @Override
    public void onDisabled() {
        forceClose();
        keyPressed = false;
    }
    
    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        
        // Handle activation key (RenderTickEvent equivalent)
        boolean keyDown = Keyboard.isKeyDown(this.activationKey.getValue());
        
        if (keyDown && !keyPressed) {
            keyPressed = true;
            activateRemoteShop();
        } else if (!keyDown) {
            keyPressed = false;
        }
        
        // Keep container open (onUpdate equivalent)
        if (mc.currentScreen instanceof GuiContainer && 
            !(mc.currentScreen instanceof GuiInventory && this.cancelInventory.getValue())) {
            if (shopActive) {
                openContainer();
            }
        }
    }
    
    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        
        // Handle incoming packets
        if (event.getType() == EventType.RECEIVE) {
            if (event.getPacket() instanceof S2DPacketOpenWindow) {
                S2DPacketOpenWindow packet = (S2DPacketOpenWindow) event.getPacket();
                windowId = packet.getWindowId();
            } else if (event.getPacket() instanceof S2EPacketCloseWindow) {
                forceClose();
            }
        }
        
        // Handle outgoing packets
        if (event.getType() == EventType.SEND) {
            // Cancel inventory open if enabled
            if (event.getPacket() instanceof C0BPacketEntityAction && this.cancelInventory.getValue()) {
                C0BPacketEntityAction packet = (C0BPacketEntityAction) event.getPacket();
                if (packet.getAction() == C0BPacketEntityAction.Action.OPEN_INVENTORY) {
                    event.setCancelled(true);
                }
            }
        }
    }
    
    @EventTarget
    public void onWorldChange(LoadWorldEvent event) {
        forceClose();
    }
    
    private void activateRemoteShop() {
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        
        switch (this.mode.getValue()) {
            case 0: // NORMAL
                openNormalShop();
                break;
            case 1: // HYPIXEL
                openHypixelShop();
                break;
        }
    }
    
    private void openNormalShop() {
        // Find nearest villager/shop NPC
        Entity nearestShop = null;
        double nearestDistance = Double.MAX_VALUE;
        
        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (entity instanceof EntityVillager && entity != mc.thePlayer) {
                double distance = mc.thePlayer.getDistanceToEntity(entity);
                if (distance < nearestDistance && distance < 100.0) { // Within 100 blocks
                    nearestDistance = distance;
                    nearestShop = entity;
                }
            }
        }
        
        if (nearestShop != null) {
            shopEntity = nearestShop;
            shopActive = true;
            
            // Interact with entity
            PacketUtil.sendPacket(new C02PacketUseEntity(nearestShop, C02PacketUseEntity.Action.INTERACT));
        }
    }
    
    private void openHypixelShop() {
        // Hypixel mode: Use command
        if (mc.thePlayer != null) {
            mc.thePlayer.sendChatMessage("/shop");
            shopActive = true;
        }
    }
    
    private void openContainer() {
        // Keep container interaction active
        if (shopEntity != null && this.mode.getValue() == 0) {
            // Re-interact if needed
            if (mc.currentScreen == null) {
                PacketUtil.sendPacket(new C02PacketUseEntity(shopEntity, C02PacketUseEntity.Action.INTERACT));
            }
        }
    }
    
    private void forceClose() {
        if (mc.currentScreen instanceof GuiContainer) {
            mc.thePlayer.closeScreen();
        }
        shopActive = false;
        shopEntity = null;
        windowId = -1;
    }
    
    @Override
    public String[] getSuffix() {
        if (shopActive) {
            return new String[]{"§a[ACTIVE]"};
        }
        return new String[]{this.mode.getModeString()};
    }
}
