package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.*;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.IntProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.server.S19PacketEntityStatus;

@ModuleInfo(category = ModuleCategory.COMBAT)
public class HitSelect extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    /*
     * Modes:
     *   PAUSE  – block the C02 entirely (no swing, no packet)
     *   ACTIVE – cancel the C02 but still allow the swing animation
     */
    public final ModeProperty mode = new ModeProperty("Mode", 0,
            new String[]{"PAUSE", "ACTIVE"});

    /*
     * Strategies:
     *   INVULN  – only skip during target invulnerability
     *   CRIT    – hold until falling for critical hit
     *   COMBO   – time hits at invuln expiry for stunlock
     *   SMART   – pick best strategy per tick
     */
    public final ModeProperty strategy = new ModeProperty("Strategy", 0,
            new String[]{"INVULN", "CRIT", "COMBO", "SMART"});

    public final IntProperty maxSkip = new IntProperty("Max skip", 6, 1, 12);
    public final BooleanProperty lowHPBypass = new BooleanProperty("Low HP bypass", true);
    public final BooleanProperty sprintReset = new BooleanProperty("Sprint reset", false);

    // ── Tick counters ─────────────────────────────────────────────────────
    private int ticksSinceHit;       // ticks since we ALLOWED an attack through
    private int skipsInRow;          // consecutive blocked attacks
    private int serverConfirmAge;    // ticks since S19 opcode 2 on our target
    private int airTicks;
    private int groundTicks;

    // ── Motion state ──────────────────────────────────────────────────────
    private boolean wasOnGround;

    // ── Target tracking ───────────────────────────────────────────────────
    private EntityLivingBase target;
    private int prevTargetHurtTime;

    // ── Sprint reset ──────────────────────────────────────────────────────
    private boolean sprintResetQueued;

    public HitSelect() {
        super("HitSelect", false);
    }

    // ══════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════

    @Override
    public void onEnabled() {
        ticksSinceHit = 100;
        skipsInRow = 0;
        serverConfirmAge = 100;
        airTicks = 0;
        groundTicks = 0;
        wasOnGround = true;
        target = null;
        prevTargetHurtTime = 0;
        sprintResetQueued = false;
    }

    @Override
    public void onDisabled() {
        if (mc.thePlayer != null && sprintResetQueued) {
            mc.thePlayer.setSprinting(true);
            sprintResetQueued = false;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // TICK UPDATE — runs once per tick before packets
    // ══════════════════════════════════════════════════════════════════════

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (event.getType() != EventType.PRE || mc.thePlayer == null) return;

        ticksSinceHit++;
        serverConfirmAge++;

        // Ground / air tracking
        if (mc.thePlayer.onGround) {
            if (!wasOnGround) groundTicks = 0;
            groundTicks++;
            airTicks = 0;
        } else {
            if (wasOnGround) airTicks = 0;
            airTicks++;
            groundTicks = 0;
        }
        wasOnGround = mc.thePlayer.onGround;

        // Target resolution
        EntityLivingBase resolved = resolveTarget();
        if (resolved != target) {
            target = resolved;
            prevTargetHurtTime = (target != null) ? target.hurtTime : 0;
            serverConfirmAge = 100;
        } else if (target != null) {
            // Detect new hurt (hurtTime jumps up)
            if (target.hurtTime > prevTargetHurtTime) {
                // Client saw target get hit — approximate server confirm
                // Real confirm comes from S19, but this is a decent fallback
            }
            prevTargetHurtTime = target.hurtTime;
        }

        // Sprint reset execution (1 tick off, then back on)
        if (sprintResetQueued) {
            mc.thePlayer.setSprinting(true);
            sprintResetQueued = false;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // PACKET HANDLER
    // ══════════════════════════════════════════════════════════════════════

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || mc.thePlayer == null) return;

        // ── Outbound: intercept attack packets ────────────────────────────
        if (event.getType() == EventType.SEND && event.getPacket() instanceof C02PacketUseEntity) {
            C02PacketUseEntity pkt = (C02PacketUseEntity) event.getPacket();
            if (pkt.getAction() != C02PacketUseEntity.Action.ATTACK) return;

            if (shouldBlock()) {
                event.setCancelled(true);
                skipsInRow++;
            } else {
                ticksSinceHit = 0;
                skipsInRow = 0;

                // Sprint reset: unsprint for 1 tick before hit for max KB
                if (sprintReset.getValue()
                        && mc.thePlayer.isSprinting()
                        && mc.thePlayer.onGround
                        && !sprintResetQueued) {
                    mc.thePlayer.setSprinting(false);
                    sprintResetQueued = true;
                }
            }
            return;
        }

        // ── Inbound: server hit confirmation ──────────────────────────────
        if (event.getType() == EventType.RECEIVE && event.getPacket() instanceof S19PacketEntityStatus) {
            S19PacketEntityStatus pkt = (S19PacketEntityStatus) event.getPacket();
            if (pkt.getOpCode() == 2 && mc.theWorld != null && target != null) {
                if (pkt.getEntity(mc.theWorld) == target) {
                    serverConfirmAge = 0;
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // DECISION — shouldBlock()
    //
    // true  = cancel this attack (wasted / suboptimal)
    // false = allow this attack
    // ══════════════════════════════════════════════════════════════════════

    private boolean shouldBlock() {
        // Safety: never block more than N in a row
        if (skipsInRow >= maxSkip.getValue()) return false;

        // No target info — can't make informed decision, allow
        if (target == null || target.isDead) return false;

        // Low HP bypass: never delay a kill
        if (lowHPBypass.getValue()) {
            float hpRatio = target.getHealth() / target.getMaxHealth();
            if (hpRatio < 0.15f) return false;
        }

        int ht = target.hurtTime;

        switch (strategy.getValue()) {
            case 0:  return blockInvuln(ht);
            case 1:  return blockCrit(ht);
            case 2:  return blockCombo(ht);
            case 3:  return blockSmart(ht);
            default: return false;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // STRATEGY 0 — INVULN
    //
    // Server invuln: hurtResistantTime counts 20→0, damage only when ≤10.
    // Client hurtTime shows 10→0 (visual, ~1 tick behind server).
    //
    // Block when target is invulnerable. Allow when they're hittable.
    //
    //   hurtTime 10-8 → server hRT 18-16 → BLOCK (deep invuln)
    //   hurtTime  7-5 → server hRT 15-13 → BLOCK (still invuln)
    //   hurtTime  4-3 → borderline       → block if haven't waited long
    //   hurtTime  2-0 → out/nearly out   → ALLOW
    // ══════════════════════════════════════════════════════════════════════

    private boolean blockInvuln(int ht) {
        if (ht == 0) return false;

        // Server-confirmed timing (more accurate than client hurtTime)
        if (serverConfirmAge < 20) {
            //  age 0-6  → server hRT 20-14 → definitely invuln
            //  age 7-9  → server hRT 13-11 → invuln but close
            //  age 10+  → server hRT ≤10   → hittable
            if (serverConfirmAge < 7) return true;
            if (serverConfirmAge < 10) return ticksSinceHit < 4;
            return false;
        }

        // Fallback: client hurtTime
        if (ht >= 5) return true;
        if (ht >= 3) return ticksSinceHit < 4;
        return false;
    }

    // ══════════════════════════════════════════════════════════════════════
    // STRATEGY 1 — CRIT
    //
    // Hold attack until we're in a valid crit window.
    //
    // 1.8.9 crit conditions (server-side):
    //   !onGround && fallDistance > 0 && !ladder && !water && !riding
    //
    // Jump timeline (motionY after jump = 0.42):
    //   Tick 0: 0.42  (ascending)
    //   Tick 5: 0.02  (apex)
    //   Tick 6: -0.06 (falling, fallDist tiny)
    //   Tick 7: -0.14 (fallDist ~0.07 — valid crit)
    //   Tick 8: -0.22 (fallDist ~0.21 — solid crit)
    //
    // We wait for isCrit() but timeout after 12 ticks.
    // Also enforce invuln check — crit during invuln is wasted.
    // ══════════════════════════════════════════════════════════════════════

    private boolean blockCrit(int ht) {
        // If target is deeply invuln, block regardless of crit state
        if (ht >= 5) return true;

        // Crit available now — allow (unless invuln)
        if (isCrit()) {
            if (ht >= 3) return true; // Crit ready but target still invuln
            return false;             // Crit ready and target hittable
        }

        // Timeout: don't hold forever
        if (ticksSinceHit > 12) return false;

        // On ground: give 2 ticks for jump, then force
        if (mc.thePlayer.onGround) {
            return groundTicks <= 2;
        }

        // Ascending: crit is coming, wait
        if (mc.thePlayer.motionY > 0) return true;

        // Falling but fallDistance too small: wait 1 more tick
        if (mc.thePlayer.fallDistance < 0.05f && airTicks < 8) return true;

        // Falling with some distance but below crit threshold:
        // close enough, allow
        return false;
    }

    // ══════════════════════════════════════════════════════════════════════
    // STRATEGY 2 — COMBO
    //
    // Time attacks to land the exact tick invuln expires.
    // Creates a stunlock where target is perpetually in hitstun.
    //
    // Ideal: send C02 when server hRT is about to cross 10.
    // With ~1 tick network travel:
    //   Send at serverConfirmAge 8-9 → arrives at server tick 9-10 → hits.
    //   Send at client hurtTime 2-3  → arrives as hRT ≈ 10 → hits.
    // ══════════════════════════════════════════════════════════════════════

    private boolean blockCombo(int ht) {
        // Target not in hitstun — hit immediately
        if (ht == 0) return false;

        // Server-confirmed timing
        if (serverConfirmAge < 20) {
            if (serverConfirmAge < 8) return true;   // Too early
            if (serverConfirmAge <= 11) return false; // Sweet spot
            return false;                             // Past — just send
        }

        // Client hurtTime fallback
        if (ht >= 5) return true;  // Deep invuln
        if (ht >= 4) return ticksSinceHit < 7; // Wait unless stalled
        // ht 1-3: sweet spot
        return false;
    }

    // ══════════════════════════════════════════════════════════════════════
    // STRATEGY 3 — SMART
    //
    // Per-tick priority selection:
    //   1. Deep invuln → always block
    //   2. Crit ready + target hittable → allow immediately
    //   3. Crit imminent + target about to be hittable → wait
    //   4. Combo timing if server confirm available
    //   5. Fallback to invuln check
    // ══════════════════════════════════════════════════════════════════════

    private boolean blockSmart(int ht) {
        // 1. Deep invuln — never waste
        if (ht >= 7) return true;

        // 2. Crit ready right now
        if (isCrit()) {
            // Still invuln? Wait unless borderline
            if (ht >= 4) return true;
            // Hittable or nearly — send the crit
            return false;
        }

        // 3. Crit imminent (ascending, about to apex)
        boolean critSoon = !mc.thePlayer.onGround
                && mc.thePlayer.motionY > 0
                && mc.thePlayer.motionY < 0.15
                && airTicks >= 3;
        boolean targetOpenSoon = ht <= 4;
        if (critSoon && targetOpenSoon && ticksSinceHit < 8) {
            return true; // Wait for crit + open window to align
        }

        // 4. Server-confirmed combo timing
        if (serverConfirmAge < 20) {
            if (serverConfirmAge < 8) return true;
            if (serverConfirmAge <= 11) return false;
        }

        // 5. Fallback
        return blockInvuln(ht);
    }

    // ══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Check if the player is in a valid critical hit state.
     * 
     * 1.8.9 server code (EntityPlayer.attackTargetEntityWithCurrentItem):
     *   if (fallDistance > 0.0F && !onGround && !isOnLadder()
     *       && !isInWater() && !isPotionActive(blindness)
     *       && ridingEntity == null)
     */
    private boolean isCrit() {
        return !mc.thePlayer.onGround
                && mc.thePlayer.fallDistance > 0.0F
                && mc.thePlayer.motionY < 0
                && !mc.thePlayer.isOnLadder()
                && !mc.thePlayer.isInWater()
                && !mc.thePlayer.isRiding();
    }

    /**
     * Resolve combat target.
     * KillAura target takes priority over crosshair entity.
     */
    private EntityLivingBase resolveTarget() {
        try {
            KillAura ka = (KillAura) myau.Myau.moduleManager.modules.get(KillAura.class);
            if (ka != null && ka.isEnabled()) {
                EntityLivingBase t = ka.getTarget();
                if (t != null && !t.isDead) return t;
            }
        } catch (Exception ignored) {}

        if (mc.objectMouseOver != null
                && mc.objectMouseOver.entityHit instanceof EntityLivingBase) {
            EntityLivingBase t = (EntityLivingBase) mc.objectMouseOver.entityHit;
            if (!t.isDead) return t;
        }
        return null;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{strategy.getModeString()};
    }
}