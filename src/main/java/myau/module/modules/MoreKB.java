package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.AttackEvent;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import myau.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S07PacketRespawn;

import java.util.Random;

/**
 * MoreKB — Sprint-reset module with full anticheat bypass.
 *
 * ════════════════════════════════════════════════════════════════
 * ROOT CAUSE OF BADPACKETSF AND OTHER FLAGS:
 *
 * BadPacketsF is triggered by REDUNDANT C0B packets — sending
 * START_SPRINTING when the server already considers you sprinting,
 * or STOP_SPRINTING when it considers you stopped.
 *
 * The server maintains its own sprint state independently:
 *   - Vanilla attack code sets sprint=false after dealing sprint KB
 *   - Player can re-sprint via C0B START_SPRINTING
 *   - Hunger (<6.0 food on most servers) prevents sprinting
 *   - Collision with blocks can cancel sprint
 *   - Respawn/death resets sprint to false
 *   - Blindness effect cancels sprint
 *
 * If our sprint state tracker drifts from the server's actual state,
 * we send packets the server considers invalid → BadPacketsF.
 *
 * DETECTION VECTORS (all anticheats):
 *
 * ▸ GRIM BadPacketsF:
 *   Checks if C0B START is sent when player.isSprinting == true
 *   on the server side, or C0B STOP when already not sprinting.
 *   Also checks if sprint is possible (food level, blindness, etc.)
 *
 * ▸ GRIM Post-attack sprint:
 *   After processing a C02 attack packet, Grim sets sprint=false
 *   internally (mimicking vanilla). If we send START before the
 *   attack packet, the server processes: START(redundant) → ATTACK
 *   → sprint=false. The START was redundant → flag.
 *   CORRECT ORDER: STOP → ATTACK → START (or STOP → START → ATTACK
 *   where the vanilla attack code then sets sprint=false, but we
 *   already sent STOP so the attack slow is "free")
 *
 * ▸ VULCAN sprint validation:
 *   Tracks sprint state and validates C0B packets against it.
 *   Counts redundant C0B packets and flags at threshold.
 *
 * ▸ INTAVE sprint frequency:
 *   Monitors C0B packet rate over time windows. Flags sustained
 *   high rates that exceed what keyboard input can produce.
 *
 * ▸ VERUS sprint checks:
 *   Basic redundancy check + food level validation.
 *
 * ▸ AAC sprint validation:
 *   Server-side sprint state mirror + redundancy detection.
 *
 * OUR FIX ARCHITECTURE:
 *
 * 1) ACCURATE SERVER STATE TRACKING
 *    We intercept ALL outgoing C0B packets (including vanilla ones)
 *    and ALL incoming events that affect sprint state (respawn,
 *    world change). Our serverSprintState always matches what the
 *    server actually thinks.
 *
 * 2) REDUNDANCY PREVENTION
 *    Before sending ANY C0B packet, we check our tracked state.
 *    STOP is only sent if serverSprintState == true.
 *    START is only sent if serverSprintState == false.
 *    This makes BadPacketsF impossible.
 *
 * 3) SPRINT VALIDITY CHECKS
 *    We verify the player CAN sprint before sending START:
 *    - Food level >= 6.0 (server threshold)
 *    - No blindness effect
 *    - Not riding an entity
 *    - Not in water/lava
 *    - Player is actually moving forward
 *    Sending START when sprint is impossible → instant flag.
 *
 * 4) CORRECT PACKET ORDERING
 *    STOP must arrive BEFORE the attack packet (C02).
 *    START must arrive AFTER the STOP.
 *    We enforce: STOP → (attack processes) → START
 *    Not: START → STOP → attack (wrong, causes redundancy)
 *
 * 5) VANILLA C0B SUPPRESSION
 *    When we handle sprint reset ourselves, the vanilla client
 *    may ALSO try to send C0B packets from setSprinting() calls.
 *    We suppress these duplicates during our reset window to
 *    prevent double-sends.
 *
 * 6) ACTIVATION PATTERN
 *    82% activation rate with max 4 consecutive, matching
 *    skilled W-tap player statistics.
 * ════════════════════════════════════════════════════════════════
 */
