package myau.mixin;

import myau.Myau;
import myau.module.modules.Animations;
import myau.module.modules.KillAura;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.item.EnumAction;
import net.minecraft.item.ItemMap;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.util.MathHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@SideOnly(Side.CLIENT)
@Mixin(value = ItemRenderer.class, priority = 9999)
public abstract class MixinItemRenderer {

    @Shadow private float prevEquippedProgress;
    @Shadow private float equippedProgress;
    @Shadow @Final private Minecraft mc;
    @Shadow private ItemStack itemToRender;

    @Shadow protected abstract void rotateArroundXAndY(float angle, float angleY);
    @Shadow protected abstract void setLightMapFromPlayer(AbstractClientPlayer clientPlayer);
    @Shadow protected abstract void rotateWithPlayerRotations(EntityPlayerSP entityplayerspIn, float partialTicks);
    @Shadow protected abstract void renderItemMap(AbstractClientPlayer clientPlayer, float pitch, float equipmentProgress, float swingProgress);
    @Shadow protected abstract void transformFirstPersonItem(float equipProgress, float swingProgress);
    @Shadow protected abstract void performDrinking(AbstractClientPlayer clientPlayer, float partialTicks);
    @Shadow protected abstract void doBowTransformations(float partialTicks, AbstractClientPlayer clientPlayer);
    @Shadow protected abstract void doItemUsedTransformations(float swingProgress);
    @Shadow protected abstract void renderPlayerArm(AbstractClientPlayer clientPlayer, float equipProgress, float swingProgress);
    @Shadow public abstract void renderItem(net.minecraft.entity.EntityLivingBase entityIn, ItemStack heldStack, ItemCameraTransforms.TransformType transform);

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private Animations getAnim() {
        if (Myau.moduleManager == null) return null;
        Animations a = (Animations) Myau.moduleManager.modules.get(Animations.class);
        return (a != null && a.isEnabled()) ? a : null;
    }

