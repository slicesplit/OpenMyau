package myau.module.modules;

import myau.enums.ModuleCategory;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.mixin.IAccessorC0DPacketCloseWindow;
import myau.module.Module;
import myau.module.ModuleInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.inventory.Container;
import net.minecraft.network.play.client.C0DPacketCloseWindow;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S2DPacketOpenWindow;
import net.minecraft.network.play.server.S2EPacketCloseWindow;
import org.lwjgl.input.Keyboard;

@ModuleInfo(category = ModuleCategory.MISC)
public class RemoteShop extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private GuiContainer cachedGui = null;
    private Container cachedContainer = null;
    private int cachedWindowId = -1;
    private boolean wasHomePressed = false;

    public RemoteShop() {
        super("RemoteShop", false);
    }

    @Override
    public void onEnabled() {
        this.invalidate();
    }

    @Override
    public void onDisabled() {
        this.invalidate();
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() == EventType.PRE) {
            if (mc.thePlayer != null && mc.theWorld != null) {
                if (!mc.thePlayer.isDead && !(mc.thePlayer.getHealth() <= 0.0F)) {
                    // Check if current screen is a valid shop/container to cache
                    if (mc.currentScreen instanceof GuiContainer
                            && !(mc.currentScreen instanceof GuiInventory)
                            && !(mc.currentScreen instanceof GuiContainerCreative)) {
                        GuiContainer gui = (GuiContainer) mc.currentScreen;
                        int windowId = gui.inventorySlots.windowId;
                        if (windowId > 0) {
                            this.cachedGui = gui;
                            this.cachedContainer = gui.inventorySlots;
                            this.cachedWindowId = windowId;
                        }
                    }

                    // Keep container active on the player
                    if (this.cachedContainer != null
                            && mc.currentScreen != this.cachedGui
                            && mc.thePlayer.openContainer != this.cachedContainer) {
                        mc.thePlayer.openContainer = this.cachedContainer;
                    }

                    // Re-open cached shop when HOME key is pressed
                    boolean homePressed = Keyboard.isKeyDown(Keyboard.KEY_HOME);
                    if (homePressed && !this.wasHomePressed && this.cachedGui != null && mc.currentScreen == null) {
                        mc.displayGuiScreen(this.cachedGui);
                        mc.thePlayer.openContainer = this.cachedContainer;
                    }

                    this.wasHomePressed = homePressed;
                } else {
                    this.invalidate();
                }
            }
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (this.isEnabled() && mc.thePlayer != null) {
            if (event.getType() == EventType.SEND) {
                // Cancel closing packet if it matches our cached window ID
                if (event.getPacket() instanceof C0DPacketCloseWindow && this.cachedWindowId > 0) {
                    int windowId = ((IAccessorC0DPacketCloseWindow) event.getPacket()).getWindowId();
                    if (windowId == this.cachedWindowId) {
                        event.setCancelled(true);
                    }
                }
            } else {
                if (event.getType() == EventType.RECEIVE) {
                    // Invalidate on world change/respawn
                    if (event.getPacket() instanceof S07PacketRespawn || event.getPacket() instanceof S01PacketJoinGame) {
                        this.invalidate();
                        return;
                    }

                    // Invalidate if server forces open a new window
                    if (event.getPacket() instanceof S2DPacketOpenWindow) {
                        this.invalidate();
                        return;
                    }

                    // Invalidate if server forces close
                    if (event.getPacket() instanceof S2EPacketCloseWindow) {
                        this.invalidate();
                    }
                }
            }
        }
    }

    private void invalidate() {
        if (mc.currentScreen != null && mc.currentScreen == this.cachedGui) {
            mc.displayGuiScreen(null);
        }

        this.cachedGui = null;
        this.cachedContainer = null;
        this.cachedWindowId = -1;
        this.wasHomePressed = false;
    }

    @Override
    public String[] getSuffix() {
        return this.cachedGui != null
                ? new String[]{mc.currentScreen == this.cachedGui ? "Open" : "Cached"}
                : new String[]{"None"};
    }
}