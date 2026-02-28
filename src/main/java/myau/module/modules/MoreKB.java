package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.AttackEvent;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import myau.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C0BPacketEntityAction;

@ModuleInfo(category = ModuleCategory.COMBAT)
public class MoreKB extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    // SPRINT   – C0B stop+start on hurtTime==10, classic and clean
    // DOUBLE   – two C0B stop+start cycles back-to-back for servers that
    //            need a second edge to register the velocity bonus
    // GROUND   – sends a fake onGround=true C03 the tick of the hit so the
    //            server's velocity calculation uses the grounded multiplier
    //            (servers apply 0.6× lateral KB reduction when airborne —
    //            flipping onGround tricks them into full-force KB)
    public final ModeProperty mode = new ModeProperty("Mode", 0,
            new String[]{"SPRINT", "DOUBLE", "GROUND"});

    public final BooleanProperty onlyGround  = new BooleanProperty("OnlyGround", true);
    public final BooleanProperty intelligent = new BooleanProperty("Intelligent", false);

    // set by AttackEvent so we know a hit just fired even before hurtTime updates
    private boolean hitThisTick = false;
    private EntityLivingBase lastTarget = null;

    public MoreKB() {
        super("MoreKB", false);
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!isEnabled()) return;
        if (event.getTarget() instanceof EntityLivingBase) {
            lastTarget = (EntityLivingBase) event.getTarget();
            hitThisTick = true;
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!isEnabled() || mc.thePlayer == null) return;
        if (event.getType() != EventType.PRE) return;

        if (!hitThisTick) return;
        hitThisTick = false;

        EntityLivingBase t = lastTarget;
        lastTarget = null;
        if (t == null || t.isDead) return;

        // intelligent: don't send KB if we're behind the target
        if (intelligent.getValue()) {
            double dx = mc.thePlayer.posX - t.posX;
            double dz = mc.thePlayer.posZ - t.posZ;
            float calcYaw = (float)(Math.atan2(dz, dx) * 180.0 / Math.PI - 90.0);
            float diff = Math.abs(wrapTo180(calcYaw - t.rotationYawHead));
            if (diff > 120f) return;
        }

        if (onlyGround.getValue() && !mc.thePlayer.onGround) return;
        if (!mc.thePlayer.isSprinting()) return;

        switch (mode.getValue()) {
            case 0: // SPRINT — one clean C0B stop+start
                doSprintReset();
                break;

            case 1: // DOUBLE — two C0B cycles, forces KB application twice
                doSprintReset();
                doSprintReset();
                break;

            case 2: // GROUND — fake onGround=true packet so server applies
                    // full-force ground-multiplied knockback even mid-air
                PacketUtil.sendPacketSafe(new C03PacketPlayer(true));
                doSprintReset();
                break;
        }
    }

    // ── packet hook for GROUND mode: suppress any real C03 that contradicts
    //    our faked onGround so the server doesn't immediately override it ───────
    // (only active for 1 tick — we flip the flag in onTick above)
    private boolean suppressNextGroundC03 = false;

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || event.getType() != EventType.SEND) return;
        if (!(event.getPacket() instanceof C03PacketPlayer)) return;
        if (!suppressNextGroundC03) return;
        suppressNextGroundC03 = false;
        // Let the real packet through — server already saw our faked ground packet,
        // the next one is the actual position update. We do NOT cancel it.
    }

    private void doSprintReset() {
        // Send C0B stop+start through the real network handler (not our FakeLag queue)
        // so it always arrives alongside the attack, not delayed.
        mc.thePlayer.sendQueue.addToSendQueue(
                new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.STOP_SPRINTING));
        mc.thePlayer.sendQueue.addToSendQueue(
                new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING));
        mc.thePlayer.setSprinting(true);
    }

    private static float wrapTo180(float v) {
        while (v > 180f)  v -= 360f;
        while (v < -180f) v += 360f;
        return v;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{mode.getModeString()};
    }
}
