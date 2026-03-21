package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.AttackEvent;
import myau.events.MoveInputEvent;
import myau.events.TickEvent;
import myau.mixin.IAccessorKeyBinding;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.ModeProperty;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import org.lwjgl.input.Keyboard;

@ModuleInfo(category = ModuleCategory.COMBAT)
public class MoreKB extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    // 0 = Legit      — release W key on hit, restore after delay
    // 1 = LegitSneak — force sneak=true during noSprint window via MoveInputEvent
    // 2 = LegitFast  — setSprinting(false) every tick during noSprint window
    // 3 = Fast       — set moveForward to 0.7999 during noSprint window via MoveInputEvent
    // 4 = Packet     — setSprinting(false) every tick (PreMotion equivalent)
    // 5 = LegitBlock — hold right click during noSprint window
    // 6 = LegitInv   — open/close inventory on hit
    // 7 = STap       — press S + release W on hit, restore after delay
    public final ModeProperty mode = new ModeProperty("Mode", 2,
            new String[]{"Legit", "LegitSneak", "LegitFast", "Fast", "Packet", "LegitBlock", "LegitInv", "STap"});

    public final FloatProperty minRePressDelay = new FloatProperty("Min Re-press delay", 2f, 0f, 10f);
    public final FloatProperty maxRePressDelay = new FloatProperty("Max Re-press delay", 4f, 0f, 10f);
    public final FloatProperty minDelayBetween = new FloatProperty("Min delay between",  10f, 0f, 13f);
    public final FloatProperty maxDelayBetween = new FloatProperty("Max delay between",  10f, 0f, 13f);
    public final FloatProperty chance          = new FloatProperty("Chance", 100f, 0f, 100f);
    public final BooleanProperty playersOnly   = new BooleanProperty("Players only", true);
    public final BooleanProperty notWhileRunner = new BooleanProperty("Not while runner", false);

    // ── state ────────────────────────────────────────────────────────
    private boolean canSprint     = true;
    private int delayTicksLeft    = 0;
    private int reSprintTicksLeft = -1;

    public MoreKB() {
        super("MoreKB", false);
    }

    // ── lifecycle ────────────────────────────────────────────────────

    @Override
    public void onEnabled() {
        canSprint = true;
        delayTicksLeft = 0;
        reSprintTicksLeft = -1;
    }

    @Override
    public void onDisabled() {
        if (!canSprint) {
            applyReSprintSideEffects();
        }
        canSprint = true;
        delayTicksLeft = 0;
        reSprintTicksLeft = -1;
    }

    // ── sprint control ───────────────────────────────────────────────

    private boolean noSprint() {
        return !canSprint;
    }

    private void stopSprint() {
        canSprint = false;
        int m = mode.getValue();
        switch (m) {
            case 7: // STap — press S, fall through to release W
                ((IAccessorKeyBinding) mc.gameSettings.keyBindBack).setPressed(true);
                // fall through
            case 0: // Legit — release W
                ((IAccessorKeyBinding) mc.gameSettings.keyBindForward).setPressed(false);
                break;
            case 5: // LegitBlock — press right click
                ((IAccessorKeyBinding) mc.gameSettings.keyBindUseItem).setPressed(true);
                KeyBinding.onTick(mc.gameSettings.keyBindUseItem.getKeyCode());
                break;
            case 6: // LegitInv — tap inventory key open then close
                ((IAccessorKeyBinding) mc.gameSettings.keyBindInventory).setPressed(true);
                KeyBinding.onTick(mc.gameSettings.keyBindInventory.getKeyCode());
                ((IAccessorKeyBinding) mc.gameSettings.keyBindInventory).setPressed(false);
                KeyBinding.onTick(mc.gameSettings.keyBindInventory.getKeyCode());
                break;
            // modes 1,2,3,4 handled per-tick in onTick / onMoveInput
        }
    }

    private void reSprint() {
        canSprint = true;
        applyReSprintSideEffects();
    }

    private void applyReSprintSideEffects() {
        int m = mode.getValue();
        switch (m) {
            case 7: // STap — restore S to real state, fall through to restore W
                ((IAccessorKeyBinding) mc.gameSettings.keyBindBack).setPressed(
                        Keyboard.isKeyDown(mc.gameSettings.keyBindBack.getKeyCode()));
                // fall through
            case 0: // Legit — restore W to real state
                ((IAccessorKeyBinding) mc.gameSettings.keyBindForward).setPressed(
                        Keyboard.isKeyDown(mc.gameSettings.keyBindForward.getKeyCode()));
                break;
            case 5: // LegitBlock — release right click
                ((IAccessorKeyBinding) mc.gameSettings.keyBindUseItem).setPressed(
                        Keyboard.isKeyDown(mc.gameSettings.keyBindUseItem.getKeyCode()));
                break;
            case 6: // LegitInv — close inventory if it got opened
                if (mc.currentScreen instanceof GuiInventory) {
                    mc.thePlayer.closeScreen();
                }
                break;
        }
    }

    // ── attack handler (SimpleSprintReset.onAttack) ──────────────────

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (mc.thePlayer == null) return;
        if (delayTicksLeft > 0) return;
        if (!(event.getTarget() instanceof EntityLivingBase)) return;

        EntityLivingBase target = (EntityLivingBase) event.getTarget();

        if (playersOnly.getValue() && !(target instanceof EntityPlayer)) return;
        if (notWhileRunner.getValue() && !inFov(180f, target, mc.thePlayer)) return;
        if (target.deathTime != 0) return;
        if (target instanceof EntityPlayer && TeamUtil.isBot((EntityPlayer) target)) return;

        // chance: property is 0-100, Math.random() is 0.0-1.0
        if (Math.random() * 100.0 > chance.getValue()) return;

        stopSprint();

        int minRePress = Math.min(Math.round(minRePressDelay.getValue()), Math.round(maxRePressDelay.getValue()));
        int maxRePress = Math.max(Math.round(minRePressDelay.getValue()), Math.round(maxRePressDelay.getValue()));
        int minBetween = Math.min(Math.round(minDelayBetween.getValue()),  Math.round(maxDelayBetween.getValue()));
        int maxBetween = Math.max(Math.round(minDelayBetween.getValue()),  Math.round(maxDelayBetween.getValue()));

        reSprintTicksLeft = randomInt(minRePress, maxRePress);
        delayTicksLeft    = reSprintTicksLeft + randomInt(minBetween, maxBetween);
    }

    // ── tick handler (SimpleSprintReset.onUpdate) ────────────────────

    @EventTarget
    public void onTick(TickEvent event) {
        if (mc.thePlayer == null) return;
        if (event.getType() != EventType.PRE) return;

        // reSprint countdown
        if (reSprintTicksLeft == 0) {
            reSprint();
            reSprintTicksLeft = -1;
        } else if (reSprintTicksLeft > 0) {
            reSprintTicksLeft--;
        }

        // delay-between countdown
        if (delayTicksLeft > 0) {
            delayTicksLeft--;
        }

        // modes 2 (LegitFast) and 4 (Packet) — suppress sprint every tick
        if (noSprint() && isMoving()) {
            int m = mode.getValue();
            if (m == 2 || m == 4) {
                mc.thePlayer.setSprinting(false);
            }
        }
    }

    // ── MoveInputEvent — modes 1 (LegitSneak) and 3 (Fast) ──────────

    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        if (mc.thePlayer == null) return;
        if (!noSprint() || !isMoving()) return;

        int m = mode.getValue();
        switch (m) {
            case 1: // LegitSneak — force sneak flag on
                event.setSneak(true);
                break;
            case 3: // Fast — reduce forward so sprint condition fails
                event.setForward(0.7999f);
                break;
        }
    }

    // ── utilities ────────────────────────────────────────────────────

    private static int randomInt(int min, int max) {
        if (min >= max) return min;
        return min + (int) (Math.random() * (max - min + 1));
    }

    private static boolean isMoving() {
        return mc.thePlayer != null
                && (mc.thePlayer.moveForward != 0f || mc.thePlayer.moveStrafing != 0f);
    }

    private static boolean inFov(float fov, net.minecraft.entity.Entity entity, net.minecraft.entity.Entity from) {
        double dx = entity.posX - from.posX;
        double dz = entity.posZ - from.posZ;
        float yaw  = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
        float diff = Math.abs(wrapTo180(yaw - from.rotationYaw));
        return diff <= fov / 2f;
    }

    private static float wrapTo180(float v) {
        while (v >  180f) v -= 360f;
        while (v < -180f) v += 360f;
        return v;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{mode.getModeString()};
    }
}