package myau.module.modules;

import myau.enums.ModuleCategory;
import myau.module.Module;
import myau.module.ModuleInfo;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;

@ModuleInfo(category = ModuleCategory.RENDER)
public class Animations extends Module {

    // ── Animation mode ────────────────────────────────────────────────────────
    public final ModeProperty mode = new ModeProperty(
            "Mode",
            1, // default: Pushdown (index 1)
            new String[]{"OneSeven", "Pushdown", "NewPushdown", "Old", "Helium", "Argon", "Cesium", "Sulfur"}
    );

    // ── Swing tweaks ──────────────────────────────────────────────────────────
    public final BooleanProperty oddSwing = new BooleanProperty("OddSwing", false);
    public final IntProperty swingSpeed   = new IntProperty("SwingSpeed", 15, 0, 20);

    // ── Hand transform offsets ────────────────────────────────────────────────
    public final FloatProperty itemScale = new FloatProperty("ItemScale", 0f, -5f, 5f);
    public final FloatProperty handX     = new FloatProperty("X",         0f, -5f, 5f);
    public final FloatProperty handY     = new FloatProperty("Y",         0f, -5f, 5f);
    public final FloatProperty handPosX  = new FloatProperty("PositionRotationX", 0f, -50f, 50f);
    public final FloatProperty handPosY  = new FloatProperty("PositionRotationY", 0f, -50f, 50f);
    public final FloatProperty handPosZ  = new FloatProperty("PositionRotationZ", 0f, -50f, 50f);

    // ── Item rotate ───────────────────────────────────────────────────────────
    public final BooleanProperty itemRotate = new BooleanProperty("ItemRotate", false);
    public final ModeProperty itemRotateMode = new ModeProperty(
            "ItemRotateMode",
            0, // default: None
            new String[]{"None", "Straight", "Forward", "Nano", "Uh"},
            () -> itemRotate.getValue()
    );
    public final FloatProperty rotateSpeed = new FloatProperty(
            "RotateSpeed", 8f, 1f, 15f,
            () -> itemRotate.getValue()
    );

    // ── Rotation state (mutable, shared with mixin) ───────────────────────────
    public volatile float delay = 0f;

    public Animations() {
        super("Animations", false);
    }

    /** Returns the current mode name string. */
    public String getModeName() {
        return mode.getModeString();
    }

    /** Returns the current itemRotateMode name string. */
    public String getItemRotateModeName() {
        return itemRotateMode.getModeString();
    }
}