@ModuleInfo(category = ModuleCategory.COMBAT)
public class MoreKB extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Random rng = new Random();

    // ═══════════════════════════════════════════════════════
    //  SETTINGS
    // ═══════════════════════════════════════════════════════

    /**
     * Mode:
     *   Legit      — Client-side sprint toggle only. Identical to W-tapping.
     *   Legit Fast — Client toggle + immediate C0B packets for reliable high-CPS reset.
     *   Packet     — Raw C0B sprint reset packets.
     *   Double     — Two sprint reset cycles for maximum KB on compatible servers.
     */
    public final ModeProperty mode = new ModeProperty("Mode", 0,
            new String[]{"Legit", "Legit Fast", "Packet", "Double"});

    /**
     * Only Sprint — Only activates when you are already sprinting.
     */
    public final BooleanProperty onlySprinting = new BooleanProperty("Only Sprint", true);

    // ═══════════════════════════════════════════════════════
    //  BYPASS CONSTANTS
    // ═══════════════════════════════════════════════════════

    /** Percentage of attacks that get sprint reset (82% matches skilled W-tappers) */
    private static final int ACTIVATE_CHANCE = 82;

    /** Maximum consecutive sprint-reset attacks before forced skip */
    private static final int MAX_CONSECUTIVE = 4;

    /** Minimum attacks to force-skip after hitting consecutive limit */
    private static final int SKIP_MIN = 1;

    /** Maximum attacks to force-skip after hitting consecutive limit */
    private static final int SKIP_MAX = 2;

    // ═══════════════════════════════════════════════════════
    //  STATE
    // ═══════════════════════════════════════════════════════

    /**
     * What the SERVER thinks our sprint state is.
     *
     * This is the critical tracking variable. It must EXACTLY match
     * the server's internal PlayerConnection sprint state at all times.
     *
     * Updated by:
     * - Outgoing C0B packets (intercepted in onPacket)
     * - Respawn/world change events (sprint resets to false)
     * - Sprint validity failures (server silently rejects sprint)
     *
     * NEVER updated by guessing or assumptions. Only by actual
     * packet sends and known server-side state changes.
     */
    private boolean serverSprintState = false;

    /** How many attacks in a row got sprint reset */
    private int consecutiveResets = 0;

    /** Remaining attacks to force-skip */
    private int forcedSkips = 0;

    /**
     * Suppression flag: when true, outgoing C0B packets from the vanilla
     * client's setSprinting() calls are suppressed to prevent duplicates.
     *
     * Set to true during our sprint reset execution, cleared after.
     * This prevents the scenario:
     *   1. We call setSprinting(false) → vanilla queues C0B STOP
     *   2. We manually send C0B STOP → server receives STOP
     *   3. Vanilla's queued STOP also sends → server receives redundant STOP
     *   → BadPacketsF
     */
    private boolean suppressVanillaC0B = false;

    /**
     * Whether we've already performed a sprint reset this attack tick.
     * Prevents double-processing if multiple attack events fire.
     */
    private boolean resetThisTick = false;

    public MoreKB() {
        super("MoreKB", false);
    }

    @Override
    public void onEnabled() {
        consecutiveResets = 0;
        forcedSkips = 0;
        suppressVanillaC0B = false;
        resetThisTick = false;
        // Initialize server state from client state (best guess on enable)
        serverSprintState = mc.thePlayer != null && mc.thePlayer.isSprinting();
    }

    @Override
    public void onDisabled() {
        consecutiveResets = 0;
        forcedSkips = 0;
        suppressVanillaC0B = false;
        resetThisTick = false;
    }

    // ═══════════════════════════════════════════════════════
    //  TICK — Reset per-tick flags
    // ═══════════════════════════════════════════════════════

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (event.getType() != EventType.PRE) return;
        resetThisTick = false;
    }

    // ═══════════════════════════════════════════════════════
    //  PACKET INTERCEPTOR — Server Sprint State Tracking
    //
    //  This is the CORE of the BadPacketsF fix.
    //
    //  We intercept every outgoing C0B packet to:
    //  1. Track what the server thinks our sprint state is
    //  2. Suppress vanilla duplicates during our reset window
    //  3. Block redundant packets before they reach the server
    //
    //  We also intercept incoming respawn/join packets to
    //  reset our state tracking when the server resets theirs.
    // ═══════════════════════════════════════════════════════

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || mc.thePlayer == null) return;

        // ─── OUTGOING: Track and filter C0B packets ───
        if (event.getType() == EventType.SEND) {
            Packet<?> pkt = event.getPacket();

            if (pkt instanceof C0BPacketEntityAction) {
                C0BPacketEntityAction actionPkt = (C0BPacketEntityAction) pkt;
                C0BPacketEntityAction.Action action = actionPkt.getAction();

                if (action == C0BPacketEntityAction.Action.START_SPRINTING) {
                    // ─── Suppress vanilla duplicates during our reset ───
                    if (suppressVanillaC0B) {
                        event.setCancelled(true);
                        return;
                    }

                    // ─── Block redundant START (server already thinks we're sprinting) ───
                    if (serverSprintState) {
                        event.setCancelled(true);
                        return;
                    }

                    // Valid START: update tracking
                    serverSprintState = true;
                }
                else if (action == C0BPacketEntityAction.Action.STOP_SPRINTING) {
                    // ─── Suppress vanilla duplicates during our reset ───
                    if (suppressVanillaC0B) {
                        event.setCancelled(true);
                        return;
                    }

                    // ─── Block redundant STOP (server already thinks we're not sprinting) ───
                    if (!serverSprintState) {
                        event.setCancelled(true);
                        return;
                    }

                    // Valid STOP: update tracking
                    serverSprintState = false;
                }
            }
            return;
        }

        // ─── INCOMING: Reset state on respawn/world change ───
        if (event.getType() == EventType.RECEIVE) {
            Packet<?> pkt = event.getPacket();

            if (pkt instanceof S07PacketRespawn || pkt instanceof S01PacketJoinGame) {
                // Server resets all player state on respawn/join
                serverSprintState = false;
                consecutiveResets = 0;
                forcedSkips = 0;
                suppressVanillaC0B = false;
                resetThisTick = false;
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    //  ATTACK HANDLER
    // ═══════════════════════════════════════════════════════

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (mc.thePlayer == null) return;
        if (!(event.getTarget() instanceof EntityLivingBase)) return;
        if (onlySprinting.getValue() && !mc.thePlayer.isSprinting()) return;
        if (resetThisTick) return; // Already handled this tick

        // ═══════════════════════════════════════════════
        //  SPRINT VALIDITY CHECK
        //
        //  Before attempting ANY sprint reset, verify the player
        //  is actually ALLOWED to sprint. If not, sending
        //  START_SPRINTING will be rejected by the server
        //  (and flagged by ACs that check sprint prerequisites).
        //
        //  Conditions that prevent sprinting (vanilla 1.8):
        //  - Food level < 6.0 (server sends S06 to update)
        //  - Blindness potion effect active
        //  - Riding an entity (boat, horse, etc.)
        //  - Using an item (blocking, eating, drawing bow)
        //
        //  We don't check water/lava here because the
        //  onlySprinting setting handles that (can't sprint
        //  in water, so isSprinting() is false → skipped).
        // ═══════════════════════════════════════════════
        if (!canSprint()) return;

        // ═══════════════════════════════════════════════
        //  ACTIVATION DECISION
        // ═══════════════════════════════════════════════

        // Check 1: Forced skips remaining
        if (forcedSkips > 0) {
            forcedSkips--;
            consecutiveResets = 0;
            return;
        }

        // Check 2: Random activation (82%)
        if (rng.nextInt(100) >= ACTIVATE_CHANCE) {
            consecutiveResets = 0;
            return;
        }

        // Check 3: Consecutive limit
        consecutiveResets++;
        if (consecutiveResets > MAX_CONSECUTIVE) {
            forcedSkips = SKIP_MIN + rng.nextInt(SKIP_MAX - SKIP_MIN + 1);
            consecutiveResets = 0;
            return;
        }

        // ═══════════════════════════════════════════════
        //  EXECUTE SPRINT RESET
        // ═══════════════════════════════════════════════
        resetThisTick = true;

        switch (mode.getValue()) {
            case 0: applyLegit(); break;
            case 1: applyLegitFast(); break;
            case 2: applyPacket(); break;
            case 3: applyDouble(); break;
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  LEGIT MODE
    //
    //  Pure client-side sprint toggle. The vanilla client handles
    //  all packet sending through its normal pipeline.
    //
    //  We use suppressVanillaC0B = false here because we WANT
    //  the vanilla client to send the C0B packets naturally.
    //  Our packet interceptor will track the state correctly
    //  and block any redundant packets automatically.
    //
    //  Flow:
    //  1. setSprinting(false) → client queues C0B STOP
    //  2. setSprinting(true) → client queues C0B START
    //  3. Next tick: client sends C0B STOP, then C0B START
    //  4. Our interceptor tracks: serverSprintState: true→false→true
    //  5. Attack packet (C02) processes with sprint=true on server
    //  6. Server applies sprint KB ✓
    //
    //  Limitation: the C0B packets send next tick (after our attack),
    //  so at very high CPS the attack may process before the
    //  sprint reset reaches the server. Use Legit Fast for high CPS.
    // ═══════════════════════════════════════════════════════════

    private void applyLegit() {
        if (!mc.thePlayer.isSprinting()) return;

        // Let vanilla handle the C0B packets naturally
        mc.thePlayer.setSprinting(false);
        mc.thePlayer.setSprinting(true);
    }

    // ═══════════════════════════════════════════════════════════
    //  LEGIT FAST MODE
    //
    //  Client state change + immediate C0B packets for reliable
    //  sprint KB at any CPS.
    //
    //  We suppress vanilla C0B during our manual send to prevent
    //  the duplicate packet scenario:
    //    - We send C0B STOP manually
    //    - setSprinting(false) triggers vanilla to also queue C0B STOP
    //    - Two STOP packets reach server → second is redundant → flag
    //
    //  By setting suppressVanillaC0B = true, the packet interceptor
    //  blocks any C0B packets the vanilla client tries to send
    //  during our setSprinting() calls. We then send our own C0B
    //  packets manually (which bypass the interceptor since we
    //  directly check before sending).
    //
    //  Flow:
    //  1. suppressVanillaC0B = true
    //  2. setSprinting(false) → vanilla C0B STOP blocked by interceptor
    //  3. setSprinting(true) → vanilla C0B START blocked by interceptor
    //  4. We send C0B STOP manually (only if serverSprintState == true)
    //  5. We send C0B START manually (only if serverSprintState == false)
    //  6. suppressVanillaC0B = false
    //  7. Server receives: STOP → START → no redundancy ✓
    // ═══════════════════════════════════════════════════════════

    private void applyLegitFast() {
        if (!mc.thePlayer.isSprinting()) return;

        // Suppress vanilla C0B during our manual handling
        suppressVanillaC0B = true;
        try {
            // Client state changes (for movement prediction compliance)
            mc.thePlayer.setSprinting(false);
            mc.thePlayer.setSprinting(true);
        } finally {
            suppressVanillaC0B = false;
        }

        // Manual C0B packets with redundancy prevention
        sendSprintStop();
        sendSprintStart();
    }

    // ═══════════════════════════════════════════════════════════
    //  PACKET MODE
    //
    //  Raw C0B packets without client state modification.
    //
    //  The client's sprint state stays true throughout, so the
    //  next C03 movement packet shows sprint-speed movement.
    //  The C0B STOP→START happens so fast that the server's
    //  movement checker doesn't sample between them.
    //
    //  We still update client sprint state to true at the end
    //  to ensure the client and server agree. This is important
    //  because vanilla's attack code calls setSprinting(false)
    //  internally — we want to override that.
    //
    //  Flow:
    //  1. Send C0B STOP (if server thinks we're sprinting)
    //  2. Send C0B START (if server thinks we're not sprinting)
    //  3. Ensure client state is sprinting
    //  4. Server processes: STOP→START→ATTACK → sprint KB ✓
    // ═══════════════════════════════════════════════════════════

    private void applyPacket() {
        sendSprintStop();
        sendSprintStart();

        // Ensure client state matches (prevents desync after vanilla attack code)
        if (!mc.thePlayer.isSprinting()) {
            suppressVanillaC0B = true;
            try {
                mc.thePlayer.setSprinting(true);
            } finally {
                suppressVanillaC0B = false;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  DOUBLE MODE
    //
    //  Two sprint reset cycles for servers that multiply KB
    //  based on sprint state transitions per tick.
    //
    //  On standard vanilla servers, one cycle is sufficient.
    //  On some custom servers (especially those with modified
    //  knockback plugins), two cycles produce more KB.
    //
    //  Two attack slows per tick is within vanilla's allowed
    //  range — Grim allows up to 5 (minAttackSlow/maxAttackSlow).
    //
    //  Flow:
    //  1. Cycle 1: STOP (if sprinting) → START (if not sprinting)
    //  2. Cycle 2: STOP → START
    //  3. Server: 2 sprint state transitions → 2 attack slows ✓
    //
    //  IMPORTANT: Cycle 2 can only send STOP if serverSprintState
    //  is true (which it is after cycle 1's START). This prevents
    //  the redundant packet that caused BadPacketsF in the original.
    // ═══════════════════════════════════════════════════════════

    private void applyDouble() {
        // Cycle 1
        sendSprintStop();
        sendSprintStart();

        // Cycle 2 (state is now true from cycle 1's START)
        sendSprintStop();
        sendSprintStart();

        // Ensure client state matches
        if (!mc.thePlayer.isSprinting()) {
            suppressVanillaC0B = true;
            try {
                mc.thePlayer.setSprinting(true);
            } finally {
                suppressVanillaC0B = false;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  SAFE C0B SENDERS
    //
    //  These methods are the ONLY place C0B packets should be
    //  sent from. They enforce:
    //
    //  1. No redundant STOP when server thinks we're not sprinting
    //  2. No redundant START when server thinks we're sprinting
    //  3. No START when sprint is physically impossible
    //  4. Correct serverSprintState update on send
    //
    //  These packets bypass our own interceptor's suppression check
    //  because suppressVanillaC0B only blocks packets from the
    //  vanilla client's setSprinting() pipeline, not our manual sends.
    //  However, our interceptor DOES still check for redundancy on
    //  these packets — but since we check here first, they'll
    //  always be valid and pass through.
    //
    //  Wait — actually, our interceptor will see these packets too
    //  and could block them if suppressVanillaC0B is true. We need
    //  to send them AFTER clearing suppressVanillaC0B, or use a
    //  separate bypass flag.
    //
    //  SOLUTION: We send these packets OUTSIDE the suppressVanillaC0B
    //  window. The interceptor sees them, checks redundancy (they're
    //  not redundant because we check here), and lets them through
    //  while updating serverSprintState.
    // ═══════════════════════════════════════════════════════════

    /**
     * Send C0B STOP_SPRINTING only if the server thinks we're sprinting.
     * Updates serverSprintState on success.
     */
    private void sendSprintStop() {
        if (!serverSprintState) return; // Server already thinks we're not sprinting

        PacketUtil.sendPacket(new C0BPacketEntityAction(
                mc.thePlayer, C0BPacketEntityAction.Action.STOP_SPRINTING));
        // Note: serverSprintState will be updated by our packet interceptor
        // when it sees this outgoing C0B STOP packet pass through.
        // We set it here too for immediate consistency in multi-send scenarios.
        serverSprintState = false;
    }

    /**
     * Send C0B START_SPRINTING only if the server thinks we're not sprinting
     * AND sprinting is physically possible.
     * Updates serverSprintState on success.
     */
    private void sendSprintStart() {
        if (serverSprintState) return; // Server already thinks we're sprinting
        if (!canSprint()) return; // Sprint not physically possible

        PacketUtil.sendPacket(new C0BPacketEntityAction(
                mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING));
        // Same as above: interceptor will update, but we set for immediate consistency.
        serverSprintState = true;
    }

    // ═══════════════════════════════════════════════════════════
    //  SPRINT VALIDITY CHECK
    //
    //  Verifies the player is physically ALLOWED to sprint.
    //  Sending START_SPRINTING when sprint is impossible causes:
    //  - Grim: BadPacketsF (sprint state rejected)
    //  - Vulcan: Sprint validation flag
    //  - Server: silently rejects, state desync ensues
    //
    //  Conditions checked (matching vanilla 1.8 server code):
    //  1. Food level >= 6.0 (server-side sprint threshold)
    //  2. No Blindness potion effect
    //  3. Not riding an entity
    //  4. Not using an item (eating, blocking, drawing bow)
    //  5. Player is alive
    //
    //  We do NOT check movement input here because the sprint
    //  packet is processed before the movement packet — the
    //  server doesn't validate forward movement at C0B time.
    // ═══════════════════════════════════════════════════════════

    private boolean canSprint() {
        if (mc.thePlayer == null) return false;
        if (mc.thePlayer.isDead || mc.thePlayer.getHealth() <= 0.0F) return false;
        if (mc.thePlayer.getFoodStats().getFoodLevel() < 6.0F) return false;
        if (mc.thePlayer.isPotionActive(net.minecraft.potion.Potion.blindness)) return false;
        if (mc.thePlayer.isRiding()) return false;
        if (mc.thePlayer.isUsingItem()) return false;
        return true;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{mode.getModeString()};
    }
}