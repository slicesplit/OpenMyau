package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.*;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.server.S0BPacketAnimation;
import net.minecraft.network.play.server.S12PacketEntityVelocity;

@ModuleInfo(category = ModuleCategory.COMBAT)
public class HitSelect extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    public final PercentProperty chance      = new PercentProperty("Chance", 100);
    public final ModeProperty    mode        = new ModeProperty("Mode", 0, new String[]{"PAUSE", "ACTIVE"});
    public final ModeProperty    preference  = new ModeProperty("Preference", 0,
            new String[]{"KB_REDUCTION", "CRITICAL_HITS"}, () -> mode.getValue() == 1);
    public final BooleanProperty killSkip    = new BooleanProperty("KillSkip", true);

    // ── own state ──────────────────────────────────────────────────────────────
    private int  pauseTicks       = 0;   // PAUSE mode: ticks remaining in pause
    private int  sinceLast        = 0;   // ticks since last attack was SENT
    private int  groundTicks      = 0;   // consecutive ticks on ground
    private int  consecutiveSkips = 0;   // attacks blocked in a row (safety cap)
    private boolean lastHitWasCrit = false;

    // ── incoming velocity tracking ─────────────────────────────────────────────
    // We track the server velocity packet (S12) for ourselves.
    // velAge counts up every tick; a fresh S12 resets it to 0.
    private int velAge = 999;

    // ── opponent motion model ──────────────────────────────────────────────────
    // Lateral-only approach speed to avoid Y noise from jumping.
    // opSwingAge: ticks since the target's arm-swing animation — used as a
    //   proxy for "opponent just attacked" (attack swing = type 0 from S0B).
    private int    opId              = -1;
    private int    opSwingAge        = 999;
    private int    opClientHurtTime  = 0;    // from entity field each tick
    private double opLastLateralDist = 5.0;
    private double opLateralSpeed    = 0.0;  // blocks/tick approaching laterally
    private int    opApproachTicks   = 0;

    public HitSelect() {
        super("HitSelect", false);
    }

    @Override public void onEnabled()  { reset(); }
    @Override public void onDisabled() { reset(); }

    private void reset() {
        pauseTicks = 0; sinceLast = 0; groundTicks = 0; consecutiveSkips = 0;
        lastHitWasCrit = false; velAge = 999;
        opId = -1; opSwingAge = 999; opClientHurtTime = 0;
        opLastLateralDist = 5.0; opLateralSpeed = 0.0; opApproachTicks = 0;
    }

    // ── per-tick bookkeeping ──────────────────────────────────────────────────
    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (event.getType() != EventType.PRE || mc.thePlayer == null) return;

        sinceLast++;
        opSwingAge++;
        velAge++;
        if (pauseTicks > 0) pauseTicks--;

        // ground streak
        groundTicks = mc.thePlayer.onGround ? groundTicks + 1 : 0;

        // opponent motion model
        EntityLivingBase t = getTarget();
        if (t != null) {
            // target changed → reset opponent model
            if (t.getEntityId() != opId) {
                opId              = t.getEntityId();
                opSwingAge        = 999;
                opClientHurtTime  = 0;
                opLastLateralDist = lateralDist(t);
                opLateralSpeed    = 0.0;
                opApproachTicks   = 0;
            }

            opClientHurtTime = t.hurtTime;

            // Lateral-only approach speed (ignore Y so jumping doesn't fake-trigger)
            double ld = lateralDist(t);
            double delta = opLastLateralDist - ld; // positive = closing
            opLateralSpeed    = delta;
            opApproachTicks   = (delta > 0.04) ? opApproachTicks + 1 : 0;
            opLastLateralDist = ld;
        } else {
            opId = -1;
        }
    }

    // ── packet intercept ──────────────────────────────────────────────────────
    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || mc.thePlayer == null) return;

        if (event.getType() == EventType.SEND) {
            if (event.getPacket() instanceof C02PacketUseEntity) {
                C02PacketUseEntity pkt = (C02PacketUseEntity) event.getPacket();
                if (pkt.getAction() != C02PacketUseEntity.Action.ATTACK) return;

                // chance gate: skip HitSelect entirely with (100-chance)% probability
                if (chance.getValue() < 100 && (Math.random() * 100.0) >= chance.getValue()) {
                    onHitSent();
                    return;
                }

                if (shouldBlock()) {
                    event.setCancelled(true);
                    consecutiveSkips++;
                } else {
                    onHitSent();
                }
            }
        }

        if (event.getType() == EventType.RECEIVE) {
            // opponent arm-swing → proxy for opponent attack
            if (event.getPacket() instanceof S0BPacketAnimation) {
                S0BPacketAnimation anim = (S0BPacketAnimation) event.getPacket();
                // type 0 = swing arm (happens for both walking and attacking in 1.8,
                // but it only fires from server when the entity actually swings —
                // i.e. attacks or right-clicks). Close enough for our purposes.
                if (anim.getAnimationType() == 0) {
                    EntityLivingBase t = getTarget();
                    if (t != null && anim.getEntityID() == t.getEntityId()) {
                        opSwingAge = 0;
                    }
                }
            }

            // S12 to US → incoming knockback
            if (event.getPacket() instanceof S12PacketEntityVelocity) {
                S12PacketEntityVelocity vel = (S12PacketEntityVelocity) event.getPacket();
                if (vel.getEntityID() == mc.thePlayer.getEntityId()) {
                    velAge = 0;
                }
            }

        }
    }

    // ── decision entrypoints ──────────────────────────────────────────────────

    private void onHitSent() {
        lastHitWasCrit = isCrit();
        consecutiveSkips = 0;
        sinceLast = 0;
        if (mode.getValue() == 0) {
            pauseTicks = calcPause();
        }
    }

    private boolean shouldBlock() {
        // safety: never skip more than 6 in a row
        if (consecutiveSkips >= 6) return false;
        // kill skip: never delay when target is nearly dead
        if (killSkip.getValue()) {
            EntityLivingBase t = getTarget();
            if (t != null && t.getHealth() / t.getMaxHealth() < 0.18f) return false;
        }
        return mode.getValue() == 0 ? pauseLogic() : activeLogic();
    }

    // ── PAUSE mode ────────────────────────────────────────────────────────────

    private boolean pauseLogic() {
        return pauseTicks > 0;
    }

    private int calcPause() {
        EntityLivingBase t = getTarget();

        // base pause of 2 ticks — just enough to avoid double-hits in 1 hurtTime window
        int p = 2;

        if (t != null) {
            int ht = authorativeHurtTime(t);

            // target still deeply in invuln — no point attacking, extend pause
            if (ht > 8) p = Math.max(p, 4);
            else if (ht > 5) p = Math.max(p, 3);

            // we just did a crit — let motionY settle before next hit
            if (lastHitWasCrit) p = Math.max(p, 3);
        }

        // we're on the ground and moving — W-tap opportunity, let movement reset
        if (mc.thePlayer.onGround && groundTicks >= 2 && mc.thePlayer.moveForward > 0) {
            p = Math.max(p, 3);
        }

        // opponent just swung — they're committed, we can pause safely
        if (opSwingAge <= 3) p = Math.max(p, 3);

        // incoming velocity — we're about to be knocked back, ride it out
        if (velAge <= 3) p = Math.max(p, 4);

        // opponent rushing — closing fast, shorten pause to maintain pressure
        if (opApproachTicks >= 3 && opLateralSpeed > 0.15) p = Math.max(p, 3);

        return Math.min(p, 5);
    }

    // ── ACTIVE mode ───────────────────────────────────────────────────────────

    private boolean activeLogic() {
        EntityLivingBase t = getTarget();
        if (t == null) return false;

        int ht = authorativeHurtTime(t);
        boolean inCrit      = isCrit();
        boolean sprint      = mc.thePlayer.isSprinting();
        boolean onGround    = mc.thePlayer.onGround;
        boolean velFresh    = velAge <= 4;
        boolean opSwinging  = opSwingAge <= 3 && opLastLateralDist < 4.5;
        boolean opRushing   = opApproachTicks >= 2 && opLateralSpeed > 0.08;

        // ── KB_REDUCTION preference ───────────────────────────────────────────
        // Goal: only hit when our attack will apply fresh knockback (server-side
        // the knockback is applied only when hurtTime==0 on the target, but we
        // can also get KB from the enemy's counter-hit). We skip hits when the
        // target is in their invuln window to avoid wasting attacks.
        if (preference.getValue() == 0) {
            // Target is currently vulnerable — ALLOW the hit
            if (ht == 0) return false;

            // Target is deep in invuln (>4 ticks) — block this attack,
            // wait for them to come out of it.
            // Exception: if they're about to swing at us, let the hit through
            // so we can exchange and reset sprinting.
            if (ht > 4 && !opSwinging) return true;

            // ht in [1..4]: borderline — block only if we're not in a
            // dangerous approach scenario (they'd hit us while we wait)
            if (ht > 2 && !opRushing && !velFresh) return true;

            // We took velocity, skip — let their KB resolve before we hit
            if (velFresh && sinceLast < 4) return true;

            // Allow otherwise
            return false;
        }

        // ── CRITICAL_HITS preference ──────────────────────────────────────────
        // Goal: only land hits when we're in a true crit (falling, fallDistance>0).
        // Block attacks at any non-crit moment, but with exceptions for
        // urgency (opponent about to hit us, we're being rushed).
        if (preference.getValue() == 1) {
            // Already in crit — ALLOW
            if (inCrit) return false;

            // Kill pressure override — if sinceLast > 6 ticks, force a hit
            // regardless of crit state (can't miss too many in a row)
            if (sinceLast > 6) return false;

            // Opponent just swung — they're open, take the trade even if not a crit
            if (opSwinging && sinceLast >= 3) return false;

            // On ground and jumping — we're mid-hop, about to go airborne.
            // Block this hit and let us get into the air for the crit.
            if (onGround && groundTicks <= 1 && !mc.thePlayer.isCollidedVertically) return true;

            // Rising (motionY > 0.1): ascending jump, NOT a crit yet — block
            if (!onGround && mc.thePlayer.motionY > 0.1) return true;

            // Falling but fallDistance too small (< 0.1) — not a real crit
            if (!onGround && mc.thePlayer.motionY < 0 && mc.thePlayer.fallDistance < 0.1f) return true;

            // Block by default unless we've waited too long
            return sinceLast < 5;
        }

        return false;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the most reliable hurtTime we have for the target.
     * opClientHurtTime is updated every tick from the entity field —
     * accurate enough since we read it at the top of each UpdateEvent.
     */
    private int authorativeHurtTime(EntityLivingBase t) {
        return opClientHurtTime;
    }

    /**
     * True crit: airborne, actively falling (motionY < 0), has fallen at least
     * a tiny bit (fallDistance > 0), not on ladder, water, or mount.
     */
    private boolean isCrit() {
        if (mc.thePlayer.onGround) return false;
        if (mc.thePlayer.motionY >= 0) return false;
        if (mc.thePlayer.fallDistance <= 0) return false;
        if (mc.thePlayer.isOnLadder()) return false;
        if (mc.thePlayer.isInWater()) return false;
        if (mc.thePlayer.isRiding()) return false;
        return true;
    }

    /**
     * Lateral (XZ-plane only) distance to target — ignores Y so a player
     * jumping at us doesn't fake-trigger the "approaching" detector.
     */
    private double lateralDist(EntityLivingBase t) {
        double dx = mc.thePlayer.posX - t.posX;
        double dz = mc.thePlayer.posZ - t.posZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private EntityLivingBase getTarget() {
        try {
            KillAura ka = (KillAura) myau.Myau.moduleManager.modules.get(KillAura.class);
            if (ka != null && ka.isEnabled()) {
                EntityLivingBase t = ka.getTarget();
                if (t != null) return t;
            }
        } catch (Exception ignored) {}
        if (mc.objectMouseOver != null && mc.objectMouseOver.entityHit instanceof EntityLivingBase)
            return (EntityLivingBase) mc.objectMouseOver.entityHit;
        return null;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{mode.getModeString()};
    }
}
