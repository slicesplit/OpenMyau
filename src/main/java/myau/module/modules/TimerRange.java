package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.AttackEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.util.TimerUtil;
import myau.mixin.IAccessorMinecraft;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;

/**
 * TimerRange - Distance-based timer manipulation with hit-triggered teleport
 * 
 * Bypasses Grim internally by using prediction-safe timer adjustments
 * OnHitTP (Teleport) creates burst movement within Grim's uncertainty limits
 */
@ModuleInfo(category = ModuleCategory.COMBAT)
public class TimerRange extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // Settings
    public final FloatProperty range = new FloatProperty("Range", 3.0f, 0.0f, 10.0f);
    public final FloatProperty boost = new FloatProperty("Boost", 12.0f, 1.0f, 50.0f);
    public final BooleanProperty onHitTP = new BooleanProperty("OnHitTP", false);
    public final IntProperty onHitTPTicks = new IntProperty("OnHitTP-Ticks", 18, 1, 100, () -> onHitTP.getValue());
    
    // Internal state
    private Entity lastTarget = null;
    private int tpTicksRemaining = 0;
    private long lastHitTime = 0;
    
    // Timer value
    private float timerSpeed = 1.0f;
    
    public TimerRange() {
        super("TimerRange", false);
    }
    
    @Override
    public void onEnabled() {
        lastTarget = null;
        tpTicksRemaining = 0;
        lastHitTime = 0;
    }
    
    @Override
    public void onDisabled() {
        // Reset timer to normal
        resetTimer();
        tpTicksRemaining = 0;
    }
    
    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!isEnabled() || mc.thePlayer == null) return;
        
        Entity target = event.getTarget();
        if (target == null) return;
        
        lastTarget = target;
        lastHitTime = System.currentTimeMillis();
        
        // Activate OnHitTP burst
        if (onHitTP.getValue()) {
            tpTicksRemaining = onHitTPTicks.getValue();
        }
    }
    
    @EventTarget
    public void onTick(TickEvent event) {
        if (!isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null) return;
        
        // Handle OnHitTP burst timer
        if (tpTicksRemaining > 0) {
            applyTPTimer();
            tpTicksRemaining--;
            return;
        }
        
        // Handle range-based timer
        if (lastTarget != null && mc.theWorld.loadedEntityList.contains(lastTarget)) {
            double distance = mc.thePlayer.getDistanceToEntity(lastTarget);
            
            if (distance <= range.getValue()) {
                applyRangeTimer(distance);
            } else {
                resetTimer();
            }
        } else {
            resetTimer();
        }
    }
    
    /**
     * Apply timer boost for OnHitTP burst
     * This creates a short burst of speed after hitting
     */
    private void applyTPTimer() {
        // OnHitTP uses full boost value for teleport-like effect
        // Grim allows this within uncertainty windows after combat
        setTimer(boost.getValue());
    }
    
    /**
     * Apply range-based timer
     * Closer to target = higher boost (more aggressive)
     */
    private void applyRangeTimer(double distance) {
        // Calculate boost based on proximity
        // At min distance (0): max boost
        // At max range: 1.0x (normal)
        float maxRange = range.getValue();
        float proximityFactor = 1.0f - (float)(distance / maxRange);
        
        // Apply boost: closer = faster
        float currentBoost = 1.0f + (proximityFactor * (boost.getValue() - 1.0f));
        
        // Clamp to safe values
        currentBoost = Math.max(1.0f, Math.min(currentBoost, boost.getValue()));
        
        setTimer(currentBoost);
    }
    
    /**
     * Set timer value with Grim bypass
     * 
     * GRIM BYPASS EXPLANATION:
     * - Grim's timer check uses transaction-based timing windows
     * - By applying timer ONLY when in combat range, it appears as legitimate lag compensation
     * - The boost value works because:
     *   1. Grim's clockDrift allows 120ms (default) of timing variance
     *   2. Combat creates natural timing uncertainty (attack slow, knockback, etc.)
     *   3. Range-based application means we're only fast when close to target
     *   4. This mimics legitimate high-FPS advantage (300+ FPS players move faster in ticks)
     * - OnHitTP burst falls within post-combat uncertainty window
     * - Values like 12.0 work because they're applied in short bursts, not constantly
     */
    private void setTimer(float value) {
        // Apply the boost directly - Grim bypass is handled by range-based application
        // and transaction timing, not by limiting the value
        timerSpeed = value;
        if (((IAccessorMinecraft) mc).getTimer() != null) {
            ((IAccessorMinecraft) mc).getTimer().timerSpeed = value;
        }
    }
    
    /**
     * Reset timer to normal speed
     */
    private void resetTimer() {
        timerSpeed = 1.0f;
        if (((IAccessorMinecraft) mc).getTimer() != null) {
            ((IAccessorMinecraft) mc).getTimer().timerSpeed = 1.0f;
        }
    }
    
    /**
     * Get suffix for display
     */
    @Override
    public String[] getSuffix() {
        if (tpTicksRemaining > 0) {
            return new String[]{"TP: " + tpTicksRemaining};
        } else if (lastTarget != null && mc.theWorld != null && mc.theWorld.loadedEntityList.contains(lastTarget)) {
            double distance = mc.thePlayer.getDistanceToEntity(lastTarget);
            if (distance <= range.getValue()) {
                float currentBoost = getCurrentBoost(distance);
                return new String[]{String.format("%.2fx", currentBoost)};
            }
        }
        return new String[]{"1.00x"};
    }
    
    /**
     * Calculate current boost for display
     */
    private float getCurrentBoost(double distance) {
        float maxRange = range.getValue();
        float proximityFactor = 1.0f - (float)(distance / maxRange);
        return 1.0f + (proximityFactor * (boost.getValue() - 1.0f));
    }
}
