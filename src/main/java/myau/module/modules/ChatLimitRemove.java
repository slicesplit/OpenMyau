package myau.module.modules;

import myau.module.Module;
import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;

/**
 * ChatLimitRemove - Removes chat message sending limits
 * 
 * Simple: Removes the delay between chat messages so you can spam freely
 */
@ModuleInfo(category = ModuleCategory.MISC)
public class ChatLimitRemove extends Module {
    
    public ChatLimitRemove() {
        super("ChatLimitRemove", false);
    }
    
    @Override
    public void onEnabled() {
        super.onEnabled();
    }
    
    @Override
    public void onDisabled() {
        super.onDisabled();
    }
}
