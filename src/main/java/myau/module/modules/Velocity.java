package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.*;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.*;
import net.minecraft.network.play.server.*;
import net.minecraft.util.MathHelper;

import java.util.*;

@ModuleInfo(category = ModuleCategory.COMBAT)
public class Velocity extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Random rng = new Random();

    public final ModeProperty mode = new ModeProperty("Mode", 0,
            new String[]{"Normal", "Lag", "Delay"});

    public final PercentProperty horizontal = new PercentProperty("Horizontal", 0,
            () -> mode.getValue() == 0);
    public final PercentProperty vertical = new PercentProperty("Vertical", 0,
            () -> mode.getValue() == 0);
    public final IntProperty ticks = new IntProperty("Ticks", 0, 0, 10,
            () -> mode.getValue() == 0);
    public final BooleanProperty kiteMode = new BooleanProperty("Kite Mode", false,
            () -> mode.getValue() == 0);
    public final PercentProperty kiteHorizontal = new PercentProperty("Kite Horizontal", 200,
            () -> mode.getValue() == 0 && kiteMode.getValue());
    public final PercentProperty kiteVertical = new PercentProperty("Kite Vertical", 100,
            () -> mode.getValue() == 0 && kiteMode.getValue());
    public final BooleanProperty alwaysKite = new BooleanProperty("Always Kite", false,
            () -> mode.getValue() == 0 && kiteMode.getValue());

    public final IntProperty airDelay = new IntProperty("Air Delay", 3, 0, 20,
            () -> mode.getValue() == 1);
    public final IntProperty groundDelay = new IntProperty("Ground Delay", 5, 0, 20,
            () -> mode.getValue() == 1);
    public final BooleanProperty lagSmart = new BooleanProperty("Smart", true,
            () -> mode.getValue() == 1);
    public final BooleanProperty lagTransCancel = new BooleanProperty("Cancel Transactions", true,
            () -> mode.getValue() == 1);
    public final BooleanProperty lagC03Spoof = new BooleanProperty("C03 Spoof", true,
            () -> mode.getValue() == 1);
    public final IntProperty lagC03Count = new IntProperty("C03 Count", 3, 1, 8,
            () -> mode.getValue() == 1 && lagC03Spoof.getValue());
    public final BooleanProperty lagAbuse = new BooleanProperty("Abuse", false,
            () -> mode.getValue() == 1);

    public final IntProperty delayTicks = new IntProperty("Delay Ticks", 5, 1, 1000,
            () -> mode.getValue() == 2);
    public final PercentProperty delayHorizontal = new PercentProperty("Delay Horizontal", 0,
            () -> mode.getValue() == 2);
    public final PercentProperty delayVertical = new PercentProperty("Delay Vertical", 0,
            () -> mode.getValue() == 2);
    public final BooleanProperty delaySmooth = new BooleanProperty("Smooth Apply", false,
            () -> mode.getValue() == 2);
    public final IntProperty delaySmoothTicks = new IntProperty("Smooth Ticks", 3, 1, 10,
            () -> mode.getValue() == 2 && delaySmooth.getValue());

    public final PercentProperty chance = new PercentProperty("Chance", 100);
    public final BooleanProperty onlyWhenTargeting =
            new BooleanProperty("Only When Targeting", false);
    public final BooleanProperty waterCheck =
            new BooleanProperty("Water Check", true);

    private static final double SMALL_KB_THRESHOLD = 0.15;
    private static final double LARGE_KB_THRESHOLD = 0.45;
    private static final int SMALL_KB_ACCEPT_RATE = 85;
    private static final int MEDIUM_KB_ACCEPT_RATE = 22;
    private static final int LARGE_KB_ACCEPT_RATE = 10;
    private static final double PARTIAL_H_BASE = 0.50;
    private static final double PARTIAL_V_BASE = 0.85;
    private static final double RESIDUAL_BASE_H = 0.06;
    private static final double RESIDUAL_SCALE = 0.035;
    private static final double GROUND_MIN_H = 0.15;
    private static final double GROUND_MIN_V = 0.60;
    private static final int MAX_CONSECUTIVE_CANCELS = 3;
    private static final int ABSOLUTE_MAX_HOLD_TICKS = 16;
    private static final int MAX_QUEUED_PACKETS = 12;
    private static final int RESPAWN_GRACE_TICKS_CONST = 20;
    private static final long COMBO_WINDOW_MS = 650L;
    private static final long ENGAGEMENT_TIMEOUT_MS = 5000L;
    private static final int ENGAGEMENT_GRACE_HITS = 1;
    private static final int HISTORY_WINDOW = 20;
    private static final int MAX_DELAYED_VELOCITIES = 200;

    private int velocityTicks = 0;
    private double storedMotionX, storedMotionY, storedMotionZ;

    private final Deque<Packet<?>> heldPackets = new ArrayDeque<>();
    private boolean inHoldPhase = false;
    private int holdTicksRemaining = 0;
    private int holdTicksElapsed = 0;
    private boolean inReleasePhase = false;
    private boolean currentlyFlushing = false;
    private boolean holdLockout = false;
    private int lockoutTicksRemaining = 0;
    private int consecutiveCancels = 0;
    private boolean mustAcceptNext = false;
    private long lastVelocityTimeMs = 0L;
    private int comboHitIndex = 0;
    private int engagementHitCount = 0;
    private long lastEngagementTimeMs = 0L;
    private int respawnGraceTicks = 0;
    private final Deque<Boolean> cancelHistory = new ArrayDeque<>();
    private double residualX, residualY, residualZ;
    private boolean hasResidual = false;
    private boolean lastCancelWasGrounded = false;
    private int postCancelGroundTicks = 0;
    private int lagTicksSinceVelocity = 0;
    private boolean lagSentC03ThisCycle = false;
    private int lagHoldC03Counter = 0;
    private int lagCancelStreak = 0;
    private int lagAcceptStreak = 0;
    private boolean lagForceAcceptWindow = false;
    private int lagForceAcceptTicks = 0;
    private double lagLastVelMag = 0;
    private boolean lagAbuseActive = false;
    private int lagAbuseTicks = 0;

    private enum Strategy { FULL_ACCEPT, PARTIAL_ACCEPT, LAG_CANCEL }

    private static class DelayedVelocity {
        final double vx, vy, vz;
        int ticksRemaining;
        final boolean smooth;
        int smoothTicksLeft;

        DelayedVelocity(double vx, double vy, double vz, int ticksRemaining, boolean smooth, int smoothTicks) {
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.ticksRemaining = ticksRemaining;
            this.smooth = smooth;
            this.smoothTicksLeft = smoothTicks;
        }
    }

    private final Deque<DelayedVelocity> delayedVelocities = new ArrayDeque<>();
    private final List<DelayedVelocity> smoothingActive = new ArrayList<>();

    public Velocity() {
        super("Velocity", false);
    }

    @Override
    public void onEnabled() {
        resetAllState();
    }

    @Override
    public void onDisabled() {
        emergencyFlush();
        resetAllState();
    }

    private void resetAllState() {
        velocityTicks = 0;
        storedMotionX = storedMotionY = storedMotionZ = 0.0;
        heldPackets.clear();
        inHoldPhase = false;
        holdTicksRemaining = 0;
        holdTicksElapsed = 0;
        inReleasePhase = false;
        currentlyFlushing = false;
        holdLockout = false;
        lockoutTicksRemaining = 0;
        consecutiveCancels = 0;
        mustAcceptNext = false;
        lastVelocityTimeMs = 0L;
        comboHitIndex = 0;
        engagementHitCount = 0;
        lastEngagementTimeMs = 0L;
        respawnGraceTicks = 0;
        cancelHistory.clear();
        residualX = residualY = residualZ = 0;
        hasResidual = false;
        lastCancelWasGrounded = false;
        postCancelGroundTicks = 0;
        delayedVelocities.clear();
        smoothingActive.clear();
        lagTicksSinceVelocity = 0;
        lagSentC03ThisCycle = false;
        lagHoldC03Counter = 0;
        lagCancelStreak = 0;
        lagAcceptStreak = 0;
        lagForceAcceptWindow = false;
        lagForceAcceptTicks = 0;
        lagLastVelMag = 0;
        lagAbuseActive = false;
        lagAbuseTicks = 0;
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (event.getType() != EventType.PRE || mc.thePlayer == null) return;

        if (respawnGraceTicks > 0) respawnGraceTicks--;
        if (lockoutTicksRemaining > 0) {
            lockoutTicksRemaining--;
            if (lockoutTicksRemaining <= 0) holdLockout = false;
        }

        if (mc.thePlayer.isDead || mc.thePlayer.getHealth() <= 0.0F) {
            if (inHoldPhase || inReleasePhase) {
                emergencyFlush();
                inHoldPhase = false;
                inReleasePhase = false;
                holdTicksElapsed = 0;
                holdLockout = false;
                lockoutTicksRemaining = 0;
            }
            delayedVelocities.clear();
            smoothingActive.clear();
            lagAbuseActive = false;
            return;
        }

        if (mode.getValue() == 0 && velocityTicks > 0) {
            if (--velocityTicks == 0) {
                mc.thePlayer.motionX = storedMotionX;
                mc.thePlayer.motionY = storedMotionY;
                mc.thePlayer.motionZ = storedMotionZ;
            }
        }

        if (mode.getValue() == 1) {
            tickLagMode();
        }

        if (mode.getValue() == 2) {
            tickDelayMode();
        }
    }

    private void tickDelayMode() {
        Iterator<DelayedVelocity> smoothIt = smoothingActive.iterator();
        while (smoothIt.hasNext()) {
            DelayedVelocity sv = smoothIt.next();
            if (sv.smoothTicksLeft <= 0) {
                smoothIt.remove();
                continue;
            }
            double frac = 1.0 / sv.smoothTicksLeft;
            mc.thePlayer.motionX += sv.vx * frac;
            mc.thePlayer.motionY += sv.vy * frac;
            mc.thePlayer.motionZ += sv.vz * frac;
            sv.smoothTicksLeft--;
            if (sv.smoothTicksLeft <= 0) smoothIt.remove();
        }

        if (delayedVelocities.isEmpty()) return;

        boolean anyApplied = false;
        double finalVx = 0, finalVy = 0, finalVz = 0;
        DelayedVelocity lastExpired = null;

        Iterator<DelayedVelocity> it = delayedVelocities.iterator();
        while (it.hasNext()) {
            DelayedVelocity dv = it.next();
            dv.ticksRemaining--;
            if (dv.ticksRemaining <= 0) {
                lastExpired = dv;
                finalVx = dv.vx;
                finalVy = dv.vy;
                finalVz = dv.vz;
                anyApplied = true;
                it.remove();
            }
        }

        if (anyApplied && mc.thePlayer != null) {
            if (lastExpired != null && lastExpired.smooth && lastExpired.smoothTicksLeft > 0) {
                smoothingActive.add(new DelayedVelocity(finalVx, finalVy, finalVz, 0, true, lastExpired.smoothTicksLeft));
            } else {
                mc.thePlayer.motionX = finalVx;
                mc.thePlayer.motionY = finalVy;
                mc.thePlayer.motionZ = finalVz;
            }
        }
    }

    private void tickLagMode() {
        lagTicksSinceVelocity++;

        if (lagForceAcceptWindow) {
            lagForceAcceptTicks--;
            if (lagForceAcceptTicks <= 0) lagForceAcceptWindow = false;
        }

        if (lagAbuseActive && lagAbuse.getValue()) {
            lagAbuseTicks--;
            if (lagAbuseTicks <= 0) {
                lagAbuseActive = false;
            } else if (mc.thePlayer.onGround) {
                mc.thePlayer.motionY = 0.003;
                double yaw = Math.toRadians(mc.thePlayer.rotationYaw);
                mc.thePlayer.motionX -= Math.sin(yaw) * 0.02;
                mc.thePlayer.motionZ += Math.cos(yaw) * 0.02;
            }
        }

        if (lastCancelWasGrounded) {
            postCancelGroundTicks++;
            if (postCancelGroundTicks > 3 || !mc.thePlayer.onGround) {
                lastCancelWasGrounded = false;
                postCancelGroundTicks = 0;
            }
        }

        if (inHoldPhase) {
            holdTicksElapsed++;

            if (lagC03Spoof.getValue() && !lagSentC03ThisCycle && holdTicksElapsed >= 1) {
                currentlyFlushing = true;
                try {
                    int count = lagC03Count.getValue();
                    for (int i = 0; i < count; i++) {
                        mc.getNetHandler().addToSendQueue(new C03PacketPlayer(mc.thePlayer.onGround));
                    }
                } finally {
                    currentlyFlushing = false;
                }
                lagSentC03ThisCycle = true;
            }

            if (holdTicksElapsed >= ABSOLUTE_MAX_HOLD_TICKS
                    || heldPackets.size() >= MAX_QUEUED_PACKETS) {
                beginGradualRelease();
                return;
            }

            if (--holdTicksRemaining <= 0) {
                beginGradualRelease();
                return;
            }
        }

        if (inReleasePhase && !heldPackets.isEmpty()) {
            int releaseCount = 1 + rng.nextInt(3);
            currentlyFlushing = true;
            try {
                for (int i = 0; i < releaseCount && !heldPackets.isEmpty(); i++) {
                    mc.getNetHandler().addToSendQueue(heldPackets.pollFirst());
                }
            } finally {
                currentlyFlushing = false;
            }

            if (heldPackets.isEmpty()) {
                inReleasePhase = false;
                holdTicksElapsed = 0;
                holdLockout = true;
                lockoutTicksRemaining = 2 + rng.nextInt(3);
            }
        }

        if (hasResidual) {
            applyResidualVelocity();
        }

        if (comboHitIndex > 0
                && System.currentTimeMillis() - lastVelocityTimeMs > 2500L) {
            comboHitIndex = 0;
            lagCancelStreak = 0;
            lagAcceptStreak = 0;
        }
    }

    private void applyResidualVelocity() {
        if (mc.thePlayer == null) {
            hasResidual = false;
            return;
        }

        mc.thePlayer.motionX += residualX;
        mc.thePlayer.motionZ += residualZ;

        if (residualY > 0 && !mc.thePlayer.onGround) {
            mc.thePlayer.motionY += residualY;
        } else if (residualY > 0 && mc.thePlayer.onGround) {
            mc.thePlayer.motionY = residualY;
        }

        residualX = residualY = residualZ = 0;
        hasResidual = false;
    }

    private void beginGradualRelease() {
        inHoldPhase = false;
        inReleasePhase = true;
        lagSentC03ThisCycle = false;
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || mc.thePlayer == null || mc.getNetHandler() == null) return;

        if (event.getType() == EventType.SEND) {
            handleOutgoing(event);
            return;
        }

        if (event.getType() == EventType.RECEIVE) {
            handleIncoming(event);
        }
    }

    private void handleOutgoing(PacketEvent event) {
        if (mode.getValue() != 1) return;
        if (currentlyFlushing) return;

        Packet<?> pkt = event.getPacket();

        if (pkt instanceof C00PacketKeepAlive) {
            return;
        }

        if ((inHoldPhase || inReleasePhase) && lagTransCancel.getValue()) {
            if (pkt instanceof C0FPacketConfirmTransaction) {
                event.setCancelled(true);
                heldPackets.addLast(pkt);
                return;
            }
        }

        if (inHoldPhase && pkt instanceof C03PacketPlayer) {
            if (lagC03Spoof.getValue() && lagHoldC03Counter < 2) {
                lagHoldC03Counter++;
            }
        }
    }

    private void handleIncoming(PacketEvent event) {
        Packet<?> pkt = event.getPacket();

        if (pkt instanceof S07PacketRespawn || pkt instanceof S01PacketJoinGame) {
            emergencyFlush();
            resetAllState();
            if (mode.getValue() == 1) respawnGraceTicks = RESPAWN_GRACE_TICKS_CONST;
            return;
        }

        if (pkt instanceof S08PacketPlayerPosLook) {
            if (inHoldPhase || inReleasePhase) {
                emergencyFlush();
                inHoldPhase = false;
                inReleasePhase = false;
                holdTicksElapsed = 0;
                holdLockout = false;
                lockoutTicksRemaining = 0;
            }
            delayedVelocities.clear();
            smoothingActive.clear();
            lagForceAcceptWindow = false;
            lagCancelStreak = 0;
            return;
        }

        if (pkt instanceof S12PacketEntityVelocity) {
            S12PacketEntityVelocity vel = (S12PacketEntityVelocity) pkt;
            if (vel.getEntityID() != mc.thePlayer.getEntityId()) return;
            if (!shouldActivate()) return;

            switch (mode.getValue()) {
                case 0: handleNormal(vel, event); break;
                case 1: handleLagVelocity(vel, event); break;
                case 2: handleDelayVelocity(vel, event); break;
            }
            return;
        }

        if (pkt instanceof S27PacketExplosion) {
            S27PacketExplosion expl = (S27PacketExplosion) pkt;
            if (!shouldActivate()) return;

            switch (mode.getValue()) {
                case 0: handleNormalExplosion(expl, event); break;
                case 1: handleLagExplosion(expl, event); break;
                case 2: handleDelayExplosion(expl, event); break;
            }
        }
    }

    private void handleDelayVelocity(S12PacketEntityVelocity pkt, PacketEvent event) {
        event.setCancelled(true);

        double vx = pkt.getMotionX() / 8000.0;
        double vy = pkt.getMotionY() / 8000.0;
        double vz = pkt.getMotionZ() / 8000.0;

        double hm = delayHorizontal.getValue() / 100.0;
        double vm = delayVertical.getValue() / 100.0;

        vx *= hm;
        vy *= vm;
        vz *= hm;

        int delay = delayTicks.getValue();
        boolean smooth = delaySmooth.getValue();
        int sTicks = smooth ? delaySmoothTicks.getValue() : 1;

        if (delayedVelocities.size() >= MAX_DELAYED_VELOCITIES) {
            delayedVelocities.pollFirst();
        }

        delayedVelocities.addLast(new DelayedVelocity(vx, vy, vz, delay, smooth, sTicks));
    }

    private void handleDelayExplosion(S27PacketExplosion pkt, PacketEvent event) {
        double ex = pkt.func_149149_c(), ey = pkt.func_149144_d(), ez = pkt.func_149147_e();
        if (ex == 0 && ey == 0 && ez == 0) return;

        event.setCancelled(true);

        double hm = delayHorizontal.getValue() / 100.0;
        double vm = delayVertical.getValue() / 100.0;

        ex *= hm;
        ey *= vm;
        ez *= hm;

        int delay = delayTicks.getValue();
        boolean smooth = delaySmooth.getValue();
        int sTicks = smooth ? delaySmoothTicks.getValue() : 1;

        if (delayedVelocities.size() >= MAX_DELAYED_VELOCITIES) {
            delayedVelocities.pollFirst();
        }

        delayedVelocities.addLast(new DelayedVelocity(ex, ey, ez, delay, smooth, sTicks));
    }

    private void handleNormal(S12PacketEntityVelocity pkt, PacketEvent event) {
        event.setCancelled(true);
        double vx = pkt.getMotionX() / 8000.0;
        double vy = pkt.getMotionY() / 8000.0;
        double vz = pkt.getMotionZ() / 8000.0;

        boolean kite = kiteMode.getValue()
                && (alwaysKite.getValue() || isHitFromBehind(vx, vz));
        double hm = (kite ? kiteHorizontal.getValue() : horizontal.getValue()) / 100.0;
        double vm = (kite ? kiteVertical.getValue() : vertical.getValue()) / 100.0;

        vx *= hm;
        vy *= vm;
        vz *= hm;

        if (ticks.getValue() > 0) {
            storedMotionX = vx;
            storedMotionY = vy;
            storedMotionZ = vz;
            velocityTicks = ticks.getValue();
        } else {
            mc.thePlayer.motionX = vx;
            mc.thePlayer.motionY = vy;
            mc.thePlayer.motionZ = vz;
        }
    }

    private void handleNormalExplosion(S27PacketExplosion pkt, PacketEvent event) {
        double ex = pkt.func_149149_c(), ey = pkt.func_149144_d(), ez = pkt.func_149147_e();
        if (ex == 0 && ey == 0 && ez == 0) return;
        event.setCancelled(true);
        boolean kite = kiteMode.getValue()
                && (alwaysKite.getValue() || isHitFromBehind(ex, ez));
        double hm = (kite ? kiteHorizontal.getValue() : horizontal.getValue()) / 100.0;
        double vm = (kite ? kiteVertical.getValue() : vertical.getValue()) / 100.0;
        mc.thePlayer.motionX += ex * hm;
        mc.thePlayer.motionY += ey * vm;
        mc.thePlayer.motionZ += ez * hm;
    }

    private void handleLagVelocity(S12PacketEntityVelocity pkt, PacketEvent event) {
        long now = System.currentTimeMillis();

        double vx = pkt.getMotionX() / 8000.0;
        double vy = pkt.getMotionY() / 8000.0;
        double vz = pkt.getMotionZ() / 8000.0;
        double hMag = Math.sqrt(vx * vx + vz * vz);
        double totalMag = Math.sqrt(vx * vx + vy * vy + vz * vz);
        boolean grounded = mc.thePlayer.onGround;

        lagLastVelMag = totalMag;
        lagTicksSinceVelocity = 0;

        if (respawnGraceTicks > 0) {
            acceptVelocity(false);
            return;
        }

        if (now - lastEngagementTimeMs > ENGAGEMENT_TIMEOUT_MS) {
            engagementHitCount = 0;
            lagCancelStreak = 0;
            lagAcceptStreak = 0;
        }
        lastEngagementTimeMs = now;
        engagementHitCount++;

        if (engagementHitCount <= ENGAGEMENT_GRACE_HITS) {
            lastVelocityTimeMs = now;
            comboHitIndex = 1;
            lagAcceptStreak++;
            lagCancelStreak = 0;
            acceptVelocity(false);
            return;
        }

        if (lagForceAcceptWindow) {
            lastVelocityTimeMs = now;
            lagAcceptStreak++;
            lagCancelStreak = 0;
            if (totalMag > LARGE_KB_THRESHOLD) {
                executePartialAccept(pkt, event, totalMag, grounded);
            }
            acceptVelocity(false);
            return;
        }

        boolean isCombo = (now - lastVelocityTimeMs) < COMBO_WINDOW_MS;
        lastVelocityTimeMs = now;
        comboHitIndex = isCombo ? comboHitIndex + 1 : 1;

        Strategy strategy = selectStrategy(isCombo, totalMag, hMag, grounded);

        switch (strategy) {
            case FULL_ACCEPT:
                lagAcceptStreak++;
                lagCancelStreak = 0;
                acceptVelocity(false);
                return;
            case PARTIAL_ACCEPT:
                lagAcceptStreak++;
                lagCancelStreak = 0;
                executePartialAccept(pkt, event, totalMag, grounded);
                acceptVelocity(false);
                return;
            case LAG_CANCEL:
                lagCancelStreak++;
                lagAcceptStreak = 0;
                executeLagCancel(pkt, event, totalMag, hMag, grounded);
                acceptVelocity(true);

                if (lagAbuse.getValue() && grounded && totalMag > 0.3) {
                    lagAbuseActive = true;
                    lagAbuseTicks = 3 + rng.nextInt(3);
                }

                if (lagSmart.getValue() && lagCancelStreak >= 2 + rng.nextInt(2)) {
                    lagForceAcceptWindow = true;
                    lagForceAcceptTicks = 3 + rng.nextInt(5);
                }
                return;
        }
    }

    private void handleLagExplosion(S27PacketExplosion pkt, PacketEvent event) {
        double ex = pkt.func_149149_c(), ey = pkt.func_149144_d(), ez = pkt.func_149147_e();
        if (ex == 0 && ey == 0 && ez == 0) return;

        if (respawnGraceTicks > 0) {
            acceptVelocity(false);
            return;
        }

        double mag = Math.sqrt(ex * ex + ey * ey + ez * ez);
        double hMag = Math.sqrt(ex * ex + ez * ez);
        boolean grounded = mc.thePlayer.onGround;
        Strategy strategy = selectStrategy(false, mag, hMag, grounded);

        switch (strategy) {
            case FULL_ACCEPT:
                lagAcceptStreak++;
                lagCancelStreak = 0;
                acceptVelocity(false);
                return;
            case PARTIAL_ACCEPT:
                event.setCancelled(true);
                double hm = jitter(PARTIAL_H_BASE, 0.10);
                double vm = jitter(PARTIAL_V_BASE, 0.08);
                if (grounded) {
                    hm = Math.max(hm, GROUND_MIN_H);
                    vm = Math.max(vm, GROUND_MIN_V);
                }
                mc.thePlayer.motionX += ex * hm;
                mc.thePlayer.motionY += ey * vm;
                mc.thePlayer.motionZ += ez * hm;
                lagAcceptStreak++;
                lagCancelStreak = 0;
                acceptVelocity(false);
                return;
            case LAG_CANCEL:
                event.setCancelled(true);
                double[] res = computeResidual(ex, ey, ez, mag, grounded);
                residualX = res[0];
                residualY = res[1];
                residualZ = res[2];
                hasResidual = true;
                beginHold(grounded);
                lagCancelStreak++;
                lagAcceptStreak = 0;
                acceptVelocity(true);
                return;
        }
    }

    private Strategy selectStrategy(boolean isCombo, double totalMag,
                                    double hMag, boolean grounded) {

        if (totalMag < SMALL_KB_THRESHOLD) {
            return rng.nextInt(100) < SMALL_KB_ACCEPT_RATE
                    ? Strategy.FULL_ACCEPT
                    : Strategy.PARTIAL_ACCEPT;
        }

        if (holdLockout || inHoldPhase || inReleasePhase) {
            if (totalMag > LARGE_KB_THRESHOLD) {
                return Strategy.PARTIAL_ACCEPT;
            }
            return rng.nextBoolean() ? Strategy.FULL_ACCEPT : Strategy.PARTIAL_ACCEPT;
        }

        if (mustAcceptNext) {
            if (totalMag > LARGE_KB_THRESHOLD) {
                return Strategy.PARTIAL_ACCEPT;
            }
            return rng.nextInt(100) < 40
                    ? Strategy.FULL_ACCEPT
                    : Strategy.PARTIAL_ACCEPT;
        }

        if (lagSmart.getValue()) {
            if (lagCancelStreak >= 3 && rng.nextInt(100) < 60) {
                return totalMag > LARGE_KB_THRESHOLD
                        ? Strategy.PARTIAL_ACCEPT
                        : Strategy.FULL_ACCEPT;
            }

            if (lagAcceptStreak >= 4 && rng.nextInt(100) < 70) {
                return Strategy.LAG_CANCEL;
            }

            if (grounded && mc.thePlayer.hurtTime > 0 && mc.thePlayer.hurtTime <= 2) {
                if (totalMag < LARGE_KB_THRESHOLD && rng.nextInt(100) < 55) {
                    return Strategy.LAG_CANCEL;
                }
            }
        }

        if (isCombo && comboHitIndex > 0) {
            int phase = (comboHitIndex - 1) % 7;
            switch (phase) {
                case 2:
                    return Strategy.PARTIAL_ACCEPT;
                case 5:
                    return totalMag > LARGE_KB_THRESHOLD
                            ? Strategy.PARTIAL_ACCEPT
                            : (rng.nextBoolean()
                            ? Strategy.PARTIAL_ACCEPT
                            : Strategy.FULL_ACCEPT);
            }
        }

        if (cancelHistory.size() >= 8) {
            int cancels = 0;
            for (Boolean b : cancelHistory) if (b) cancels++;
            double rate = (double) cancels / cancelHistory.size();
            if (rate > 0.85) return Strategy.FULL_ACCEPT;
            if (rate > 0.78) return Strategy.PARTIAL_ACCEPT;
        }

        if (grounded) {
            if (totalMag <= LARGE_KB_THRESHOLD) {
                int groundAcceptRate = MEDIUM_KB_ACCEPT_RATE + 15;
                if (rng.nextInt(100) < groundAcceptRate) {
                    return rng.nextInt(100) < 35
                            ? Strategy.FULL_ACCEPT
                            : Strategy.PARTIAL_ACCEPT;
                }
            }
        }

        if (totalMag > LARGE_KB_THRESHOLD) {
            if (rng.nextInt(100) < LARGE_KB_ACCEPT_RATE) {
                return Strategy.PARTIAL_ACCEPT;
            }
            return Strategy.LAG_CANCEL;
        }

        int roll = rng.nextInt(100);
        if (roll < MEDIUM_KB_ACCEPT_RATE) {
            return roll < MEDIUM_KB_ACCEPT_RATE / 3
                    ? Strategy.FULL_ACCEPT
                    : Strategy.PARTIAL_ACCEPT;
        }

        return Strategy.LAG_CANCEL;
    }

    private void executePartialAccept(S12PacketEntityVelocity pkt,
                                      PacketEvent event, double totalMag,
                                      boolean grounded) {
        event.setCancelled(true);
        double vx = pkt.getMotionX() / 8000.0;
        double vy = pkt.getMotionY() / 8000.0;
        double vz = pkt.getMotionZ() / 8000.0;

        double magScale = totalMag > LARGE_KB_THRESHOLD ? 0.75 : 1.0;
        double hm = jitter(PARTIAL_H_BASE * magScale, 0.10);
        double vm = jitter(PARTIAL_V_BASE, 0.08);

        if (grounded) {
            hm = Math.max(hm, GROUND_MIN_H);
            vm = Math.max(vm, GROUND_MIN_V);
        }

        mc.thePlayer.motionX = vx * hm;
        mc.thePlayer.motionY = vy * vm;
        mc.thePlayer.motionZ = vz * hm;
    }

    private void executeLagCancel(S12PacketEntityVelocity pkt,
                                  PacketEvent event, double totalMag,
                                  double hMag, boolean grounded) {
        event.setCancelled(true);

        double vx = pkt.getMotionX() / 8000.0;
        double vy = pkt.getMotionY() / 8000.0;
        double vz = pkt.getMotionZ() / 8000.0;

        double[] res = computeResidual(vx, vy, vz, totalMag, grounded);
        residualX = res[0];
        residualY = res[1];
        residualZ = res[2];
        hasResidual = true;

        if (grounded) {
            lastCancelWasGrounded = true;
            postCancelGroundTicks = 0;
        }

        beginHold(grounded);
    }

    private double[] computeResidual(double vx, double vy, double vz,
                                     double totalMag, boolean grounded) {
        double hMult = RESIDUAL_BASE_H + Math.min(totalMag, 1.5) * RESIDUAL_SCALE;
        hMult += (rng.nextDouble() - 0.5) * 0.025;
        hMult = clamp(hMult, 0.03, 0.14);

        double rx = vx * hMult;
        double rz = vz * hMult;

        double ry;
        if (grounded) {
            if (vy > 0) {
                double minLift = 0.08 + rng.nextDouble() * 0.04;
                ry = Math.max(vy * GROUND_MIN_V * 0.3, minLift);
                ry = Math.min(ry, 0.15);
            } else {
                ry = 0;
            }

            double hResidualMag = Math.sqrt(rx * rx + rz * rz);
            double hOriginalMag = Math.sqrt(vx * vx + vz * vz);
            if (hOriginalMag > 0.01 && hResidualMag < hOriginalMag * GROUND_MIN_H) {
                double scale = (hOriginalMag * GROUND_MIN_H) / hResidualMag;
                rx *= scale;
                rz *= scale;
            }
        } else {
            if (vy > 0) {
                ry = vy * hMult * 0.5;
            } else {
                ry = vy * hMult * 0.3;
            }
        }

        return new double[]{rx, ry, rz};
    }

    private void beginHold(boolean grounded) {
        if (holdLockout || inHoldPhase || inReleasePhase) return;

        int baseDelay = grounded
                ? groundDelay.getValue()
                : airDelay.getValue();

        int jitteredDelay = baseDelay + rng.nextInt(5) - 2;
        jitteredDelay = Math.max(1, Math.min(jitteredDelay, ABSOLUTE_MAX_HOLD_TICKS));

        inHoldPhase = true;
        holdTicksRemaining = jitteredDelay;
        holdTicksElapsed = 0;
        lagSentC03ThisCycle = false;
        lagHoldC03Counter = 0;
    }

    private void emergencyFlush() {
        if (heldPackets.isEmpty()) return;
        if (mc.getNetHandler() == null) {
            heldPackets.clear();
            return;
        }
        currentlyFlushing = true;
        try {
            Packet<?> p;
            while ((p = heldPackets.pollFirst()) != null) {
                mc.getNetHandler().addToSendQueue(p);
            }
        } catch (Exception e) {
            heldPackets.clear();
        } finally {
            currentlyFlushing = false;
            inReleasePhase = false;
            inHoldPhase = false;
        }
    }

    private void acceptVelocity(boolean wasCancelled) {
        cancelHistory.addLast(wasCancelled);
        while (cancelHistory.size() > HISTORY_WINDOW) cancelHistory.pollFirst();

        if (wasCancelled) {
            consecutiveCancels++;
            if (consecutiveCancels >= MAX_CONSECUTIVE_CANCELS) {
                mustAcceptNext = true;
            }
        } else {
            consecutiveCancels = 0;
            mustAcceptNext = false;
        }
    }

    private boolean shouldActivate() {
        if (mc.thePlayer == null) return false;
        if (waterCheck.getValue()
                && (mc.thePlayer.isInWater() || mc.thePlayer.isInLava())) return false;
        if (onlyWhenTargeting.getValue()) {
            return mc.objectMouseOver != null
                    && mc.objectMouseOver.entityHit instanceof EntityLivingBase;
        }
        if (chance.getValue() < 100
                && rng.nextDouble() * 100.0 >= chance.getValue()) return false;
        return true;
    }

    private boolean isHitFromBehind(double vx, double vz) {
        double angle = Math.toDegrees(Math.atan2(vz, vx)) - 90.0;
        float diff = MathHelper.wrapAngleTo180_float(
                (float) angle - mc.thePlayer.rotationYaw);
        return Math.abs(diff) > 90.0f;
    }

    private double jitter(double base, double range) {
        return clamp(base + (rng.nextDouble() - 0.5) * range, 0.03, 1.0);
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : (Math.min(v, max));
    }

    @Override
    public String[] getSuffix() {
        return new String[]{mode.getModeString()};
    }
}