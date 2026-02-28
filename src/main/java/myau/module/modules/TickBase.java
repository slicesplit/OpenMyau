package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.util.Timer;

import java.lang.reflect.Field;

/**
 * TickBase - GRIM OPTIMIZED v2
 * 
 * NEW STRATEGY: Timer-speed manipulation (Grim-safe)
 * Instead of calling onUpdate() which generates extra C03 packets,
 * we manipulate mc.timer.timerSpeed to make the game naturally tick faster.
 * 
 * Why this works against Grim:
 * - Grim's Timer check allows a small balance buffer (~0.3s)
 * - By running timerSpeed at 1.08-1.15 for 4-8 ticks, we send C03s at a rate
 *   that Grim expects from a slightly faster client
 * - The packets arrive naturally spaced (50ms apart at normal speed, ~46ms faster)
 *   which is within Grim's prediction tolerance
 * - No extra packet generation, no subsystem re-entry, no crashes
 * 
 * Critical constraints:
 * - Never exceed timerSpeed 1.15 (~3 extra ticks/sec = 0.15s over 1 second)
 * - Always use a cooldown (15-20 ticks) so balance recovers
 * - Reset timerSpeed on teleport (S08PacketPlayerPosLook)
 * - Only activate when KillAura has a target in range
 */
@ModuleInfo(category = ModuleCategory.COMBAT)
public class TickBase extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    // ==================== PROPERTIES ====================
    
    public final FloatProperty speed = new FloatProperty("Speed", 1.08f, 1.05f, 1.15f);
    public final IntProperty duration = new IntProperty("Duration", 4, 2, 8);
    public final IntProperty cooldown = new IntProperty("Cooldown", 20, 10, 40);
    public final FloatProperty minRange = new FloatProperty("Min Range", 2.5f, 2.0f, 5.0f);
    public final BooleanProperty requiresKillAura = new BooleanProperty("Requires KillAura", true);

    // ==================== STATE ====================
    
    private int activeTicks = 0;
    private int cooldownTicks = 0;
    private boolean timerSpeedActive = false;
    
    // Reflected fields for timer access (cached on first use)
    private static Field timerField = null;
    private static Field speedField = null;
    private static boolean reflectionFailed = false;

    public TickBase() {
        super("TickBase", false);
    }

    @Override
    public void onDisabled() {
        // CRITICAL: Always reset timerSpeed on disable
        resetTimerSpeed();
        activeTicks = 0;
        cooldownTicks = 0;
        timerSpeedActive = false;
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled()) return;

        // Reset timerSpeed on teleport (Grim flag detection)
        if (event.getType() == EventType.RECEIVE && event.getPacket() instanceof S08PacketPlayerPosLook) {
            resetTimerSpeed();
            activeTicks = 0;
            cooldownTicks = 0;
            timerSpeedActive = false;
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;

        if (event.getType() != EventType.PRE) return;

        // Don't conflict with blink or FakeLag
        Blink blink = (Blink) Myau.moduleManager.getModule(Blink.class);
        FakeLag fakeLag = (FakeLag) Myau.moduleManager.getModule(FakeLag.class);
        if (mc.thePlayer.isRiding() || (blink != null && blink.isEnabled()) || (fakeLag != null && fakeLag.isEnabled())) {
            resetTimerSpeed();
            return;
        }

        // Handle cooldown
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }

        // Handle active duration
        if (activeTicks > 0) {
            activeTicks--;
            if (activeTicks == 0) {
                // Duration expired, reset timerSpeed and start cooldown
                resetTimerSpeed();
                cooldownTicks = cooldown.getValue();
            }
            return;
        }

        // Try to activate TickBase if conditions are met
        if (shouldActivate()) {
            activateTick();
        }
    }

    /**
     * Check if TickBase should activate this tick
     */
    private boolean shouldActivate() {
        // Must be on ground
        if (!mc.thePlayer.onGround) {
            return false;
        }

        // Check KillAura requirement
        if (requiresKillAura.getValue()) {
            KillAura killAura = (KillAura) Myau.moduleManager.getModule(KillAura.class);
            if (killAura == null || !killAura.isEnabled()) {
                return false;
            }

            // Check if KillAura has a target in range
            EntityLivingBase target = killAura.getTarget();
            if (target == null || target.isDead || target.deathTime > 0) {
                return false;
            }

            // Check range
            double distSq = mc.thePlayer.getDistanceSqToEntity(target);
            double minRangeSq = minRange.getValue() * minRange.getValue();
            if (distSq > minRangeSq) {
                return false;
            }

            // Skip teammates and friends (only for EntityPlayer targets)
            if (target instanceof EntityPlayer) {
                EntityPlayer player = (EntityPlayer) target;
                if (TeamUtil.isSameTeam(player) || TeamUtil.isFriend(player)) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Activate TickBase by speeding up the game timer
     */
    private void activateTick() {
        if (!setTimerSpeed(speed.getValue())) {
            return; // Reflection failed, can't proceed
        }
        timerSpeedActive = true;
        activeTicks = duration.getValue();
    }

    /**
     * Set the timer speed via reflection
     * @return true if successful, false if reflection failed
     */
    private static boolean setTimerSpeed(float speedValue) {
        if (reflectionFailed) {
            return false;
        }

        try {
            // Initialize reflected fields if not already done
            if (timerField == null || speedField == null) {
                initializeReflection();
            }

            if (timerField == null || speedField == null) {
                reflectionFailed = true;
                return false;
            }

            // Get the timer object from Minecraft
            Object timer = timerField.get(mc);
            if (timer == null) {
                return false;
            }

            // Set the timerSpeed field
            speedField.setFloat(timer, speedValue);
            return true;
        } catch (Exception e) {
            reflectionFailed = true;
            return false;
        }
    }

    /**
     * Reset the timer speed to normal (1.0)
     */
    private static void resetTimerSpeed() {
        setTimerSpeed(1.0f);
    }

    /**
     * Initialize reflection for Timer access
     */
    private static void initializeReflection() {
        try {
            // Get the timer field from Minecraft (SRG name: field_71428_T)
            timerField = Minecraft.class.getDeclaredField("field_71428_T");
            timerField.setAccessible(true);

            // Get the Timer class and the timerSpeed field (SRG name: field_74277_a)
            Object timerObj = timerField.get(mc);
            if (timerObj != null) {
                speedField = timerObj.getClass().getDeclaredField("field_74277_a");
                speedField.setAccessible(true);
            }
        } catch (Exception e) {
            // Reflection failed, mark it and disable this feature
            timerField = null;
            speedField = null;
            reflectionFailed = true;
        }
    }
}
