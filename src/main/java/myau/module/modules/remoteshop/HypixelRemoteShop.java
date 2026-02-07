package myau.module.modules.remoteshop;

import myau.module.modules.RemoteShop;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.inventory.IInventory;
import org.jetbrains.annotations.NotNull;

/**
 * Hypixel RemoteShop mode
 * Enhanced for Hypixel with shop detection and validation
 */
public class HypixelRemoteShop extends IRemoteShop {
    
    public HypixelRemoteShop(String name, @NotNull RemoteShop parent) {
        super(name, parent);
    }
    
    @Override
    public void openContainer() {
        if (mc.currentScreen instanceof GuiChest) {
            GuiChest chest = (GuiChest) mc.currentScreen;
            
            // Validate it's a shop GUI (has "Shop" in title or specific slots)
            if (isHypixelShop(chest)) {
                cachedShop = chest;
            }
        } else {
            cachedShop = null;
        }
    }
    
    /**
     * Check if the chest is a Hypixel shop
     */
    private boolean isHypixelShop(GuiChest chest) {
        try {
            IInventory inventory = chest.inventorySlots.getSlot(0).inventory;
            String title = inventory.getDisplayName().getUnformattedText().toLowerCase();
            
            // Check for common Hypixel shop titles
            return title.contains("shop") || 
                   title.contains("upgrades") || 
                   title.contains("quick buy") ||
                   title.contains("item shop");
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    public void remoteShop() {
        if (cachedShop == null) {
            return;
        }
        
        mc.displayGuiScreen(cachedShop);
    }
    
    @Override
    public String getPrettyName() {
        return "Hypixel";
    }
}
