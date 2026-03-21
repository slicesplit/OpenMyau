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
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.network.play.server.S0BPacketAnimation;

@ModuleInfo(category = ModuleCategory.COMBAT)
public class HitSelect extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    public final PercentProperty chance          = new PercentProperty("Chance", 100);
    public final ModeProperty    mode            = new ModeProperty("Mode", 0,
            new String[]{"PAUSE", "ACTIVE"});
    public final ModeProperty    preference      = new ModeProperty("Preference", 0,
            new String[]{"KB_REDUCTION", "CRITICAL_HITS", "COMBO_LOCK", "FULL_BLATANT"},
            () -> mode.getValue() == 1);
    public final BooleanProperty killSkip        = new BooleanProperty("KillSkip", true);
    public final BooleanProperty forceCritOnJump = new BooleanProperty("ForceCritOnJump", true,
            () -> mode.getValue() == 1 && (preference.getValue() == 1 || preference.getValue() == 3));
    public final BooleanProperty antiWaste       = new BooleanProperty("AntiWaste", true);
    public final BooleanProperty velocitySync    = new BooleanProperty("VelocitySync", true);

    // ── tick state ────────────────────────────────────────────────────────────
    private int sinceLast          = 0;
    private int consecutiveSkips   = 0;
    private int velAge             = 999;
    private int targetHurtAge     = 999;
    private int playerHurtAge     = 999;

    // ── server-confirmed state ────────────────────────────────────────────────
    private int serverHurtTick     = 0;
    private int serverConfirmAge   = 999;
    private boolean lastHitConfirmed = false;
    private int confirmedHitsInRow  = 0;
    private int wastedHitsInRow     = 0;

    // ── motion tracking ───────────────────────────────────────────────────────
    private double prevMotionY      = 0.0;
    private double prevPrevMotionY  = 0.0;
    private int airTicks            = 0;
    private int groundTicks         = 0;
    private boolean wasOnGround     = true;
    private double peakY            = 0.0;
    private double jumpStartY       = 0.0;
    private boolean ascending       = false;
    private boolean justPassedApex  = false;

    // ── velocity tracking ─────────────────────────────────────────────────────
    private double lastVelX         = 0.0;
    private double lastVelY         = 0.0;
    private double lastVelZ         = 0.0;
    private double incomingKBStrength = 0.0;

    // ── target tracking ───────────────────────────────────────────────────────
    private EntityLivingBase lastTarget = null;
    private double lastTargetHealth     = 0.0;
    private int targetInvulnWindow      = 0;
    private int ticksSinceTargetDamaged = 999;

    // ── combo state ───────────────────────────────────────────────────────────
    private int comboLength         = 0;
    private int comboBreakTimer     = 0;
    private boolean inCombo         = false;

    public HitSelect() {
        super("HitSelect", false);
    }

    @Override
    public void onEnabled() {
        reset();
    }

    @Override
    public void onDisabled() {
        reset();
    }

    private void reset() {
        sinceLast = 0;
        consecutiveSkips = 0;
        velAge = 999;
        targetHurtAge = 999;
        playerHurtAge = 999;
        serverHurtTick = 0;
        serverConfirmAge = 999;
        lastHitConfirmed = false;
        confirmedHitsInRow = 0;
        wastedHitsInRow = 0;
        prevMotionY = 0.0;
        prevPrevMotionY = 0.0;
        airTicks = 0;
        groundTicks = 0;
        wasOnGround = true;
        peakY = 0.0;
        jumpStartY = 0.0;
        ascending = false;
        justPassedApex = false;
        lastVelX = 0.0;
        lastVelY = 0.0;
        lastVelZ = 0.0;
        incomingKBStrength = 0.0;
        lastTarget = null;
        lastTargetHealth = 0.0;
        targetInvulnWindow = 0;
        ticksSinceTargetDamaged = 999;
        comboLength = 0;
        comboBreakTimer = 0;
        inCombo = false;
    }

    // ── per-tick state machine ────────────────────────────────────────────────
    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (event.getType() != EventType.PRE || mc.thePlayer == null) return;

        sinceLast++;
        velAge++;
        targetHurtAge++;
        playerHurtAge++;
        serverConfirmAge++;
        ticksSinceTargetDamaged++;

        // ── motion analysis ───────────────────────────────────────────────────
        prevPrevMotionY = prevMotionY;
        prevMotionY = mc.thePlayer.motionY;

        if (mc.thePlayer.onGround) {
            if (!wasOnGround) {
                groundTicks = 0;
            }
            groundTicks++;
            airTicks = 0;
            ascending = false;
            justPassedApex = false;
        } else {
            if (wasOnGround) {
                airTicks = 0;
                jumpStartY = mc.thePlayer.posY;
                peakY = mc.thePlayer.posY;
                ascending = true;
            }
            airTicks++;

            if (mc.thePlayer.posY > peakY) {
                peakY = mc.thePlayer.posY;
            }

            boolean wasAscending = ascending;
            ascending = mc.thePlayer.motionY > 0.0;
            justPassedApex = wasAscending && !ascending;
        }
        wasOnGround = mc.thePlayer.onGround;

        // ── player hurt tracking ──────────────────────────────────────────────
        if (mc.thePlayer.hurtTime == mc.thePlayer.maxHurtTime && mc.thePlayer.maxHurtTime > 0) {
            playerHurtAge = 0;
        }

        // ── target state tracking ─────────────────────────────────────────────
        EntityLivingBase target = getTarget();
        if (target != null) {
            if (target != lastTarget) {
                lastTarget = target;
                lastTargetHealth = target.getHealth();
                comboLength = 0;
                comboBreakTimer = 0;
                inCombo = false;
                confirmedHitsInRow = 0;
                wastedHitsInRow = 0;
                targetInvulnWindow = 0;
                ticksSinceTargetDamaged = 999;
            }

            if (target.hurtTime == target.maxHurtTime && target.maxHurtTime > 0) {
                targetHurtAge = 0;
            }

            // detect damage dealt
            double currentHealth = target.getHealth();
            if (currentHealth < lastTargetHealth) {
                ticksSinceTargetDamaged = 0;
                confirmedHitsInRow++;
                wastedHitsInRow = 0;
                lastHitConfirmed = true;

                comboLength++;
                comboBreakTimer = 0;
                inCombo = comboLength >= 2;
            }
            lastTargetHealth = currentHealth;

            targetInvulnWindow = target.hurtTime;
        } else {
            lastTarget = null;
            inCombo = false;
            comboLength = 0;
        }

        // ── combo decay ───────────────────────────────────────────────────────
        if (inCombo) {
            comboBreakTimer++;
            if (comboBreakTimer > 30) {
                inCombo = false;
                comboLength = 0;
                comboBreakTimer = 0;
            }
        }
    }

    // ── packet intercept ──────────────────────────────────────────────────────
    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || mc.thePlayer == null) return;

        // ── OUTBOUND: intercept our attack packets ────────────────────────────
        if (event.getType() == EventType.SEND) {
            if (!(event.getPacket() instanceof C02PacketUseEntity)) return;
            C02PacketUseEntity pkt = (C02PacketUseEntity) event.getPacket();
            if (pkt.getAction() != C02PacketUseEntity.Action.ATTACK) return;

            // chance gate
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

        // ── INBOUND: track server responses ───────────────────────────────────
        if (event.getType() == EventType.RECEIVE) {
            // velocity packet — incoming KB
            if (event.getPacket() instanceof S12PacketEntityVelocity) {
                S12PacketEntityVelocity vel = (S12PacketEntityVelocity) event.getPacket();
                if (vel.getEntityID() == mc.thePlayer.getEntityId()) {
                    velAge = 0;
                    lastVelX = vel.getMotionX() / 8000.0;
                    lastVelY = vel.getMotionY() / 8000.0;
                    lastVelZ = vel.getMotionZ() / 8000.0;
                    incomingKBStrength = Math.sqrt(lastVelX * lastVelX + lastVelZ * lastVelZ);
                }
            }

            // entity status — damage confirm (entity ID 2 = hurt animation)
            if (event.getPacket() instanceof S19PacketEntityStatus) {
                S19PacketEntityStatus status = (S19PacketEntityStatus) event.getPacket();
                if (status.getOpCode() == 2) {
                    EntityLivingBase target = getTarget();
                    if (target != null && status.getEntity(mc.theWorld) == target) {
                        serverConfirmAge = 0;
                        serverHurtTick = 10; // server hurtTime starts at 10
                        lastHitConfirmed = true;
                    }
                }
            }

            // entity animation — swing confirm for hit detection
            if (event.getPacket() instanceof S0BPacketAnimation) {
                S0BPacketAnimation anim = (S0BPacketAnimation) event.getPacket();
                EntityLivingBase target = getTarget();
                if (target != null && anim.getEntityID() == target.getEntityId()) {
                    // target swung at us — we might be about to take KB
                    if (velocitySync.getValue()) {
                        // prepare for incoming velocity
                    }
                }
            }
        }
    }

    // ── hit sent callback ─────────────────────────────────────────────────────
    private void onHitSent() {
        sinceLast = 0;
        consecutiveSkips = 0;
        comboBreakTimer = 0;
    }

    // ── master decision ───────────────────────────────────────────────────────
    private boolean shouldBlock() {
        // HARD SAFETY: never cancel more than 5 in a row
        if (consecutiveSkips >= 5) return false;

        // KILL PRESSURE: don't delay killing blows
        if (killSkip.getValue()) {
            EntityLivingBase t = getTarget();
            if (t != null) {
                float healthPercent = t.getHealth() / t.getMaxHealth();
                // if one hit can probably kill, never block
                if (healthPercent < 0.15f) return false;
                // if target is very low and we're in combo, don't risk losing it
                if (healthPercent < 0.25f && inCombo && comboLength >= 3) return false;
            }
        }

        // ANTI-WASTE: if we've wasted 3+ hits in a row, stop blocking
        if (antiWaste.getValue() && wastedHitsInRow >= 3) return false;

        return mode.getValue() == 0 ? pauseLogic() : activeLogic();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  PAUSE MODE — server-accurate invulnerability window exploitation
    // ══════════════════════════════════════════════════════════════════════════
    //
    //  Server mechanics (1.8.9):
    //    - On hit: target.hurtResistantTime = 20 (ticks of partial invuln)
    //    - Damage only applies when hurtResistantTime <= 10
    //    - hurtTime counts DOWN from 10 to 0 (visual only on client)
    //    - Client hurtTime is unreliable due to interpolation
    //
    //  Strategy: block hits that would land during hurtResistantTime > 10
    //  (the first 10 ticks after a confirmed hit where server ignores damage)
    //
    private boolean pauseLogic() {
        EntityLivingBase t = getTarget();
        if (t == null) return false;

        int ht = t.hurtTime;

        // ── no invuln — always allow ──────────────────────────────────────────
        if (ht == 0) return false;

        // ── use server-confirmed timing if available ──────────────────────────
        if (serverConfirmAge < 20) {
            int serverTicksElapsed = serverConfirmAge;

            // Server invuln window: ticks 0-9 after hit = fully invuln
            // ticks 10-19 = hurtResistantTime ticking down but damage can apply
            if (serverTicksElapsed < 8) {
                // definitely still invuln — block this hit
                return true;
            }
            if (serverTicksElapsed >= 8 && serverTicksElapsed <= 10) {
                // borderline — let through, packet travel time means it'll land at ~10
                return false;
            }
            // past invuln window — always allow
            return false;
        }

        // ── fallback: client-side hurtTime estimation ─────────────────────────
        // hurtTime 10 = just got hit (most invuln)
        // hurtTime 1  = about to exit invuln
        if (ht >= 8) return true;   // deep invuln — guaranteed waste
        if (ht >= 5) {
            // mid invuln — block unless we've been waiting too long
            return sinceLast < 5;
        }
        // ht 1-4: close to exiting — allow the hit
        return false;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ACTIVE MODE — preference-based tick-perfect attack scheduling
    // ══════════════════════════════════════════════════════════════════════════

    private boolean activeLogic() {
        EntityLivingBase t = getTarget();
        if (t == null) return false;

        int ht = t.hurtTime;

        switch (preference.getValue()) {
            case 0:  return kbReductionLogic(t, ht);
            case 1:  return criticalHitLogic(t, ht);
            case 2:  return comboLockLogic(t, ht);
            case 3:  return fullBlatantLogic(t, ht);
            default: return false;
        }
    }

    // ── KB REDUCTION ──────────────────────────────────────────────────────────
    //  Goal: maximize knockback dealt per hit by ensuring every hit actually
    //  applies damage (not wasted on invuln frames) and hitting when we have
    //  positional advantage (on ground, not mid-KB).
    //
    //  Mechanics:
    //    - KB is calculated at damage application time
    //    - Sprinting adds extra KB (+1 in look direction)
    //    - Hitting during target's invuln = 0 KB applied
    //    - Our own velocity doesn't affect KB dealt (common myth)
    //
    private boolean kbReductionLogic(EntityLivingBase t, int ht) {
        // target out of invuln — perfect time, allow immediately
        if (ht == 0) return false;

        // target deeply invuln — hit would be completely wasted
        if (ht >= 7) return true;

        // we just received KB — wait for position to stabilize
        // hitting while being knocked back doesn't reduce our KB, but
        // our aim might be off and we waste the hit
        if (velocitySync.getValue() && velAge <= 2 && sinceLast < 4) return true;

        // mid invuln but we've waited a while — force through
        if (ht >= 4 && sinceLast >= 6) return false;

        // mid invuln, haven't waited long — keep blocking
        if (ht >= 4 && sinceLast < 4) return true;

        // low invuln (1-3) — close enough, allow
        return false;
    }

    // ── CRITICAL HITS ─────────────────────────────────────────────────────────
    //  Goal: only release attacks during valid critical hit windows.
    //
    //  Server crit conditions (ALL must be true):
    //    1. player.fallDistance > 0.0
    //    2. !player.onGround
    //    3. !player.isOnLadder()
    //    4. !player.isInWater()
    //    5. !player.isRiding()
    //    6. !player.isPotionActive(Potion.blindness)
    //    7. player.motionY < 0 (falling, not checked by server but
    //       fallDistance > 0 implies this after apex)
    //
    //  Tick-perfect timing:
    //    Jump gives motionY = 0.42
    //    Gravity = -0.0784/tick (approximately)
    //    Apex at tick ~5-6 after jump
    //    Best crit window: ticks 6-11 (falling, fallDistance accumulating)
    //    Perfect crit: tick 7-8 (enough fallDistance for particle + max damage)
    //
    private boolean criticalHitLogic(EntityLivingBase t, int ht) {
        // already in perfect crit state — never block
        if (isPerfectCrit()) return false;

        // target invuln — block regardless (don't waste a crit on invuln)
        if (antiWaste.getValue() && ht >= 6) return true;

        // hard timeout — never block more than 8 ticks (prevents getting stuck)
        if (sinceLast > 8) return false;

        // ── ascending phase — block, crit incoming ────────────────────────────
        if (!mc.thePlayer.onGround && mc.thePlayer.motionY > 0.0) {
            // definitely still going up — wait for apex
            return true;
        }

        // ── just passed apex — block 1 more tick for fallDistance buildup ─────
        if (justPassedApex) return true;

        // ── on ground — block briefly to allow jump ───────────────────────────
        if (mc.thePlayer.onGround) {
            // just landed or standing — give 2 ticks to initiate jump
            if (sinceLast <= 2) return true;
            // been on ground too long — force the hit, not worth waiting
            return false;
        }

        // ── falling but insufficient fallDistance ─────────────────────────────
        if (!mc.thePlayer.onGround && mc.thePlayer.motionY <= 0.0) {
            if (mc.thePlayer.fallDistance < 0.1f && airTicks < 4) {
                // barely falling — wait one more tick
                return true;
            }
            // good fallDistance or been in air long enough — allow
            return false;
        }

        return false;
    }

    // ── COMBO LOCK ────────────────────────────────────────────────────────────
    //  Goal: maximize combo length by hitting at the exact tick the target's
    //  invulnerability expires. This creates a "stunlock" effect where the
    //  target is perpetually in hitstun.
    //
    //  Timing math:
    //    - hurtTime counts 10 -> 0 on client (1 per tick)
    //    - hurtResistantTime counts 20 -> 0 on server (1 per tick)
    //    - Damage applies when hurtResistantTime <= 10
    //    - So optimal re-hit is at hurtTime 1-2 (server hurtResistantTime ~11-12)
    //    - Account for ~1 tick packet delay: hit at client hurtTime 2-3
    //
    private boolean comboLockLogic(EntityLivingBase t, int ht) {
        // not in invuln — always allow
        if (ht == 0) {
            // if we're in an active combo and target just exited invuln, HIT NOW
            if (inCombo && ticksSinceTargetDamaged <= 12) return false;
            return false;
        }

        // deep invuln — definitely block
        if (ht >= 8) return true;

        // mid invuln — block unless timeout
        if (ht >= 4) {
            return sinceLast < 7;
        }

        // sweet spot: hurtTime 1-3
        // This is the tick-perfect window for combo continuation
        if (ht <= 3 && ht >= 1) {
            // perfect re-hit timing — allow
            // at ht=2, by the time packet reaches server, hurtResistantTime will be ~10
            // which is exactly when damage starts applying again
            if (ht == 2 || ht == 1) return false;
            // ht=3: slightly early but acceptable
            if (sinceLast >= 6) return false;
            return true;
        }

        return false;
    }

    // ── FULL BLATANT ──────────────────────────────────────────────────────────
    //  Combines everything: waits for perfect crit during falling phase,
    //  times hits to land exactly as invuln expires, maximizes KB,
    //  and maintains combo lock. No subtlety. Pure optimization.
    //
    private boolean fullBlatantLogic(EntityLivingBase t, int ht) {
        // ── kill threshold — instant release ──────────────────────────────────
        if (t.getHealth() / t.getMaxHealth() < 0.2f) return false;

        // ── hard timeout ──────────────────────────────────────────────────────
        if (sinceLast > 10) return false;

        // ── target deeply invuln — always block ──────────────────────────────
        if (ht >= 7) return true;

        // ── crit window check ─────────────────────────────────────────────────
        boolean critReady = isPerfectCrit();
        boolean canCritSoon = !mc.thePlayer.onGround && mc.thePlayer.motionY > 0.0 && airTicks < 8;

        // if we can crit within a few ticks, wait for it
        if (!critReady && canCritSoon && sinceLast < 6) return true;

        // ── combo timing ──────────────────────────────────────────────────────
        if (ht > 0) {
            // wait for sweet spot
            if (ht >= 4 && sinceLast < 6) return true;

            // sweet spot with crit
            if (ht <= 3 && critReady) return false;

            // sweet spot without crit — still allow if waited enough
            if (ht <= 2) return false;

            // ht=3 without crit — wait one more tick if possible
            if (ht == 3 && sinceLast < 5) return true;
        }

        // ── no invuln, check crit ─────────────────────────────────────────────
        if (ht == 0) {
            // ascending — wait for crit
            if (!mc.thePlayer.onGround && mc.thePlayer.motionY > 0.05 && sinceLast < 5) return true;

            // perfect crit available — allow
            if (critReady) return false;

            // on ground, been waiting — just hit
            if (mc.thePlayer.onGround && sinceLast >= 3) return false;

            // just jumped — wait for fall
            if (!mc.thePlayer.onGround && airTicks < 3 && sinceLast < 4) return true;

            // falling but no fallDistance yet
            if (!mc.thePlayer.onGround && mc.thePlayer.motionY <= 0 && mc.thePlayer.fallDistance < 0.05f
                    && sinceLast < 5) return true;

            // been waiting long enough — force it
            return false;
        }

        return false;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Checks all server-side critical hit conditions with maximum precision.
     * Returns true ONLY when a hit right now would guaranteed crit.
     */
    private boolean isPerfectCrit() {
        if (mc.thePlayer.onGround)             return false;
        if (mc.thePlayer.motionY >= 0.0)       return false;
        if (mc.thePlayer.fallDistance <= 0.0f)  return false;
        if (mc.thePlayer.isOnLadder())         return false;
        if (mc.thePlayer.isInWater())          return false;
        if (mc.thePlayer.isRiding())           return false;
        if (mc.thePlayer.isSprinting() && mc.thePlayer.motionY > -0.08) return false;
        // ensure enough fallDistance for meaningful crit (not just 0.0001)
        if (mc.thePlayer.fallDistance < 0.0625f) return false;
        return true;
    }

    /**
     * Resolves current combat target from KillAura or crosshair.
     */
    private EntityLivingBase getTarget() {
        try {
            KillAura ka = (KillAura) myau.Myau.moduleManager.modules.get(KillAura.class);
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

    @Override
    public String[] getSuffix() {
        String modeStr = mode.getModeString();
        if (mode.getValue() == 1) {
            modeStr += " " + preference.getModeString();
        }
        if (inCombo) {
            modeStr += " [" + comboLength + "]";
        }
        return new String[]{modeStr};
    }
}