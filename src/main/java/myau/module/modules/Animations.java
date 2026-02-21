package myau.module.modules;

import myau.enums.ModuleCategory;
import myau.module.Module;
import myau.module.ModuleInfo;
import myau.property.properties.FloatProperty;
import myau.property.properties.ModeProperty;

@ModuleInfo(category = ModuleCategory.RENDER)
public class Animations extends Module {

    // ═══════════════════════════════════════════════════════
    //  SETTINGS
    // ═══════════════════════════════════════════════════════

    /**
     * Style - Animation mode for blocking and swinging
     *   30 different animation styles to choose from.
     *   "Vanilla" (18) disables all modifications.
     */
    public final ModeProperty style = new ModeProperty("Style", 0, new String[]{
        "1.7", "Smooth", "Exhibition", "Sigma", "Push", "Slide", "Spin",
        "Swing", "Tap", "Jello", "Down", "Avatar", "Stab", "Flux", "Swank",
        "Swong", "ETB", "Leaked", "Vanilla", "Rotate", "Reverse", "Lucky",
        "Zoom", "Move", "Punch", "Stella", "Interia", "Float", "Chill", "Epic"
    });

    /**
     * Speed - Animation speed multiplier
     *   Affects time-based animations like Spin, Jello, Float etc.
     */
    public final FloatProperty speed = new FloatProperty("Speed", 1.0f, 0.2f, 3.0f);

    public Animations() {
        super("Animations", false);
    }

    @Override
    public String[] getSuffix() {
        return new String[]{style.getModeString()};
    }
}