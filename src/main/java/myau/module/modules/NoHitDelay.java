package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;

import myau.module.Module;

@ModuleInfo(category = ModuleCategory.COMBAT)
public class NoHitDelay extends Module {
    public NoHitDelay() {
        super("NoHitDelay", true, true);
    }
}
