package myau.module.modules;

import myau.event.EventTarget;
import myau.events.Render2DEvent;
import myau.mixin.IAccessorGuiChat;
import myau.module.Module;
import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;
import myau.property.properties.IntProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiTextField;

/**
 * ChatLimitRemove - Removes the vanilla chat character length limit (default 256).
 * Hooks into GuiChat's inputField every frame and raises setMaxStringLength so
 * you can type (and send) messages longer than the vanilla 256-char cap.
 *
 * Note: the server still enforces its own limit on C01PacketChatMessage (usually
 * 256 bytes for vanilla, but many server softwares raise or remove this limit).
 * This module only removes the CLIENT-SIDE restriction in the text field.
 */
@ModuleInfo(category = ModuleCategory.MISC)
public class ChatLimitRemove extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    public final IntProperty limit = new IntProperty("Limit", 32767, 256, 32767);

    public ChatLimitRemove() {
        super("ChatLimitRemove", false);
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!isEnabled()) return;
        if (!(mc.currentScreen instanceof GuiChat)) return;

        GuiTextField inputField = ((IAccessorGuiChat) mc.currentScreen).getInputField();
        if (inputField == null) return;

        // Raise the max string length every frame so it persists even if
        // Minecraft resets it (e.g. on screen re-init).
        int target = limit.getValue();
        if (inputField.getMaxStringLength() < target) {
            inputField.setMaxStringLength(target);
        }
    }
}
