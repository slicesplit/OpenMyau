package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.KnockbackEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.PercentProperty;
import myau.util.PlayerUtil;
import myau.util.RotationUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MovingObjectPosition;

import java.util.Random;

public class JumpReset extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private final Random random = new Random();

    public final PercentProperty chance = new PercentProperty("chance", 100, 0, 100, null);
    public final PercentProperty accuracy = new PercentProperty("accuracy", 95, 0, 100, null);
    public final BooleanProperty onlyWhenTargeting = new BooleanProperty("only-when-targeting", true);
    public final BooleanProperty waterCheck = new BooleanProperty("water-check", true);

    private boolean shouldJump = false;
    private int jumpTicks = 0;

    public JumpReset() {
        super("JumpReset", false);
    }

    @Override
    public void onEnabled() {
        shouldJump = false;
        jumpTicks = 0;
    }

    @EventTarget
    public void onKnockback(KnockbackEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }

        if (this.waterCheck.getValue() && PlayerUtil.isInWater()) {
            return;
        }

        if (this.onlyWhenTargeting.getValue() && !isLookingAtAttacker()) {
            return;
        }

        if (random.nextFloat() * 100.0F > this.chance.getValue().floatValue()) {
            return;
        }

        if (mc.thePlayer.onGround) {
            boolean accurate = random.nextFloat() * 100.0F <= this.accuracy.getValue().floatValue();
            
            if (accurate) {
                shouldJump = true;
                jumpTicks = 0;
            } else {
                shouldJump = true;
                jumpTicks = random.nextInt(3) + 2;
            }
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null) {
            return;
        }

        if (shouldJump) {
            if (jumpTicks <= 0) {
                if (mc.thePlayer.onGround) {
                    mc.thePlayer.jump();
                    shouldJump = false;
                }
            } else {
                jumpTicks--;
                if (jumpTicks == 0) {
                    if (mc.thePlayer.onGround) {
                        mc.thePlayer.jump();
                    }
                    shouldJump = false;
                }
            }
        }
    }

    private boolean isLookingAtAttacker() {
        if (mc.objectMouseOver == null || mc.objectMouseOver.typeOfHit != MovingObjectPosition.MovingObjectType.ENTITY) {
            return false;
        }

        Entity pointedEntity = mc.objectMouseOver.entityHit;
        if (!(pointedEntity instanceof EntityLivingBase)) {
            return false;
        }

        double distance = mc.thePlayer.getDistanceToEntity(pointedEntity);
        return distance <= 6.0;
    }
}
