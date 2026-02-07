package myau.module.modules;

import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;

/**
 * FovFix - Prevents FOV changes from dynamic events
 * 
 * Locks FOV to a specific value and prevents changes from:
 * - Sprinting (+15% FOV)
 * - Speed effects (+15% per level)
 * - Slowness effects (-15% per level)
 * - Bow drawing (-15% FOV)
 * - Flying
 * - Any other dynamic FOV modifiers
 * 
 * Works via mixin injection in MixinEntityRenderer
 */
public class FovFix extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // Mode: Lock to current FOV or custom value
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"Lock Current", "Custom"});
    
    // Custom FOV value
    public final FloatProperty customFov = new FloatProperty("custom-fov", 70.0F, 30.0F, 110.0F, () -> mode.getValue() == 1);
    
    // State tracking
    private float lockedFov = 70.0F;
    private float lastGameFov = 70.0F;
    
    public FovFix() {
        super("FovFix", false);
    }
    
    @Override
    public void onEnabled() {
        if (mc.gameSettings != null) {
            // Lock to current FOV setting
            lockedFov = mc.gameSettings.fovSetting;
            lastGameFov = mc.gameSettings.fovSetting;
        }
    }
    
    @Override
    public void onDisabled() {
        // Restore normal FOV behavior
        if (mc.gameSettings != null && mc.entityRenderer != null) {
            mc.entityRenderer.updateCameraAndRender(0, 0);
        }
    }
    
    /**
     * Called by mixin to get the fixed FOV value
     * This is the main method that intercepts FOV changes
     */
    public float getFixedFov(float originalFov) {
        if (mc.thePlayer == null || mc.gameSettings == null) {
            return originalFov;
        }
        
        // Update locked FOV if base FOV changed in settings
        float baseFov = mc.gameSettings.fovSetting;
        if (Math.abs(baseFov - lastGameFov) > 0.1F && mode.getValue() == 0) {
            lockedFov = baseFov;
            lastGameFov = baseFov;
        }
        
        // Get target FOV based on mode
        float targetFov = (mode.getValue() == 1) ? customFov.getValue() : lockedFov;
        
        // Return target FOV, effectively blocking all dynamic changes
        return targetFov;
    }
    
    @Override
    public String[] getSuffix() {
        if (mode.getValue() == 1) {
            return new String[]{String.format("%.0f", customFov.getValue())};
        } else {
            return new String[]{String.format("%.0f", lockedFov)};
        }
    }
}
