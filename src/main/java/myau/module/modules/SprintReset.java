package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.*;
import myau.module.Module;
import myau.property.properties.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.client.C0BPacketEntityAction.Action;

@ModuleInfo(category = ModuleCategory.COMBAT)
public class SprintReset extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty mode = new ModeProperty("mode", 0,
            new String[]{"Packet", "S-Tap", "W-Tap", "Legit S-Tap"});

    // How many ticks to hold the direction key released (for legit modes)
    public final IntProperty releaseLength = new IntProperty("release-ticks", 1, 1, 3,
            () -> mode.getValue() >= 1);

    // Only reset when moving forward
    public final BooleanProperty onlyForward = new BooleanProperty("only-forward", true);

    // Only when target is in range
    public final BooleanProperty onlyInRange = new BooleanProperty("only-in-range", true);

    private int releaseTicks;
    private boolean wasReleased;

    public SprintReset() {
        super("SprintReset", false);
    }

    @Override
    public void onEnabled() {
        releaseTicks = 0;
        wasReleased = false;
    }

    @Override
    public void onDisabled() {
        releaseTicks = 0;
        wasReleased = false;
    }

    // ══════════════════════════════════════════════
    //  ATTACK EVENT — triggers the sprint reset
    // ══════════════════════════════════════════════

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (mc.thePlayer == null) return;
        if (!mc.thePlayer.isSprinting()) return;

        if (onlyForward.getValue() && !isMovingForward()) return;

        if (onlyInRange.getValue()) {
            if (mc.thePlayer.getDistanceToEntity(event.getTarget()) > 3.5) return;
        }

        switch (mode.getValue()) {
            case 0:
                doPacketReset();
                break;
            case 1:
                startSTap();
                break;
            case 2:
                startWTap();
                break;
            case 3:
                startLegitSTap();
                break;
        }
    }

    // ══════════════════════════════════════════════
    //  TICK — handles multi-tick resets for legit modes
    // ══════════════════════════════════════════════

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (event.getType() != EventType.PRE) return;
        if (mc.thePlayer == null) return;

        if (wasReleased) {
            releaseTicks++;
            if (releaseTicks >= releaseLength.getValue()) {
                restoreKeys();
                wasReleased = false;
                releaseTicks = 0;
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  MODE 0: PACKET RESET — The most brutal method
    //
    //  How it works:
    //  1. Send STOP_SPRINTING to server
    //  2. Server marks player as not sprinting
    //  3. Send START_SPRINTING to server
    //  4. Server marks player as sprinting again
    //
    //  This all happens on the SAME TICK as the attack.
    //  The server processes packets in order:
    //    C0B(STOP) → C02(ATTACK) → C0B(START)
    //
    //  But because sprint state is checked when C02 is
    //  processed, and we re-engage sprint BEFORE the next
    //  tick, the server sees:
    //    - Sprint stops
    //    - Attack lands (no sprint KB... wait)
    //
    //  Actually, the brutal version sends:
    //    C0B(STOP) → C0B(START) → C02(ATTACK)
    //
    //  So the server sees sprint re-engaged BEFORE the
    //  attack packet, giving full sprint knockback every
    //  single hit. The sprint was "reset" so the game
    //  treats it as a new sprint hit.
    //
    //  Why this is the most brutal:
    //  - Zero tick delay between reset and attack
    //  - 100% sprint KB rate (every hit is a sprint hit)
    //  - No movement disruption (player never stops moving)
    //  - No visual tells (no stutter, no slow down)
    //  - Works at any CPS
    //  - Impossible to replicate manually
    // ══════════════════════════════════════════════════════

    private void doPacketReset() {
        if (mc.getNetHandler() == null) return;

        // Stop sprint
        mc.getNetHandler().addToSendQueue(
                new C0BPacketEntityAction(mc.thePlayer, Action.STOP_SPRINTING));

        // Immediately re-engage sprint
        mc.getNetHandler().addToSendQueue(
                new C0BPacketEntityAction(mc.thePlayer, Action.START_SPRINTING));

        // Ensure client state matches
        mc.thePlayer.setSprinting(true);
    }

    // ══════════════════════════════════════════════
    //  MODE 1: S-TAP
    //  Simulates pressing S for 1-3 ticks
    //  Forces sprint off because you can't sprint backward
    // ══════════════════════════════════════════════

    private void startSTap() {
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindBack.getKeyCode(), true);
        wasReleased = true;
        releaseTicks = 0;
    }

    // ══════════════════════════════════════════════
    //  MODE 2: W-TAP
    //  Releases W for 1-3 ticks to break sprint
    // ══════════════════════════════════════════════

    private void startWTap() {
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), false);
        wasReleased = true;
        releaseTicks = 0;
    }

    // ══════════════════════════════════════════════
    //  MODE 3: LEGIT S-TAP
    //  Same as S-Tap but with randomized timing
    //  to look more human on replays
    // ══════════════════════════════════════════════

    private void startLegitSTap() {
        // 30% chance to skip a reset — humans aren't perfect
        if (Math.random() < 0.30) return;

        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindBack.getKeyCode(), true);
        wasReleased = true;
        releaseTicks = 0;
    }

    // ══════════════════════════════════════════════
    //  KEY RESTORATION
    // ══════════════════════════════════════════════

    private void restoreKeys() {
        // Restore keys based on what the player is actually pressing
        KeyBinding.setKeyBindState(
                mc.gameSettings.keyBindForward.getKeyCode(),
                org.lwjgl.input.Keyboard.isKeyDown(mc.gameSettings.keyBindForward.getKeyCode()));
        KeyBinding.setKeyBindState(
                mc.gameSettings.keyBindBack.getKeyCode(),
                org.lwjgl.input.Keyboard.isKeyDown(mc.gameSettings.keyBindBack.getKeyCode()));
    }

    private boolean isMovingForward() {
        return mc.thePlayer.moveForward > 0;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{mode.getModeString()};
    }
}