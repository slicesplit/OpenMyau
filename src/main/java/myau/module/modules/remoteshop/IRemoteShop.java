package myau.module.modules.remoteshop;

import myau.module.modules.RemoteShop;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiChest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Base class for RemoteShop modes
 * Handles caching and reopening of shop GUIs
 */
public abstract class IRemoteShop {
    protected static final Minecraft mc = Minecraft.getMinecraft();
    protected final String name;
    protected final RemoteShop parent;
    protected @Nullable GuiChest cachedShop = null;
    
    public IRemoteShop(String name, @NotNull RemoteShop parent) {
        this.name = name;
        this.parent = parent;
    }
    
    /**
     * Called when a container is opened - cache it if it's a shop
     */
    public void openContainer() {
        if (mc.currentScreen instanceof GuiChest) {
            cachedShop = (GuiChest) mc.currentScreen;
        } else {
            cachedShop = null;
        }
    }
    
    /**
     * Called when activation key is pressed - open the cached shop
     */
    public void remoteShop() {
        if (cachedShop == null) {
            return;
        }
        
        mc.displayGuiScreen(cachedShop);
    }
    
    /**
     * Force close and clear cache
     */
    public void forceClose() {
        cachedShop = null;
    }
    
    /**
     * Called when mode is enabled
     */
    public void onEnable() {
        // Override if needed
    }
    
    /**
     * Called when mode is disabled
     */
    public void onDisable() {
        forceClose();
    }
    
    public String getName() {
        return name;
    }
    
    public String getPrettyName() {
        return name;
    }
}
