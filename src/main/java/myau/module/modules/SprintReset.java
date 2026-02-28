package myau.module.modules;

import myau.module.ModuleInfo;
import myau.enums.ModuleCategory;
import myau.event.EventTarget;
import myau.events.AttackEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.play.client.C0BPacketEntityAction;

@ModuleInfo(category = ModuleCategory.COMBAT)
public class SprintReset extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    public final BooleanProperty onlyWhileSprinting = new BooleanProperty("OnlyWhileSprinting", true);
    public final BooleanProperty onlyWhileMoving    = new BooleanProperty("OnlyWhileMoving", true);
    public final BooleanProperty intelligent        = new BooleanProperty("Intelligent", false);

    public SprintReset() {
        super("SprintReset", false);
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!isEnabled() || mc.thePlayer == null) return;
        if (!(event.getTarget() instanceof EntityLivingBase)) return;

        EntityLivingBase target = (EntityLivingBase) event.getTarget();
        if (target.isDead) return;

        if (onlyWhileSprinting.getValue() && !mc.thePlayer.isSprinting()) return;
        if (onlyWhileMoving.getValue() && !isMoving()) return;

        if (intelligent.getValue()) {
            double dx = mc.thePlayer.posX - target.posX;
            double dz = mc.thePlayer.posZ - target.posZ;
            float calcYaw = (float)(Math.atan2(dz, dx) * 180.0 / Math.PI - 90.0);
            float diff = Math.abs(wrapTo180(calcYaw - target.rotationYawHead));
            if (diff > 120f) return;
        }

        mc.thePlayer.sendQueue.addToSendQueue(
            new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.STOP_SPRINTING));
        mc.thePlayer.sendQueue.addToSendQueue(
            new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING));
        mc.thePlayer.setSprinting(true);
    }

    private boolean isMoving() {
        return mc.thePlayer.moveForward != 0f || mc.thePlayer.moveStrafing != 0f;
    }

    private static float wrapTo180(float v) {
        while (v > 180f)  v -= 360f;
        while (v < -180f) v += 360f;
        return v;
    }
}
