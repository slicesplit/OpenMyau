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
 * FIXED LOGIC:
 * - Shop GUI opens normally → Gets cached automatically
 * - Press E or ESC → Hides GUI CLIENT-SIDE (server still has it open!)
 * - Press HOME → Reopens the cached shop from anywhere
 * - Buy items normally → Server thinks GUI was open the whole time
 * - Works with InvMove, doesn't break chest/block placement
 * 
 * Controls:
 * HOME = Open cached shop menu
 * E/ESC = Hide shop (client-side only, doesn't close server-side)
 */
@ModuleInfo(category = ModuleCategory.MISC)
public class RemoteShop extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // Cached shop GUI (server thinks this is still open)
    private GuiScreen cachedShop = null;
    
    // Key state tracking
    private boolean wasHomePressed = false;
    private boolean wasEPressed = false;
    private boolean wasEscPressed = false;
    
    public RemoteShop() {
        super("RemoteShop", false);
    }
    
    @Override
    public void onEnabled() {
        super.onEnabled();
        cachedShop = null;
        wasHomePressed = false;
        wasEPressed = false;
        wasEscPressed = false;
    }
    
    @Override
    public void onDisabled() {
        super.onDisabled();
        cachedShop = null;
    }
    
    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;
        
        // STEP 1: Cache shop GUIs when they open (but not normal chests)
        if (mc.currentScreen != null && mc.currentScreen instanceof GuiChest) {
            GuiChest chest = (GuiChest) mc.currentScreen;
            
            // Use reflection to access lowerChestInventory (private field)
            try {
                java.lang.reflect.Field inventoryField = GuiChest.class.getDeclaredField("lowerChestInventory");
                inventoryField.setAccessible(true);
                net.minecraft.inventory.IInventory inventory = (net.minecraft.inventory.IInventory) inventoryField.get(chest);
                
                if (inventory != null) {
                    String title = inventory.getDisplayName().getUnformattedText().toLowerCase();
                    
                    // Only cache if it looks like a shop (not a regular chest)
                    if (isShopGUI(title)) {
                        cachedShop = mc.currentScreen;
                    }
                }
            } catch (NoSuchFieldException | IllegalAccessException e) {
                // Silently ignore reflection errors
            }
        }
        
        // STEP 2: E key - hide current shop GUI (client-side close)
        boolean ePressed = Keyboard.isKeyDown(Keyboard.KEY_E);
        if (ePressed && !wasEPressed) {
            if (mc.currentScreen != null && mc.currentScreen == cachedShop) {
                // Hide the shop CLIENT-SIDE (server still has it open!)
                mc.displayGuiScreen(null);
            }
        }
        wasEPressed = ePressed;
        
        // STEP 3: ESC key - same as E, hide shop
        boolean escPressed = Keyboard.isKeyDown(Keyboard.KEY_ESCAPE);
        if (escPressed && !wasEscPressed) {
            if (mc.currentScreen != null && mc.currentScreen == cachedShop) {
                // Hide the shop CLIENT-SIDE
                mc.displayGuiScreen(null);
            }
        }
        wasEscPressed = escPressed;
        
        // STEP 4: HOME key - reopen cached shop
        boolean homePressed = Keyboard.isKeyDown(Keyboard.KEY_HOME);
        if (homePressed && !wasHomePressed) {
            if (cachedShop != null) {
                // Reopen the cached shop (server never closed it!)
                mc.displayGuiScreen(cachedShop);
            }
        }
        wasHomePressed = homePressed;
    }
    
    /**
     * Check if a chest GUI title indicates it's a shop (not a normal chest)
     */
    private boolean isShopGUI(String title) {
        // Common shop identifiers
        return title.contains("shop") || 
               title.contains("store") || 
               title.contains("buy") || 
               title.contains("upgrade") ||
               title.contains("quick") ||  // Quick Buy in Bedwars
               title.contains("item");      // Item Shop
    }
    
    @Override
    public String[] getSuffix() {
        if (cachedShop != null) {
            boolean showing = (mc.currentScreen == cachedShop);
            return new String[]{showing ? "Shown" : "Cached"};
        }
        return new String[]{"No Shop"};
    }
}
