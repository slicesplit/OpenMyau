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

@ModuleInfo(category = ModuleCategory.COMBAT)
public class HitSelect extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    public final PercentProperty chance     = new PercentProperty("Chance", 100);
    // PAUSE  – blocks hits only while target is still in their hurtTime invuln window
    // ACTIVE – actively chooses the best moment to hit based on preference
    public final ModeProperty    mode       = new ModeProperty("Mode", 0, new String[]{"PAUSE", "ACTIVE"});
    public final ModeProperty    preference = new ModeProperty("Preference", 0,
            new String[]{"KB_REDUCTION", "CRITICAL_HITS"}, () -> mode.getValue() == 1);
    public final BooleanProperty killSkip   = new BooleanProperty("KillSkip", true);

    // ── state ─────────────────────────────────────────────────────────────────
    private int  sinceLast        = 0;   // ticks since our last attack was sent
    private int  consecutiveSkips = 0;   // safety cap — never block more than N in a row
    private int  velAge           = 999; // ticks since we received S12 velocity (incoming KB)

    public HitSelect() {
        super("HitSelect", false);
    }

    @Override public void onEnabled()  { reset(); }
    @Override public void onDisabled() { reset(); }

    private void reset() {
        sinceLast = 0; consecutiveSkips = 0; velAge = 999;
    }

    // ── per-tick ──────────────────────────────────────────────────────────────
    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (event.getType() != EventType.PRE || mc.thePlayer == null) return;
        sinceLast++;
        velAge++;
    }

    // ── packet intercept ──────────────────────────────────────────────────────
    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || mc.thePlayer == null) return;

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

        if (event.getType() == EventType.RECEIVE) {
            if (event.getPacket() instanceof S12PacketEntityVelocity) {
                S12PacketEntityVelocity vel = (S12PacketEntityVelocity) event.getPacket();
                if (vel.getEntityID() == mc.thePlayer.getEntityId()) {
                    velAge = 0;
                }
            }
        }
    }

    // ── decision ──────────────────────────────────────────────────────────────

    private void onHitSent() {
        sinceLast = 0;
        consecutiveSkips = 0;
    }

    private boolean shouldBlock() {
        // Hard safety: never cancel more than 4 in a row — avoids getting stuck
        if (consecutiveSkips >= 4) return false;

        // Kill pressure: never delay near-dead targets
        if (killSkip.getValue()) {
            EntityLivingBase t = getTarget();
            if (t != null && t.getHealth() / t.getMaxHealth() < 0.2f) return false;
        }

        return mode.getValue() == 0 ? pauseLogic() : activeLogic();
    }

    // ── PAUSE mode ────────────────────────────────────────────────────────────
    // Only blocks while the target is genuinely in their hurtTime invuln window.
    // hurtTime counts DOWN from 10. Server applies damage only at hurtTime==0.
    // We block while hurtTime > 6 (deeply invuln), let through at <=6 because
    // the hit will land by the time packets arrive and hurtTime reaches 0.
    // This gives you ~1 skip per 10-tick window max — feels natural, not CPS-halving.
    private boolean pauseLogic() {
        EntityLivingBase t = getTarget();
        if (t == null) return false;

        int ht = t.hurtTime;

        // Target is not invuln — always let the hit through
        if (ht == 0) return false;

        // Deep invuln (10..7): block — this attack would do nothing server-side
        if (ht >= 7) return true;

        // Mid invuln (6..4): block only if we haven't been waiting long
        // (if sinceLast >= 4 we've already held back enough — force the hit)
        if (ht >= 4 && sinceLast < 4) return true;

        // Low invuln (3..1): let through — close enough that it'll register
        return false;
    }

    // ── ACTIVE mode ───────────────────────────────────────────────────────────

    private boolean activeLogic() {
        EntityLivingBase t = getTarget();
        if (t == null) return false;

        int ht = t.hurtTime;

        // ── KB_REDUCTION ──────────────────────────────────────────────────────
        // Hit only when the target is out of (or nearly out of) invuln so every
        // attack applies fresh knockback. We do NOT block blindly — we only block
        // when the hit would literally be wasted (server would ignore it).
        if (preference.getValue() == 0) {
            // Out of invuln — always allow
            if (ht == 0) return false;
            // Deeply invuln AND we haven't waited too long — skip this one
            if (ht >= 6 && sinceLast < 6) return true;
            // We received fresh velocity (we're being knocked back) — don't trade
            // into it, wait a couple ticks for our position to stabilise
            if (velAge <= 2 && sinceLast < 3) return true;
            // Otherwise allow
            return false;
        }

        // ── CRITICAL_HITS ─────────────────────────────────────────────────────
        // Only land hits during a genuine downward fall (true crit).
        // We block when ascending or at ground level, but with tight timeouts
        // so we never get stuck cancelling every hit.
        if (preference.getValue() == 1) {
            // True crit — always allow
            if (isCrit()) return false;
            // Waited too long (>5 ticks) — force the hit regardless
            if (sinceLast > 5) return false;
            // Actively rising (jump apex not reached) — block, crit incoming
            if (!mc.thePlayer.onGround && mc.thePlayer.motionY > 0.05) return true;
            // On ground, about to jump — block so we can get air
            if (mc.thePlayer.onGround && sinceLast < 2) return true;
            // Falling but fallDistance too small — not a real crit yet
            if (!mc.thePlayer.onGround && mc.thePlayer.motionY < 0
                    && mc.thePlayer.fallDistance < 0.08f) return true;
            // Everything else — allow (don't be greedy)
            return false;
        }

        return false;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private boolean isCrit() {
        if (mc.thePlayer.onGround)          return false;
        if (mc.thePlayer.motionY >= 0)      return false;
        if (mc.thePlayer.fallDistance <= 0) return false;
        if (mc.thePlayer.isOnLadder())      return false;
        if (mc.thePlayer.isInWater())       return false;
        if (mc.thePlayer.isRiding())        return false;
        return true;
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