    private boolean isForceBlocking(ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof ItemSword)) return false;
        if (Myau.moduleManager == null) return false;
        KillAura ka = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        return ka != null && ka.isEnabled() && (ka.isPlayerBlocking() || ka.isBlocking());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // itemRenderRotate() — exact FDP port
    // FDP creates a local MSTimer each call; hasTimePassed(1L) is always true
    // on first use (timer.start = 0 < System.currentTimeMillis()).
    // Net effect per call: rotate by delay, then delay += 1 + rotateSpeed,
    // wrap at 360.
    // ─────────────────────────────────────────────────────────────────────────

    private void itemRenderRotate(Animations anim) {
        String rotMode = anim.getItemRotateModeName().toLowerCase();

        // FDP: if mode is "none", sets itemRotate = false and returns
        if (rotMode.equals("none")) {
            // Don't mutate itemRotate here — we just skip the rotate
            return;
        }

        switch (rotMode) {
            case "straight": GlStateManager.rotate(anim.delay, 0f, 1f, 0f); break;
            case "forward":  GlStateManager.rotate(anim.delay, 1f, 1f, 0f); break;
            case "nano":     GlStateManager.rotate(anim.delay, 0f, 0f, 0f); break;
            case "uh":       GlStateManager.rotate(anim.delay, 1f, 0f, 1f); break;
        }

        // FDP: if (rotationTimer.hasTimePassed(1L)) { delay++; delay += itemRotateSpeed; }
        // Timer always passes on first call → always advance
        anim.delay++;
        anim.delay += anim.rotateSpeed.getValue();

        if (anim.delay > 360f) {
            anim.delay = 0f;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // doBlockTransformations() — FDP's Animation.doBlockTransformations()
    // ─────────────────────────────────────────────────────────────────────────

    private void doBlockTransformations(Animations anim) {
        GlStateManager.translate(-0.5f, 0.2f, 0f);
        GlStateManager.rotate(30f,  0f, 1f, 0f);
        GlStateManager.rotate(-80f, 1f, 0f, 0f);
        GlStateManager.rotate(60f,  0f, 1f, 0f);
        if (anim.itemRotate.getValue()) {
            itemRenderRotate(anim);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // transformFirstPersonItem() — FDP's Animation.transformFirstPersonItem()
    // ─────────────────────────────────────────────────────────────────────────

    private void animTransformFirstPersonItem(float equipProgress, float swingProgress, Animations anim) {
        GlStateManager.translate(0.56f, -0.52f, -0.71999997f);
        GlStateManager.translate(0f, equipProgress * -0.6f, 0f);
        GlStateManager.rotate(45f, 0f, 1f, 0f);
        float f  = MathHelper.sin(swingProgress * swingProgress * 3.1415927f);
        float f1 = MathHelper.sin(MathHelper.sqrt_float(swingProgress) * 3.1415927f);
        GlStateManager.rotate(f  * -20f, 0f, 1f, 0f);
        GlStateManager.rotate(f1 * -20f, 0f, 0f, 1f);
        GlStateManager.rotate(f1 * -80f, 1f, 0f, 0f);
        GlStateManager.scale(0.4f, 0.4f, 0.4f);
        if (anim.itemRotate.getValue()) {
            itemRenderRotate(anim);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Animation dispatch — mirrors each Animation subclass's transform()
    // FDP: animation.transform(f1, f, player)
    //   f1 = swingProgress, f = equipProgress (from the Animations.kt reader)
    // ─────────────────────────────────────────────────────────────────────────

    private void applyAnimation(Animations anim, float f1, float f, AbstractClientPlayer player) {
        String mode = anim.getModeName();
        switch (mode) {

            case "OneSeven": {
                // OneSevenAnimation.transform(f1=swing, f=equip)
                animTransformFirstPersonItem(f, f1, anim);   // (equip, swing)
                doBlockTransformations(anim);
                GlStateManager.translate(-0.5f, 0.2f, 0f);
                if (anim.itemRotate.getValue()) itemRenderRotate(anim);
                break;
            }

            case "Old": {
                // OldAnimation.transform(f1=swing, f=equip)
                animTransformFirstPersonItem(f, f1, anim);
                doBlockTransformations(anim);
                if (anim.itemRotate.getValue()) itemRenderRotate(anim);
                break;
            }

            case "Pushdown": {
                // OldPushdownAnimation.transform(f1=swing, f=equip)
                GlStateManager.translate(0.56, -0.52, -0.5);
                GlStateManager.translate(0.0, -(double) f * 0.3, 0.0);
                GlStateManager.rotate(45.5f, 0f, 1f, 0f);
                float var3 = MathHelper.sin(0f);
                float var4 = MathHelper.sin(0f);
                GlStateManager.rotate(var3 * -20f, 0f, 1f, 0f);
                GlStateManager.rotate(var4 * -20f, 0f, 0f, 1f);
                GlStateManager.rotate(var4 * -80f, 1f, 0f, 0f);
                GlStateManager.scale(0.32, 0.32, 0.32);
                float var15 = MathHelper.sin(MathHelper.sqrt_float(f1) * 3.1415927f);
                GlStateManager.rotate(-var15 * 125f / 1.75f, 3.95f, 0.35f, 8f);
                GlStateManager.rotate(-var15 * 35f, 0f, var15 / 100f, -10f);
                GL11.glTranslated(-1.0, 0.6, -0.0);
                GlStateManager.rotate(30f, 0f, 1f, 0f);
                GlStateManager.rotate(-80f, 1f, 0f, 0f);
                GlStateManager.rotate(60f, 0f, 1f, 0f);
                GL11.glTranslated(1.05, 0.35, 0.4);
                GL11.glTranslatef(-1f, 0f, 0f);
                if (anim.itemRotate.getValue()) itemRenderRotate(anim);
                break;
            }

            case "NewPushdown": {
                // NewPushdownAnimation.transform(f1=swing, f=equip)
                double x = anim.handPosX.getValue() - 0.08;
                double y = anim.handPosY.getValue() + 0.12;
                double z = (double) anim.handPosZ.getValue();
                GlStateManager.translate(x, y, z);
                float var9 = MathHelper.sin(MathHelper.sqrt_float(f1) * 3.1415927f);
                GlStateManager.translate(0.0, 0.0, 0.0);
                animTransformFirstPersonItem(f / 1.4f, 0.0f, anim);
                GlStateManager.rotate(-var9 * 65.0f / 2.0f, var9 / 2.0f, 1.0f, 4.0f);
                GlStateManager.rotate(-var9 * 60.0f, 1.0f, var9 / 3.0f, 0.0f);
                doBlockTransformations(anim);
                GlStateManager.scale(1.0, 1.0, 1.0);
                break;
            }

            case "Helium": {
                // HeliumAnimation.transform(f1=swing, f=equip)
                animTransformFirstPersonItem(f, 0.0f, anim);
                float c0 = MathHelper.sin(f1 * f * 3.1415927f);
                float c1 = MathHelper.sin(MathHelper.sqrt_float(f1) * 3.1415927f);
                GlStateManager.rotate(-c1 * 55.0f, 30.0f, c0 / 5.0f, 0.0f);
                doBlockTransformations(anim);
                if (anim.itemRotate.getValue()) itemRenderRotate(anim);
                break;
            }

            case "Argon": {
                // ArgonAnimation.transform(f1=swing, f=equip)
                animTransformFirstPersonItem(f / 2.5f, f1, anim);
                float c2 = MathHelper.sin(MathHelper.sqrt_float(f1) * 3.1415927f);
                float c3 = MathHelper.cos(MathHelper.sqrt_float(f) * 3.1415927f);
                GlStateManager.rotate(c3 * 50.0f / 10.0f, -c2, 0.0f, 100.0f);
                GlStateManager.rotate(c2 * 50.0f, 200.0f, -c2 / 2.0f, 0.0f);
                GlStateManager.translate(0.0, 0.3, 0.0);
                doBlockTransformations(anim);
                if (anim.itemRotate.getValue()) itemRenderRotate(anim);
                break;
            }

            case "Cesium": {
                // CesiumAnimation.transform(f1=swing, f=equip)
                float c4 = MathHelper.sin(MathHelper.sqrt_float(f1) * 3.1415927f);
                animTransformFirstPersonItem(f, 0.0f, anim);
                GlStateManager.rotate(-c4 * 10.0f / 20.0f, c4 / 2.0f, 0.0f, 4.0f);
                GlStateManager.rotate(-c4 * 30.0f, 0.0f, c4 / 3.0f, 0.0f);
                GlStateManager.rotate(-c4 * 10.0f, 1.0f, c4 / 10.0f, 0.0f);
                GlStateManager.translate(0.0, 0.2, 0.0);
                if (anim.itemRotate.getValue()) itemRenderRotate(anim);
                break;
            }

            case "Sulfur": {
                // SulfurAnimation.transform(f1=swing, f=equip)
                float c5 = MathHelper.sin(MathHelper.sqrt_float(f1) * 3.1415927f);
                float c6 = MathHelper.cos(MathHelper.sqrt_float(f1) * 3.1415927f);
                animTransformFirstPersonItem(f, 0.0f, anim);
                GlStateManager.rotate(-c5 * 30.0f, c5 / 10.0f, c6 / 10.0f, 0.0f);
                GlStateManager.translate((double)(c5 / 1.5f), 0.2, 0.0);
                doBlockTransformations(anim);
                if (anim.itemRotate.getValue()) itemRenderRotate(anim);
                break;
            }

            default: {
                // Fallback to OneSeven (FDP's defaultAnimation)
                animTransformFirstPersonItem(f, f1, anim);
                doBlockTransformations(anim);
                GlStateManager.translate(-0.5f, 0.2f, 0f);
                if (anim.itemRotate.getValue()) itemRenderRotate(anim);
                break;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // @Overwrite renderItemInFirstPerson — exact FDP structure ported to Java
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * @author Myau (ported from FDPClient)
     * @reason Full Animations module support with all FDP animation modes
     */
    @Overwrite
    public void renderItemInFirstPerson(float partialTicks) {
        // equipProgress: 0 = fully equipped, 1 = fully unequipped
        float f = 1f - (prevEquippedProgress + (equippedProgress - prevEquippedProgress) * partialTicks);

        EntityPlayerSP abstractclientplayer = mc.thePlayer;
        float f1 = abstractclientplayer.getSwingProgress(partialTicks); // swingProgress
        float f2 = abstractclientplayer.prevRotationPitch
                + (abstractclientplayer.rotationPitch - abstractclientplayer.prevRotationPitch) * partialTicks;
        float f3 = abstractclientplayer.prevRotationYaw
                + (abstractclientplayer.rotationYaw - abstractclientplayer.prevRotationYaw) * partialTicks;

        rotateArroundXAndY(f2, f3);
        setLightMapFromPlayer(abstractclientplayer);
        rotateWithPlayerRotations(abstractclientplayer, partialTicks);
        GlStateManager.enableRescaleNormal();
        GlStateManager.pushMatrix();

        // Hand position / scale offsets from Animations module
        Animations anim = getAnim();
        if (anim != null) {
            GlStateManager.translate(anim.handX.getValue(), anim.handY.getValue(), anim.itemScale.getValue());
            GlStateManager.rotate(anim.handPosX.getValue(), 1f, 0f, 0f);
            GlStateManager.rotate(anim.handPosY.getValue(), 0f, 1f, 0f);
            GlStateManager.rotate(anim.handPosZ.getValue(), 0f, 0f, 1f);
        }

        if (itemToRender != null) {
            boolean forceBlock = isForceBlocking(itemToRender);

            if (itemToRender.getItem() instanceof ItemMap) {
                renderItemMap(abstractclientplayer, f2, f, f1);
            } else if (abstractclientplayer.getItemInUseCount() > 0 || forceBlock) {
                EnumAction enumaction = forceBlock ? EnumAction.BLOCK : itemToRender.getItemUseAction();

                switch (enumaction) {
                    case NONE:
                        transformFirstPersonItem(f, 0f);
                        break;
                    case EAT:
                    case DRINK:
                        performDrinking(abstractclientplayer, partialTicks);
                        transformFirstPersonItem(f, f1);
                        break;
                    case BLOCK:
                        if (anim != null) {
                            // FDP: animation.transform(f1=swingProgress, f=equipProgress, player)
                            applyAnimation(anim, f1, f, abstractclientplayer);
                        } else {
                            // Default (module disabled): use OneSeven / vanilla 1.7 style
                            transformFirstPersonItem(f, f1);
                            // vanilla doBlockTransformations
                            GlStateManager.translate(-0.5f, 0.2f, 0f);
                            GlStateManager.rotate(30f,  0f, 1f, 0f);
                            GlStateManager.rotate(-80f, 1f, 0f, 0f);
                            GlStateManager.rotate(60f,  0f, 1f, 0f);
                            GlStateManager.translate(-0.5f, 0.2f, 0f);
                        }
                        break;
                    case BOW:
                        transformFirstPersonItem(f, f1);
                        doBowTransformations(partialTicks, abstractclientplayer);
                        break;
                }
            } else {
                // Not blocking / not using item
                // OddSwing: skip swing bob when enabled
                if (anim == null || !anim.oddSwing.getValue()) {
                    doItemUsedTransformations(f1);
                }
                transformFirstPersonItem(f, f1);
            }

            renderItem(abstractclientplayer, itemToRender, ItemCameraTransforms.TransformType.FIRST_PERSON);
        } else if (!abstractclientplayer.isInvisible()) {
            renderPlayerArm(abstractclientplayer, f, f1);
        }

        GlStateManager.popMatrix();
        GlStateManager.disableRescaleNormal();
        RenderHelper.disableStandardItemLighting();
    }
}
