package myau.module;

import myau.Myau;
import myau.enums.ModuleCategory;
import myau.event.EventManager;
import myau.module.modules.HUD;
import myau.util.KeyBindUtil;

public abstract class Module {
    protected final String name;
    protected final boolean defaultEnabled;
    protected final int defaultKey;
    protected final boolean defaultHidden;
    protected boolean enabled;
    protected int key;
    protected boolean hidden;

    public Module(String name, boolean enabled) {
        this(name, enabled, false);
    }

    public Module(String name, boolean enabled, boolean hidden) {
        this.name = name;
        this.enabled = this.defaultEnabled = enabled;
        this.key = this.defaultKey = 0;
        this.hidden = this.defaultHidden = hidden;
    }

    public String getName() {
        return this.name;
    }

    public String formatModule() {
        return String.format(
                "%s%s &r(%s&r)",
                this.key == 0 ? "" : String.format("&l[%s] &r", KeyBindUtil.getKeyName(this.key)),
                this.name,
                this.enabled ? "&a&lON" : "&c&lOFF"
        );
    }

    public String[] getSuffix() {
        return new String[0];
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled != enabled) {
            this.enabled = enabled;
            if (enabled) {
                EventManager.register(this);
                this.onEnabled();
            } else {
                this.onDisabled();
                EventManager.unregister(this);
            }
        }
    }

    public boolean toggle() {
        boolean enabled = !this.enabled;
        this.setEnabled(enabled);
        if (this.enabled == enabled) {
            if (((HUD) Myau.moduleManager.modules.get(HUD.class)).toggleSound.getValue()) {
                Myau.moduleManager.playSound();
            }
            return true;
        } else {
            return false;
        }
    }

    public int getKey() {
        return this.key;
    }

    public void setKey(int integer) {
        this.key = integer;
    }

    public boolean isHidden() {
        return this.hidden;
    }

    public void setHidden(boolean boolean1) {
        this.hidden = boolean1;
    }

    public void onEnabled() {
    }

    public void onDisabled() {
    }

    public void verifyValue(String string) {
    }

    /**
     * Gets the category of this module from its @ModuleInfo annotation
     * Defaults to MISC if no annotation is present
     */
    public ModuleCategory getCategory() {
        ModuleInfo info = this.getClass().getAnnotation(ModuleInfo.class);
        return info != null ? info.category() : ModuleCategory.MISC;
    }

    /**
     * Gets the priority of this module from its @ModuleInfo annotation
     * Lower priority = higher in the list
     */
    public int getPriority() {
        ModuleInfo info = this.getClass().getAnnotation(ModuleInfo.class);
        return info != null ? info.priority() : 1000;
    }
}
