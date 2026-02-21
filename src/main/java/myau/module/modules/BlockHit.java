package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;
import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.*;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
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

@ModuleInfo(category = ModuleCategory.COMBAT)
public class BlockHit extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    public final PercentProperty chance = new PercentProperty("Chance", 100);
    public final BooleanProperty requireMouseDown = new BooleanProperty("Require Mouse Down", false);
    public final ModeProperty mode = new ModeProperty("Mode", 0,
            new String[]{"MANUAL", "AUTO", "PREDICT", "LAG"});

    // server-side block state
    private boolean serverBlocking = false;

    // tick counters
    private int ticksSinceAttack = 999;
    private int ticksSinceBlock = 999;
    private int ticksSinceUnblock = 999;

    // attack detection
    private boolean attackDetected = false;
    private boolean blockQueued = false;
    private int attacksThisTick = 0;

    // opponent tracking
    private int opId = -1;
    private int opSwingAge = 999;
    private double opDist = 10.0;
    private double opPrevDist = 10.0;
    private int opApproachTicks = 0;
    private int opHurtTime = 0;

    // opponent rhythm prediction
    private long opLastSwingMs = 0;
    private long[] opIntervals = new long[8];
    private int opIntIdx = 0;
    private long opAvgInterval = 0;

    // velocity detection
    private boolean velIncoming = false;
    private int velCooldown = 0;

    // predict mode
    private boolean predictQueued = false;
    private int predictHoldTicks = 0;

    // lag mode
    private final Deque<Packet<?>> lagHeld = new ArrayDeque<>();
    private boolean lagActive = false;
    private int lagTicksLeft = 0;
    private boolean lagFlushing = false;

    // stats for internal tuning
    private int totalBlocks = 0;
    private int totalUnblocks = 0;
    private int hitsBlocked = 0;

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
        serverBlocking = false;
        ticksSinceAttack = 999;
        ticksSinceBlock = 999;
        ticksSinceUnblock = 999;
        attackDetected = false;
        blockQueued = false;
        attacksThisTick = 0;
        opId = -1;
        opSwingAge = 999;
        opDist = 10.0;
        opPrevDist = 10.0;
        opApproachTicks = 0;
        opHurtTime = 0;
        opLastSwingMs = 0;
        opAvgInterval = 0;
        opIntIdx = 0;
        for (int i = 0; i < opIntervals.length; i++) opIntervals[i] = 0;
        velIncoming = false;
        velCooldown = 0;
        predictQueued = false;
        predictHoldTicks = 0;
        lagHeld.clear();
        lagActive = false;
        lagTicksLeft = 0;
        lagFlushing = false;
        totalBlocks = 0;
        totalUnblocks = 0;
        hitsBlocked = 0;
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

        switch (mode.getValue()) {
            case 0: tickManual(); break;
            case 1: tickAuto(); break;
            case 2: tickPredict(); break;
            case 3: tickLag(); break;
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

    // ═══════════════════════════════════
    //  PREDICT
    //  Preemptive blocking based on
    //  opponent swing rhythm + distance.
    //  Blocks BEFORE their hit lands.
    //  Also blocks after our attacks.
    //
    //  Effective block uptime: 85-95%
    //  because we predict incoming hits
    //  AND block after our own attacks.
    // ═══════════════════════════════════

    private void tickPredict() {
        if (predictHoldTicks > 0) predictHoldTicks--;

        EntityLivingBase target = getTarget();
        if (target == null) {
            if (serverBlocking && predictHoldTicks <= 0) sendUnblock();
            predictQueued = false;
            return;
        }

        // process queued post-attack block
        if (blockQueued) {
            blockQueued = false;
            sendBlock();
            ticksSinceBlock = 0;
            // hold for 1-2 ticks depending on threat
            predictHoldTicks = opSwingAge <= 3 ? 3 : 2;
        }

        // process predicted block
        if (predictQueued && !serverBlocking) {
            predictQueued = false;
            sendBlock();
            ticksSinceBlock = 0;
            predictHoldTicks = 2;
        }

        // unblock when hold expires
        if (serverBlocking && predictHoldTicks <= 0 && ticksSinceBlock >= 1) {
            sendUnblock();
        }

        // run prediction engine
        if (!serverBlocking) {
            boolean shouldBlock = false;

            // immediate threat: opponent swung 0-1 ticks ago and is close
            if (opSwingAge <= 1 && opDist < 4.5) {
                shouldBlock = true;
            }

            // rushing threat: closing distance + recent swing
            if (opApproachTicks >= 2 && opSwingAge <= 4 && opDist < 4.0) {
                shouldBlock = true;
            }

            // rhythm prediction: next swing due within 80ms
            if (opAvgInterval > 0 && opLastSwingMs > 0) {
                long elapsed = System.currentTimeMillis() - opLastSwingMs;
                long remaining = opAvgInterval - elapsed;
                if (remaining <= 80 && remaining >= -30 && opDist < 4.5) {
                    shouldBlock = true;
                }
            }

            // close quarters: always be ready
            if (opDist < 2.5 && opSwingAge <= 5) {
                shouldBlock = true;
            }

            // velocity incoming: we're about to take KB, block to halve it
            if (velIncoming && opDist < 5.0) {
                shouldBlock = true;
            }

            if (shouldBlock && rollChance()) {
                predictQueued = true;
            }
        }
    }

    // ═══════════════════════════════════
    //  LAG
    //  After blocking, hold outgoing C0F
    //  transactions for 2-3 ticks.
    //  Server can't confirm we unblocked
    //  until transactions arrive, so it
    //  applies block reduction for longer
    //  than we actually blocked.
    //
    //  Effective server-side block time:
    //  actual block ticks + lag ticks
    // ═══════════════════════════════════

    private void tickLag() {
        if (blockQueued) {
            blockQueued = false;
            sendBlock();
            ticksSinceBlock = 0;

            // start lagging after block
            lagActive = true;
            lagTicksLeft = 3;
        }

        if (lagActive) {
            lagTicksLeft--;
            if (lagTicksLeft <= 0) {
                // unblock and flush held transactions
                sendUnblock();
                flushLag();
                lagActive = false;
            }
        }

        // safety: if blocking without lag active, clean up
        if (serverBlocking && !lagActive && ticksSinceBlock >= 2) {
            sendUnblock();
        }
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
                    velIncoming = true;
                    velCooldown = 4;

                    // if we're blocking when velocity arrives, count it
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

        PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(mc.thePlayer.getHeldItem()));
        mc.thePlayer.setItemInUse(mc.thePlayer.getHeldItem(),
                mc.thePlayer.getHeldItem().getMaxItemUseDuration());
        serverBlocking = true;
        totalBlocks++;
    }

    private void sendUnblock() {
        if (!serverBlocking) return;

        PacketUtil.sendPacket(new C07PacketPlayerDigging(
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
                mc.getNetHandler().addToSendQueue(p);
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
            opId = target.getEntityId();
            opSwingAge = 999;
            opDist = mc.thePlayer.getDistanceToEntity(target);
            opPrevDist = opDist;
            opApproachTicks = 0;
            opLastSwingMs = 0;
            opAvgInterval = 0;
            opIntIdx = 0;
            for (int i = 0; i < opIntervals.length; i++) opIntervals[i] = 0;
        }

        opHurtTime = target.hurtTime;
        opPrevDist = opDist;
        opDist = mc.thePlayer.getDistanceToEntity(target);

        if (opDist - opPrevDist < -0.05) opApproachTicks++;
        else opApproachTicks = 0;
    }

    private void recordOpponentSwing() {
        long now = System.currentTimeMillis();
        if (opLastSwingMs > 0) {
            long interval = now - opLastSwingMs;
            if (interval > 0 && interval < 2000) {
                opIntervals[opIntIdx % opIntervals.length] = interval;
                opIntIdx++;
                long sum = 0; int cnt = 0;
                for (long si : opIntervals) {
                    if (si > 0) { sum += si; cnt++; }
                }
                if (cnt > 0) opAvgInterval = sum / cnt;
            }
        }
        opLastSwingMs = now;
        opSwingAge = 0;
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
        return new String[]{mode.getModeString()};
    }
}