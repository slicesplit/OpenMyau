package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.module.modules.remoteshop.HypixelRemoteShop;
import myau.module.modules.remoteshop.IRemoteShop;
import myau.module.modules.remoteshop.NormalRemoteShop;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.server.S2EPacketCloseWindow;
import org.lwjgl.input.Keyboard;

/**
 * RemoteShop - Cache and reopen shop GUIs remotely
 * 
 * Allows you to access shop menus from anywhere by caching the GUI.
 * Press HOME key (default) to reopen the last cached shop.
 * 
 * Modes:
 * - Normal: Cache any chest GUI
 * - Hypixel: Smart detection of Hypixel shop menus
 * 
 * Based on KeystrokesMod's architecture
 */
@ModuleInfo(category = ModuleCategory.MISC)
public class RemoteShop extends Module {
    public static final int KEYCODE = Keyboard.KEY_HOME;
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // Mode selection with submodes
    private final IRemoteShop[] modes;
    public final ModeProperty mode;
    public final BooleanProperty cancelInventory;
    
    // State tracking
    private boolean isToggled = false;
    
    public RemoteShop() {
        super("RemoteShop", false);
        
        // Initialize modes
        this.modes = new IRemoteShop[]{
            new NormalRemoteShop("Normal", this),
            new HypixelRemoteShop("Hypixel", this)
        };
        
        // Create mode property
        String[] modeNames = new String[modes.length];
        for (int i = 0; i < modes.length; i++) {
            modeNames[i] = modes[i].getName();
        }
        this.mode = new ModeProperty("mode", 0, modeNames);
        
        // Settings
        this.cancelInventory = new BooleanProperty("cancel-inventory", true);
    }
    
    @Override
    public void onEnabled() {
        isToggled = false;
        getSelectedMode().onEnable();
    }
    
    @Override
    public void onDisabled() {
        getSelectedMode().onDisable();
        isToggled = false;
    }
    
    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        
        try {
            // Handle activation key (like RenderTickEvent in KeystrokesMod)
            if (!this.isToggled && Keyboard.isKeyDown(KEYCODE)) {
                getSelectedMode().remoteShop();
                this.isToggled = true;
            } else if (!Keyboard.isKeyDown(KEYCODE)) {
                this.isToggled = false;
            }
            
            // Keep container open (onUpdate equivalent)
            if (mc.currentScreen instanceof GuiContainer && 
                !(mc.currentScreen instanceof GuiInventory && this.cancelInventory.getValue())) {
                getSelectedMode().openContainer();
            }
        } catch (Throwable ignored) {
            // Silently catch any errors
        }
    }
    
    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        
        // Handle incoming packets
        if (event.getType() == EventType.RECEIVE) {
            if (event.getPacket() instanceof S2EPacketCloseWindow) {
                getSelectedMode().forceClose();
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
        getSelectedMode().forceClose();
    }
    
    /**
     * Get currently selected mode
     */
    private IRemoteShop getSelectedMode() {
        int index = mode.getValue();
        if (index >= 0 && index < modes.length) {
            return modes[index];
        }
        return modes[0]; // Fallback to Normal
    }
    
    @Override
    public String[] getSuffix() {
        return new String[]{getSelectedMode().getPrettyName()};
    }
}
