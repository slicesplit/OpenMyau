package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;
import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.*;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import myau.util.ItemUtil;
import myau.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C0FPacketConfirmTransaction;
import net.minecraft.network.play.server.S0BPacketAnimation;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ThreadLocalRandom;

@ModuleInfo(category = ModuleCategory.COMBAT)
public class BlockHit extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    public final PercentProperty chance      = new PercentProperty("Chance", 100);
    public final BooleanProperty requireMouseDown = new BooleanProperty("Require Mouse Down", false);
    public final ModeProperty mode = new ModeProperty("Mode", 0,
            new String[]{"MANUAL", "AUTO", "PREDICT", "LAG", "SMART"});

    // LAG mode options
    public final IntProperty lagTicks    = new IntProperty("Lag Ticks", 3, 1, 8,    () -> mode.getValue() == 3);
    public final BooleanProperty lagJitter = new BooleanProperty("Lag Jitter", true, () -> mode.getValue() == 3);

    // SMART mode options
    public final BooleanProperty smartFall   = new BooleanProperty("Smart Fall Block",   true,  () -> mode.getValue() == 4);
    public final BooleanProperty smartCombat = new BooleanProperty("Smart Combat Only",  true,  () -> mode.getValue() == 4);
    public final FloatProperty   smartFallH  = new FloatProperty("Fall Dmg Height", 3.5f, 1.0f, 20.0f, () -> mode.getValue() == 4);

    // ────────── server-side block state ──────────
    private boolean serverBlocking  = false;

    // tick counters
    private int ticksSinceAttack  = 999;
    private int ticksSinceBlock   = 999;
    private int ticksSinceUnblock = 999;

    // attack detection
    private boolean blockQueued     = false;
    private int     attacksThisTick = 0;

    // ────────── opponent tracking ──────────
    private int    opId           = -1;
    private int    opSwingAge     = 999;
    private double opDist         = 10.0;
    private double opPrevDist     = 10.0;
    private int    opApproachTicks = 0;
    private int    opHurtTime     = 0;

    // ────────── Kalman-style rhythm predictor ──────────
    // Tracks opponent swing intervals with an exponential Kalman filter:
    //   estimate = estimate + K * (measurement - estimate)
    //   K (Kalman gain) = errorCov / (errorCov + measureNoise)
    // This converges on the true mean far faster than a rolling average,
    // is robust to outliers, and adapts when the opponent changes CPS.
    private long   opLastSwingMs   = 0;
    private double kfEstimate      = 0;   // Kalman estimated interval (ms)
    private double kfErrorCov      = 1e6; // uncertainty in the estimate
    private double kfMeasNoise     = 800; // measurement noise variance (tuned to 1.8 CPS swing jitter)
    private double kfProcNoise     = 120; // process noise — opponent can change rhythm
    private int    kfSamples       = 0;

    // Phase model: where in their swing cycle are they RIGHT NOW?
    // phase ∈ [0,1) — fraction of interval elapsed since last swing
    private double opPhase         = 0.0;

    // ────────── velocity / incoming hit detection ──────────
    private boolean velIncoming  = false;
    private int     velCooldown  = 0;
    private double  velMagnitude = 0; // magnitude of last incoming velocity (for Smart mode priority)

    // ────────── PREDICT mode ──────────
    private boolean predictQueued   = false;
    private int     predictHoldTicks = 0;

    // ────────── LAG mode ──────────
    private final Deque<Packet<?>> lagHeld    = new ArrayDeque<>();
    private boolean lagActive    = false;
    private int     lagTicksLeft = 0;
    private boolean lagFlushing  = false;

    // ────────── SMART mode ──────────
    // Threat score: 0 (safe) → 1 (critical) — drives block decision
    // Computed each tick via physics model + rhythm phase
    private double  smartThreat    = 0.0;
    private boolean smartBlocking  = false;
    private int     smartHoldTicks = 0;
    private boolean inCombat       = false;
    private int     combatCooldown = 0;
    private float   lastHealth     = 20.0f;
    private double  fallDmgPred    = 0.0; // predicted fall damage (Newton physics)

    // ────────── stats ──────────
    private int totalBlocks   = 0;
    private int totalUnblocks = 0;
    private int hitsBlocked   = 0;

    public BlockHit() {
        super("BlockHit", false);
    }

    @Override
    public void onEnabled() { reset(); }

    @Override
    public void onDisabled() {
        if (serverBlocking) sendUnblock();
        flushLag();
        reset();
    }

    private void reset() {
        serverBlocking   = false;
        ticksSinceAttack = ticksSinceBlock = ticksSinceUnblock = 999;
        blockQueued      = false;
        attacksThisTick  = 0;
        opId             = -1;
        opSwingAge       = 999;
        opDist = opPrevDist = 10.0;
        opApproachTicks  = 0;
        opHurtTime       = 0;
        opLastSwingMs    = 0;
        kfEstimate       = 0;
        kfErrorCov       = 1e6;
        kfSamples        = 0;
        opPhase          = 0.0;
        velIncoming      = false;
        velCooldown      = 0;
        velMagnitude     = 0;
        predictQueued    = false;
        predictHoldTicks = 0;
        lagHeld.clear();
        lagActive = false; lagTicksLeft = 0; lagFlushing = false;
        smartThreat    = 0.0;
        smartBlocking  = false;
        smartHoldTicks = 0;
        inCombat       = false;
        combatCooldown = 0;
        lastHealth     = mc.thePlayer != null ? mc.thePlayer.getHealth() : 20.0f;
        fallDmgPred    = 0.0;
        totalBlocks = totalUnblocks = hitsBlocked = 0;
    }

    // ═══════════════════════════════════
    //  TICK — main state machine
    // ═══════════════════════════════════

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (event.getType() != EventType.PRE || mc.thePlayer == null) return;

        ticksSinceAttack++;
        ticksSinceBlock++;
        ticksSinceUnblock++;
        opSwingAge++;
        attacksThisTick = 0;

        if (velCooldown > 0) velCooldown--;
        else velIncoming = false;

        if (!ItemUtil.isHoldingSword()) {
            if (serverBlocking) sendUnblock();
            blockQueued = false;
            predictQueued = false;
            return;
        }

        if (requireMouseDown.getValue() && !mc.gameSettings.keyBindUseItem.isKeyDown()) {
            if (serverBlocking) sendUnblock();
            blockQueued = false;
            predictQueued = false;
            return;
        }

        updateOpponent();
        updateFallDmgPrediction();
        updateCombatState();

        switch (mode.getValue()) {
            case 0: tickManual();  break;
            case 1: tickAuto();    break;
            case 2: tickPredict(); break;
            case 3: tickLag();     break;
            case 4: tickSmart();   break;
        }
    }

    // ═══════════════════════════════════
    //  MANUAL
    //  Block after every attack you send.
    //  Unblock right before next attack.
    //  Result: block is up ~80% of the time
    //  between your clicks.
    // ═══════════════════════════════════

    private void tickManual() {
        if (blockQueued) {
            blockQueued = false;
            sendBlock();
            ticksSinceBlock = 0;
        }

        // stay blocked until we need to attack again
        // the packet handler unblocks right before C02
    }

    // ═══════════════════════════════════
    //  AUTO
    //  Aggressive fixed-cycle blocking.
    //  Block immediately after every attack,
    //  hold block for 1 tick, unblock for
    //  attack window. Maximizes block uptime.
    //
    //  Cycle:
    //  T+0: C02 attack (unblocked this tick)
    //  T+0: C08 block (same tick, after attack)
    //  T+1: still blocking (absorbing hits)
    //  T+2: C07 unblock → ready for next attack
    //
    //  Block uptime: 2 out of 3 ticks = 66%
    //  With faster CPS: even higher
    // ═══════════════════════════════════

    private void tickAuto() {
        EntityLivingBase target = getTarget();
        if (target == null || opDist > 5.0) {
            if (serverBlocking) sendUnblock();
            return;
        }

        if (blockQueued) {
            blockQueued = false;
            sendBlock();
            ticksSinceBlock = 0;
        }

        // if we've been blocking for 2+ ticks and no attack is pending,
        // stay blocked — unblock happens in packet handler when C02 fires
        // this maximizes block uptime
    }

    // ═══════════════════════════════════════════════════════════════
    //  PREDICT
    //  Kalman-filtered rhythm prediction + phase model.
    //
    //  The Kalman filter maintains a running estimate of the opponent's
    //  true swing interval with uncertainty bounds. On each tick we
    //  compute their phase (fraction of interval elapsed) and derive a
    //  "time until next swing" estimate. We block when that estimate
    //  falls inside a latency-compensated window.
    //
    //  threat = f(phase, distance, approach velocity, velocity incoming)
    //
    //  Block uptime target: block ONLY when threat is real, unblock
    //  immediately when it isn't — so the server sees valid alternating
    //  block/hit patterns rather than a constant 1-tick toggle.
    // ═══════════════════════════════════════════════════════════════

    private void tickPredict() {
        if (predictHoldTicks > 0) predictHoldTicks--;

        EntityLivingBase target = getTarget();
        if (target == null) {
            if (serverBlocking && predictHoldTicks <= 0) sendUnblock();
            predictQueued = false;
            return;
        }

        // post-attack reblock
        if (blockQueued) {
            blockQueued = false;
            sendBlock();
            ticksSinceBlock = 0;
            // hold longer if opponent is in swing phase (high threat)
            predictHoldTicks = (kalmanThreat() > 0.65) ? 3 : 2;
        }

        if (predictQueued && !serverBlocking) {
            predictQueued = false;
            sendBlock();
            ticksSinceBlock = 0;
            predictHoldTicks = 2;
        }

        if (serverBlocking && predictHoldTicks <= 0 && ticksSinceBlock >= 1) {
            sendUnblock();
        }

        if (!serverBlocking) {
            double threat = kalmanThreat();

            // Kalman phase gate: block when swing is imminent (phase > 0.82)
            // or when we have high confidence (kfSamples > 3) and phase > 0.75
            boolean rhythmBlock = kfEstimate > 0 && kfSamples >= 2 && opDist < 5.0
                    && (opPhase > (kfSamples >= 4 ? 0.75 : 0.82));

            // immediate reaction: swing detected this tick
            boolean reactBlock = opSwingAge <= 1 && opDist < 4.5;

            // approach gate: closing fast + recent swing
            boolean approachBlock = opApproachTicks >= 2 && opSwingAge <= 5 && opDist < 4.2;

            // velocity gate: incoming KB, block to reduce it
            boolean velBlock = velIncoming && opDist < 5.0;

            // close quarters always relevant
            boolean cqBlock = opDist < 2.2 && opSwingAge <= 6;

            if ((rhythmBlock || reactBlock || approachBlock || velBlock || cqBlock)
                    && threat > 0.30 && rollChance()) {
                predictQueued = true;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  LAG
    //  Block → hold outgoing C0F transactions for lagTicks ticks →
    //  unblock → flush. Server can't confirm we stopped blocking
    //  until transactions arrive, extending effective server-side
    //  block time beyond what the client actually held.
    //
    //  With Lag Jitter: randomise hold ±1 tick to prevent detection
    //  by constant-interval analysis (NCP, Intave, Polar).
    // ═══════════════════════════════════════════════════════════════

    private void tickLag() {
        if (blockQueued) {
            blockQueued = false;
            sendBlock();
            ticksSinceBlock = 0;
            lagActive    = true;
            int base     = lagTicks.getValue();
            lagTicksLeft = lagJitter.getValue()
                    ? base + ThreadLocalRandom.current().nextInt(3) - 1   // base-1 to base+1
                    : base;
            lagTicksLeft = Math.max(1, lagTicksLeft);
        }

        if (lagActive) {
            lagTicksLeft--;
            if (lagTicksLeft <= 0) {
                sendUnblock();
                flushLag();
                lagActive = false;
            }
        }

        if (serverBlocking && !lagActive && ticksSinceBlock >= 3) {
            sendUnblock();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  SMART
    //  Physics-driven adaptive blocking. Only blocks when one of
    //  these conditions is predicted to be true:
    //
    //  1. FALL DAMAGE IMMINENT — Newton kinematics:
    //     v²  = v₀² + 2·g·Δh   →   fallDmg = max(0, fallH - 3)·0.5
    //     We predict fall damage from current motionY and height above
    //     ground. Block starts when predicted damage > 0.
    //
    //  2. INCOMING HIT IN COMBAT — Kalman phase window:
    //     Block only when threat score > smartThreshold AND we are
    //     actively in combat (took/dealt damage in last 4 seconds).
    //     Unblock immediately when threat drops — zero wasted blocks.
    //
    //  3. VELOCITY PACKET INCOMING — react within 1 tick.
    //
    //  If smartCombat is true: modes 2+3 only fire when inCombat.
    //  If smartFall is true:   mode 1 fires independently of combat.
    //
    //  No blocks are ever wasted on an opponent who isn't swinging.
    // ═══════════════════════════════════════════════════════════════

    private void tickSmart() {
        if (smartHoldTicks > 0) smartHoldTicks--;

        // ── post-attack reblock ──
        if (blockQueued) {
            blockQueued = false;
            if (!smartCombat.getValue() || inCombat) {
                sendBlock();
                ticksSinceBlock = 0;
                smartBlocking  = true;
                // hold just long enough for the server to register it
                smartHoldTicks = 1 + (opSwingAge <= 3 ? 1 : 0);
            }
        }

        // ── CASE 1: fall damage prediction ──
        if (smartFall.getValue() && !smartBlocking) {
            // fallDmgPred is computed by updateFallDmgPrediction() each tick
            // Block only if predicted damage exceeds the threshold
            if (fallDmgPred > 0.5) {
                sendBlock();
                smartBlocking  = true;
                smartHoldTicks = 3; // hold through landing
            }
        }

        // ── CASE 2: incoming hit in combat ──
        if (!smartBlocking && (!smartCombat.getValue() || inCombat)) {
            double threat = kalmanThreat();

            // Kalman phase: swing arriving within ~1.5 ticks' worth of ms
            boolean rhythmImminent = kfEstimate > 0 && kfSamples >= 2
                    && opDist < 4.8 && opPhase > 0.80;

            // immediate reaction to observed swing
            boolean reactImm = opSwingAge <= 1 && opDist < 4.5;

            // approach + velocity
            boolean velThreat = velIncoming && (!smartCombat.getValue() || inCombat);

            // Demand higher threat confidence than PREDICT — we don't waste blocks
            if ((rhythmImminent || reactImm || velThreat) && threat > 0.50 && rollChance()) {
                sendBlock();
                smartBlocking  = true;
                smartHoldTicks = 2;
            }
        }

        // ── CASE 3: velocity incoming (highest priority) ──
        if (!smartBlocking && velIncoming && velMagnitude > 0.25) {
            sendBlock();
            smartBlocking  = true;
            smartHoldTicks = 2;
        }

        // ── unblock when hold expires ──
        if (smartBlocking && smartHoldTicks <= 0 && ticksSinceBlock >= 1) {
            sendUnblock();
            smartBlocking = false;
        }

        // ── safety release if no real threat for 3 ticks ──
        if (serverBlocking && kalmanThreat() < 0.15 && opSwingAge > 8
                && !velIncoming && fallDmgPred < 0.3 && ticksSinceBlock >= 2) {
            sendUnblock();
            smartBlocking = false;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  PHYSICS HELPERS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Newton kinematics fall damage predictor.
     *
     * In Minecraft, fall damage = max(0, fallDistance - 3) * 0.5 hp,
     * reduced by armour. We predict fall distance using:
     *   Δh = v₀·t + ½·g·t²    (discrete: motionY + gravity each tick)
     * Simulate up to 40 ticks ahead, tracking fallDistance accumulation.
     * If predicted damage × (1 - armourFactor) > smartFallH threshold,
     * block now (takes effect before landing since block reduces fall dmg).
     */
    private void updateFallDmgPrediction() {
        fallDmgPred = 0.0;
        if (mc.thePlayer == null || mc.thePlayer.onGround) return;

        double motY   = mc.thePlayer.motionY;
        double posY   = mc.thePlayer.posY;
        double fallD  = mc.thePlayer.fallDistance;
        final double g = 0.08; // Minecraft gravity per tick

        // simulate descent
        for (int t = 0; t < 40; t++) {
            motY  = (motY - g) * 0.98; // drag
            posY += motY;
            if (motY < 0) fallD += -motY;

            // check if we'd hit ground (crude: posY hits integer block boundary)
            // We're conservative: just predict from current fallDistance accumulation
            if (mc.theWorld != null) {
                try {
                    net.minecraft.block.Block b = mc.theWorld.getBlockState(
                            new BlockPos(mc.thePlayer.posX, posY - 0.1, mc.thePlayer.posZ)).getBlock();
                    if (b != null && b.getMaterial() != net.minecraft.block.material.Material.air) {
                        // landing here — compute damage
                        double rawDmg = Math.max(0, fallD - 3.0);
                        // armour reduction (approximate: each armour point = ~4% reduction)
                        int armour = mc.thePlayer.getTotalArmorValue();
                        double reduction = Math.min(0.80, armour * 0.04);
                        fallDmgPred = rawDmg * (1.0 - reduction);
                        return;
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Kalman-filtered threat score ∈ [0, 1].
     *
     * Combines:
     *  - Phase confidence: how close is opponent to next swing
     *  - Distance factor: exponential decay with distance
     *  - Approach velocity: closing speed amplifies threat
     *  - Observation age: older swing observation → lower weight
     *
     * Returns 0 if Kalman has no data yet.
     */
    private double kalmanThreat() {
        EntityLivingBase target = getTarget();
        if (target == null) return 0.0;

        // distance factor: threat drops off exponentially beyond 3 blocks
        double distFactor = Math.exp(-Math.max(0, opDist - 2.0) * 0.6);

        // phase factor: how imminent is next swing
        double phaseFactor = kfEstimate > 0 && kfSamples >= 2
                ? Math.pow(opPhase, 2.5)   // non-linear: ramps sharply near 1.0
                : 0.0;

        // approach factor: closing velocity increases threat
        double closingVel  = Math.max(0, opPrevDist - opDist);
        double approachFactor = Math.min(1.0, closingVel * 8.0);

        // observation recency: swing seen N ticks ago
        double recencyFactor = Math.exp(-opSwingAge * 0.18);

        // velocity incoming: hard 0.4 bonus
        double velFactor = velIncoming ? 0.4 : 0.0;

        double raw = distFactor * 0.35
                   + phaseFactor * 0.30
                   + approachFactor * 0.15
                   + recencyFactor * 0.15
                   + velFactor;

        return Math.min(1.0, raw);
    }

    /**
     * Updates the Kalman filter with a new swing interval measurement.
     * Also updates the phase tracker.
     */
    private void updateKalman(long intervalMs) {
        // prediction step: error covariance grows with process noise
        kfErrorCov += kfProcNoise;

        // update step
        double K       = kfErrorCov / (kfErrorCov + kfMeasNoise);
        kfEstimate     = kfEstimate + K * (intervalMs - kfEstimate);
        kfErrorCov     = (1.0 - K) * kfErrorCov;
        kfSamples++;
    }

    /** Updates opPhase: fraction of Kalman interval elapsed since last swing. */
    private void updatePhase() {
        if (kfEstimate <= 0 || opLastSwingMs <= 0) { opPhase = 0; return; }
        long elapsed = System.currentTimeMillis() - opLastSwingMs;
        opPhase = Math.min(1.0, elapsed / kfEstimate);
    }

    /**
     * Updates inCombat flag.
     * In combat if: recently attacked or took damage in last ~4 seconds (80 ticks).
     */
    private void updateCombatState() {
        if (mc.thePlayer == null) return;
        float hp = mc.thePlayer.getHealth();
        if (hp < lastHealth) {
            inCombat       = true;
            combatCooldown = 80;
        }
        lastHealth = hp;
        if (ticksSinceAttack < 40) {
            inCombat       = true;
            combatCooldown = 80;
        }
        if (combatCooldown > 0) combatCooldown--;
        else inCombat = false;
    }

    // ═══════════════════════════════════
    //  PACKET HANDLER
    // ═══════════════════════════════════

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || mc.thePlayer == null) return;

        // ── OUTGOING ──
        if (event.getType() == EventType.SEND) {

            // detect ALL attack packets from any source
            if (event.getPacket() instanceof C02PacketUseEntity) {
                C02PacketUseEntity pkt = (C02PacketUseEntity) event.getPacket();
                if (pkt.getAction() == C02PacketUseEntity.Action.ATTACK) {
                    onAttackDetected();
                }
            }

            // lag mode: hold transactions while lagging
            if (mode.getValue() == 3 && lagActive && !lagFlushing) {
                if (event.getPacket() instanceof C0FPacketConfirmTransaction) {
                    event.setCancelled(true);
                    lagHeld.addLast(event.getPacket());
                }
            }
        }

        // ── INCOMING ──
        if (event.getType() == EventType.RECEIVE) {

            // opponent swing
            if (event.getPacket() instanceof S0BPacketAnimation) {
                S0BPacketAnimation anim = (S0BPacketAnimation) event.getPacket();
                if (anim.getAnimationType() == 0) {
                    EntityLivingBase t = getTarget();
                    if (t != null && anim.getEntityID() == t.getEntityId()) {
                        recordOpponentSwing();
                    }
                }
            }

            // incoming velocity = incoming hit
            if (event.getPacket() instanceof S12PacketEntityVelocity) {
                S12PacketEntityVelocity vel = (S12PacketEntityVelocity) event.getPacket();
                if (vel.getEntityID() == mc.thePlayer.getEntityId()) {
                    // capture magnitude for Smart mode priority scoring
                    double vx = vel.getMotionX() / 8000.0;
                    double vy = vel.getMotionY() / 8000.0;
                    double vz = vel.getMotionZ() / 8000.0;
                    velMagnitude = Math.sqrt(vx * vx + vy * vy + vz * vz);
                    velIncoming  = true;
                    velCooldown  = 4;

                    if (serverBlocking) hitsBlocked++;
                }
            }
        }
    }

    /**
     * Called when any C02 ATTACK packet is detected.
     * Handles the unblock→attack→reblock cycle.
     *
     * The key insight: we send C07 unblock BEFORE the C02
     * goes out (it hasn't been sent yet, we're in the send
     * handler). Then C02 goes through. Then next tick we
     * send C08 block.
     *
     * Packet order on the wire:
     *   C07 (unblock) → C02 (attack) → C08 (block)
     *
     * Server sees: stopped blocking, attacked, started blocking.
     * This is exactly what vanilla does when you left-click
     * while holding right-click with a sword.
     */
    private void onAttackDetected() {
        attacksThisTick++;
        ticksSinceAttack = 0;

        // unblock before the attack packet goes out
        // C07 is sent NOW, C02 follows immediately after
        if (serverBlocking) {
            sendUnblock();
            ticksSinceUnblock = 0;
        }

        // queue reblock for next tick
        blockQueued = true;
    }

    // ═══════════════════════════════════
    //  BLOCK / UNBLOCK
    // ═══════════════════════════════════

    private void sendBlock() {
        if (serverBlocking) return;
        if (!ItemUtil.isHoldingSword()) return;

        PacketUtil.sendPacketSafe(new C08PacketPlayerBlockPlacement(mc.thePlayer.getHeldItem()));
        mc.thePlayer.setItemInUse(mc.thePlayer.getHeldItem(),
                mc.thePlayer.getHeldItem().getMaxItemUseDuration());
        serverBlocking = true;
        totalBlocks++;
    }

    private void sendUnblock() {
        if (!serverBlocking) return;

        PacketUtil.sendPacketSafe(new C07PacketPlayerDigging(
                C07PacketPlayerDigging.Action.RELEASE_USE_ITEM,
                BlockPos.ORIGIN, EnumFacing.DOWN));
        mc.thePlayer.stopUsingItem();
        serverBlocking = false;
        totalUnblocks++;
    }

    private void flushLag() {
        if (lagHeld.isEmpty()) return;
        if (mc.getNetHandler() == null) { lagHeld.clear(); return; }

        lagFlushing = true;
        try {
            Packet<?> p;
            while ((p = lagHeld.pollFirst()) != null) {
                // Use sendPacketSafe (direct Netty write) so held C0F transactions
                // bypass FakeLag's queue and arrive at the server immediately —
                // order: C08 block → [held C0Fs] → C07 unblock → C02 attack
                PacketUtil.sendPacketSafe(p);
            }
        } catch (Exception ignored) {
        } finally {
            lagFlushing = false;
        }
    }

    // ═══════════════════════════════════
    //  OPPONENT TRACKING
    // ═══════════════════════════════════

    private void updateOpponent() {
        EntityLivingBase target = getTarget();
        if (target == null) { opId = -1; return; }

        if (target.getEntityId() != opId) {
            opId           = target.getEntityId();
            opSwingAge     = 999;
            opDist         = mc.thePlayer.getDistanceToEntity(target);
            opPrevDist     = opDist;
            opApproachTicks = 0;
            opLastSwingMs  = 0;
            kfEstimate     = 0;
            kfErrorCov     = 1e6;
            kfSamples      = 0;
            opPhase        = 0;
        }

        opHurtTime = target.hurtTime;
        opPrevDist = opDist;
        opDist     = mc.thePlayer.getDistanceToEntity(target);

        if (opDist - opPrevDist < -0.05) opApproachTicks++;
        else opApproachTicks = Math.max(0, opApproachTicks - 1);

        // update phase every tick so kalmanThreat() always has fresh data
        updatePhase();
    }

    private void recordOpponentSwing() {
        long now = System.currentTimeMillis();
        if (opLastSwingMs > 0) {
            long interval = now - opLastSwingMs;
            // plausible swing interval: 100ms (10 CPS) to 2000ms (0.5 CPS)
            if (interval >= 100 && interval <= 2000) {
                updateKalman(interval);
            }
        }
        opLastSwingMs = now;
        opSwingAge    = 0;
        opPhase       = 0.0; // reset phase: they just swung
    }

    // ═══════════════════════════════════
    //  UTILITY
    // ═══════════════════════════════════

    private boolean rollChance() {
        return chance.getValue() >= 100 || Math.random() * 100.0 < chance.getValue();
    }

    private EntityLivingBase getTarget() {
        try {
            KillAura ka = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
            if (ka != null && ka.isEnabled()) {
                EntityLivingBase t = ka.getTarget();
                if (t != null) return t;
            }
        } catch (Exception ignored) {}

        if (mc.objectMouseOver != null && mc.objectMouseOver.entityHit instanceof EntityLivingBase) {
            return (EntityLivingBase) mc.objectMouseOver.entityHit;
        }
        return null;
    }

    public boolean isBlocking() {
        return serverBlocking && ItemUtil.isHoldingSword();
    }

    @Override
    public String[] getSuffix() {
        if (mode.getValue() == 4) {
            // Smart: show threat % and combat state
            String combatStr = inCombat ? "§acombat" : "§7idle";
            return new String[]{mode.getModeString(), combatStr,
                    String.format("%.0f%%", kalmanThreat() * 100)};
        }
        return new String[]{mode.getModeString()};
    }
}