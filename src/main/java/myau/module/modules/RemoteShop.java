package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.inventory.Container;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import net.minecraft.network.play.server.S2DPacketOpenWindow;
import net.minecraft.network.play.server.S2EPacketCloseWindow;
import net.minecraft.network.play.server.S2FPacketSetSlot;
import net.minecraft.network.play.server.S30PacketWindowItems;
import org.lwjgl.input.Keyboard;

public class RemoteShop extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // Settings
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"NORMAL", "HYPIXEL"});
    public final IntProperty activationKey = new IntProperty("activation-key", Keyboard.KEY_HOME, 0, 256);
    public final BooleanProperty cancelInventory = new BooleanProperty("cancel-inventory", true);
    public final BooleanProperty autoClose = new BooleanProperty("auto-close", true);
    public final IntProperty closeDelay = new IntProperty("close-delay", 100, 0, 500, () -> this.autoClose.getValue());
    public final BooleanProperty clickOptimization = new BooleanProperty("click-optimization", true);
    
    // State tracking
    private boolean keyPressed = false;
    private boolean shopOpened = false;
    private int windowId = -1;
    private long lastInteractionTime = 0L;
    private long shopOpenTime = 0L;
    
    public RemoteShop() {
        super("RemoteShop", false);
    }
    
    @Override
    public void onEnabled() {
        keyPressed = false;
        shopOpened = false;
        windowId = -1;
        lastInteractionTime = 0L;
        shopOpenTime = 0L;
    }
    
    @Override
    public void onDisabled() {
        if (shopOpened) {
            forceCloseShop();
        }
        keyPressed = false;
        shopOpened = false;
        windowId = -1;
    }
    
    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        
        // Handle activation key
        boolean keyDown = Keyboard.isKeyDown(this.activationKey.getValue());
        
        if (keyDown && !keyPressed) {
            keyPressed = true;
            openRemoteShop();
        } else if (!keyDown) {
            keyPressed = false;
        }
        
        // Auto-close check
        if (shopOpened && this.autoClose.getValue()) {
            long timeSinceOpen = System.currentTimeMillis() - shopOpenTime;
            if (timeSinceOpen > this.closeDelay.getValue() && !(mc.currentScreen instanceof GuiContainer)) {
                forceCloseShop();
            }
        }
        
        // Keep shop open if container is open
        if (mc.currentScreen instanceof GuiContainer && !(mc.currentScreen instanceof GuiInventory && this.cancelInventory.getValue())) {
            if (shopOpened) {
                lastInteractionTime = System.currentTimeMillis();
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
                if (shopOpened) {
                    windowId = packet.getWindowId();
                }
            } else if (event.getPacket() instanceof S2EPacketCloseWindow) {
                if (shopOpened) {
                    forceCloseShop();
                }
            } else if (event.getPacket() instanceof S30PacketWindowItems || event.getPacket() instanceof S2FPacketSetSlot) {
                if (shopOpened) {
                    lastInteractionTime = System.currentTimeMillis();
                }
            }
        }
        
        // Handle outgoing packets
        if (event.getType() == EventType.SEND) {
            // Cancel inventory open if enabled
            if (event.getPacket() instanceof C0BPacketEntityAction && this.cancelInventory.getValue()) {
                C0BPacketEntityAction packet = (C0BPacketEntityAction) event.getPacket();
                if (packet.getAction() == C0BPacketEntityAction.Action.OPEN_INVENTORY && shopOpened) {
                    event.setCancelled(true);
                }
            }
            
            // Optimize clicks
            if (event.getPacket() instanceof C0EPacketClickWindow && this.clickOptimization.getValue()) {
                C0EPacketClickWindow packet = (C0EPacketClickWindow) event.getPacket();
                if (shopOpened && windowId >= 0 && packet.getWindowId() == windowId) {
                    lastInteractionTime = System.currentTimeMillis();
                }
            }
        }
    }
    
    private void openRemoteShop() {
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        
        if (shopOpened) {
            return; // Already open
        }
        
        // Mode-specific opening logic
        switch (this.mode.getValue()) {
            case 0: // NORMAL
                openNormalShop();
                break;
            case 1: // HYPIXEL
                openHypixelShop();
                break;
        }
        
        shopOpened = true;
        shopOpenTime = System.currentTimeMillis();
    }
    
    private void openNormalShop() {
        // Normal mode: Try to interact with nearby villagers/shop NPCs
        // This would require entity interaction packets
        // For now, just set the flag
        shopOpened = true;
    }
    
    private void openHypixelShop() {
        // Hypixel mode: Use command-based shop opening
        // Most Hypixel minigames use /shop or similar
        if (mc.thePlayer != null) {
            mc.thePlayer.sendChatMessage("/shop");
            shopOpened = true;
        }
    }
    
    private void forceCloseShop() {
        if (mc.currentScreen instanceof GuiContainer) {
            mc.thePlayer.closeScreen();
        }
        
        shopOpened = false;
        windowId = -1;
    }
    
    @Override
    public String[] getSuffix() {
        if (shopOpened) {
            return new String[]{"§a[OPEN]"};
        }
        return new String[]{this.mode.getModeString()};
    }
}
