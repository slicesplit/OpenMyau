package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.module.Module;
import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.gui.inventory.GuiInventory;
import org.lwjgl.input.Keyboard;

/**
 * RemoteShop - AliExpress style shop caching
 * 
 * Simple logic:
 * 1. Open a shop GUI (Bedwars, SkyWars, etc.)
 * 2. Press HOME → GUI closes CLIENT-SIDE (server thinks it's still open)
 * 3. Press HOME again → GUI reopens instantly from cache
 * 4. Buy stuff normally - server never knew you "closed" it
 * 5. Repeat infinitely
 * 
 * Perfect for quick shopping without the GUI blocking your view!
 */
@ModuleInfo(category = ModuleCategory.MISC)
public class RemoteShop extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // Cache the last non-inventory GUI
    private GuiScreen cachedGui = null;
    private boolean isHidden = false;
    
    // Toggle key
    private boolean wasHomePressed = false;
    
    public RemoteShop() {
        super("RemoteShop", false);
    }
    
    @Override
    public void onEnabled() {
        super.onEnabled();
        cachedGui = null;
        isHidden = false;
        wasHomePressed = false;
    }
    
    @Override
    public void onDisabled() {
        super.onDisabled();
        // Restore GUI if it was hidden
        if (isHidden && cachedGui != null) {
            mc.displayGuiScreen(cachedGui);
        }
        cachedGui = null;
        isHidden = false;
    }
    
    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;
        
        // Cache any non-inventory GUI that opens
        if (mc.currentScreen != null && !(mc.currentScreen instanceof GuiInventory)) {
            // This is a shop/chest GUI - cache it!
            if (cachedGui == null || mc.currentScreen != cachedGui) {
                cachedGui = mc.currentScreen;
                isHidden = false;
            }
        }
        
        // HOME key toggle logic
        boolean homePressed = Keyboard.isKeyDown(Keyboard.KEY_HOME);
        
        if (homePressed && !wasHomePressed) {
            // HOME key just pressed - toggle!
            
            if (cachedGui == null) {
                // No cached GUI
                return;
            }
            
            if (isHidden) {
                // GUI is hidden - reopen it!
                mc.displayGuiScreen(cachedGui);
                isHidden = false;
            } else {
                // GUI is open - hide it CLIENT-SIDE!
                if (mc.currentScreen == cachedGui) {
                    mc.displayGuiScreen(null);
                    isHidden = true;
                    // Server still thinks GUI is open - you can still buy stuff!
                }
            }
        }
        
        wasHomePressed = homePressed;
    }
    
    @Override
    public String[] getSuffix() {
        if (cachedGui != null) {
            String status = isHidden ? "Hidden" : "Shown";
            String guiType = cachedGui instanceof GuiChest ? "Shop" : "GUI";
            return new String[]{status + " [" + guiType + "]"};
        }
        return new String[]{"No Cache"};
    }
}
