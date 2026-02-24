package myau.mixin;

import myau.Myau;
import myau.event.EventManager;
import myau.events.EntityMovementEvent;
import myau.events.KnockbackEvent;
import myau.events.SafeWalkEvent;
import myau.module.modules.Backtrack;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@SideOnly(Side.CLIENT)
@Mixin(value = {Entity.class}, priority = 9999)
public abstract class MixinEntity {
    @Shadow public World worldObj;
    @Shadow public double posX;
    @Shadow public double posY;
    @Shadow public double posZ;
    @Shadow public double motionX;
    @Shadow public double motionY;
    @Shadow public double motionZ;
    @Shadow public float rotationYaw;
    @Shadow public float rotationPitch;
    @Shadow public float prevRotationYaw;
    @Shadow public float prevRotationPitch;
    @Shadow public boolean onGround;

    @Shadow public AxisAlignedBB boundingBox;

    @Shadow
    public boolean isRiding() {
        return false;
    }

    @Unique
    private static final ThreadLocal<Boolean> myau$inExpandCheck = ThreadLocal.withInitial(() -> false);

    @Inject(method = {"setVelocity"}, at = {@At("HEAD")}, cancellable = true)
    private void setVelocity(double double1, double double2, double double3, CallbackInfo callbackInfo) {
        if (!((Entity) ((Object) this) instanceof EntityPlayerSP)) return;

        try {
            KnockbackEvent event = new KnockbackEvent(double1, double2, double3);
            EventManager.call(event);

            if (event.isCancelled()) {
                callbackInfo.cancel();
                this.motionX = event.getX();
                this.motionY = event.getY();
                this.motionZ = event.getZ();
            }
        } catch (Exception e) {
            // Let vanilla setVelocity handle it normally — never get stuck
        }
    }

    @Inject(method = {"setAngles"}, at = {@At("HEAD")}, cancellable = true)
    private void setAngles(CallbackInfo callbackInfo) {
        if ((Entity) ((Object) this) instanceof EntityPlayerSP
                && Myau.rotationManager != null
                && Myau.rotationManager.isRotated()) {
            callbackInfo.cancel();
        }
    }

    @ModifyVariable(method = {"moveEntity"}, ordinal = 0, at = @At("STORE"), name = {"flag"})
    private boolean moveEntity(boolean boolean1) {
        if ((Entity) ((Object) this) instanceof EntityPlayerSP) {
            SafeWalkEvent event = new SafeWalkEvent(boolean1);
            EventManager.call(event);
            return event.isSafeWalk();
        } else {
            return boolean1;
        }
    }

    @Inject(method = {"moveEntity"}, at = @At("RETURN"))
    private void onMoveEntityReturn(double dx, double dy, double dz, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self instanceof EntityPlayerSP) return;
        EventManager.call(new EntityMovementEvent(self));
    }

    @Inject(method = "getEntityBoundingBox", at = @At("RETURN"), cancellable = true)
    private void onGetBoundingBox(CallbackInfoReturnable<AxisAlignedBB> cir) {
        if (!Backtrack.isRaytracing) return;
        if (myau$inExpandCheck.get()) return;
        if (Myau.moduleManager == null) return;

        Backtrack bt = (Backtrack) Myau.moduleManager.modules.get(Backtrack.class);
        if (bt == null || !bt.isEnabled()) return;

        AxisAlignedBB original = cir.getReturnValue();
        if (original == null) return;

        myau$inExpandCheck.set(true);
        try {
            AxisAlignedBB expanded = bt.getExpandedHitbox((Entity) (Object) this, original);
            if (expanded != null) {
                cir.setReturnValue(expanded);
            }
        } finally {
            myau$inExpandCheck.set(false);
        }
    }
}