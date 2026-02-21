package myau.mixin;

import myau.Myau;
import myau.module.modules.Animations;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@SideOnly(Side.CLIENT)
@Mixin(value = {ItemRenderer.class}, priority = 9999)
public abstract class MixinItemRenderer {

    @Shadow @Final private Minecraft mc;
    @Shadow private ItemStack itemToRender;

    /**
     * Inject custom blocking transformations
     * This replaces vanilla doBlockTransformations when Animations is enabled
     */
    @Inject(method = "doBlockTransformations", at = @At("HEAD"), cancellable = true)
    private void onDoBlockTransformations(CallbackInfo ci) {
        if (Myau.moduleManager == null || Myau.moduleManager.modules == null || Myau.moduleManager.modules.isEmpty()) {
            return;
        }

        try {
            Animations animations = (Animations) Myau.moduleManager.modules.get(Animations.class);
            if (animations == null || !animations.isEnabled()) {
                return;
            }

            // Vanilla mode - don't modify
            if (animations.style.getValue() == 18) {
                return;
            }

            EntityPlayerSP player = mc.thePlayer;
            if (player == null) {
                return;
            }

            // Get animation values
            float partialTicks = ((IAccessorMinecraft) mc).getTimer().renderPartialTicks;
            float swingProgress = player.getSwingProgress(partialTicks);
            float time = (System.currentTimeMillis() % 10000) / 1000f;
            float speed = animations.speed.getValue();

            // Apply base blocking position (vanilla does this)
            GlStateManager.translate(-0.5F, 0.2F, 0.0F);
            GlStateManager.rotate(30.0F, 0.0F, 1.0F, 0.0F);
            GlStateManager.rotate(-80.0F, 1.0F, 0.0F, 0.0F);
            GlStateManager.rotate(60.0F, 0.0F, 1.0F, 0.0F);

            // Apply custom animation on top
            applyAnimation(animations.style.getValue(), swingProgress, time, speed);

            ci.cancel();
        } catch (Exception e) {
            // Silently ignore during initialization
        }
    }

    /**
     * Apply swing animation modifications for non-blocking attacks
     */
    @Inject(method = "transformFirstPersonItem", at = @At("RETURN"))
    private void onTransformFirstPersonItem(float equipProgress, float swingProgress, CallbackInfo ci) {
        if (Myau.moduleManager == null || Myau.moduleManager.modules == null || Myau.moduleManager.modules.isEmpty()) {
            return;
        }

        try {
            Animations animations = (Animations) Myau.moduleManager.modules.get(Animations.class);
            if (animations == null || !animations.isEnabled()) {
                return;
            }

            EntityPlayerSP player = mc.thePlayer;
            if (player == null || player.isUsingItem()) {
                return;
            }

            // Vanilla mode - don't modify swings
            if (animations.style.getValue() == 18) {
                return;
            }

            float time = (System.currentTimeMillis() % 10000) / 1000f;
            float speed = animations.speed.getValue();

            applySwingAnimation(animations.style.getValue(), swingProgress, time, speed);
        } catch (Exception e) {
            // Silently ignore
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  BLOCKING ANIMATIONS
    // ═══════════════════════════════════════════════════════════════════

    private void applyAnimation(int mode, float swingProgress, float time, float speed) {
        float swing = MathHelper.sin(swingProgress * (float) Math.PI);
        float swingSqrt = MathHelper.sin(MathHelper.sqrt_float(swingProgress) * (float) Math.PI);
        float t = time * speed;

        switch (mode) {
            case 0: // 1.7
                GlStateManager.translate(0.0f, 0.2f, 0.0f);
                GlStateManager.rotate(-swingSqrt * 40.0f, 1.0f, 0.0f, 0.0f);
                break;

            case 1: // Smooth
                GlStateManager.translate(0.0f, swing * 0.1f, 0.0f);
                GlStateManager.rotate(-swingSqrt * 30.0f, 1.0f, 0.0f, 0.0f);
                GlStateManager.rotate(-swing * 20.0f, 0.0f, 1.0f, 0.0f);
                break;

            case 2: // Exhibition
                GlStateManager.translate(-0.05f, 0.2f, 0.2f);
                GlStateManager.rotate(-swingSqrt * 60.0f, 1.0f, 0.0f, 0.0f);
                GlStateManager.rotate(30.0f, 0.0f, 1.0f, 0.0f);
                break;

            case 3: // Sigma
                float wave = MathHelper.sin(t * 3.0f) * 0.1f;
                GlStateManager.translate(wave, 0.1f, 0.0f);
                GlStateManager.rotate(-swingSqrt * 55.0f + wave * 20, 1.0f, 0.0f, 0.0f);
                GlStateManager.rotate(-50.0f, 0.0f, 1.0f, 0.0f);
                break;

            case 4: // Push
                GlStateManager.translate(0.0f, 0.0f, -swingSqrt * 0.4f);
                GlStateManager.rotate(-swingSqrt * 35.0f, 1.0f, 0.0f, 0.0f);
                break;

            case 5: // Slide
                float slide = MathHelper.sin(t * 2.0f) * 0.15f;
                GlStateManager.translate(slide, 0.1f, 0.0f);
                GlStateManager.rotate(-swing * 25.0f, 1.0f, 0.0f, 0.0f);
                break;

            case 6: // Spin
                GlStateManager.translate(0.0f, 0.15f, -0.1f);
                GlStateManager.rotate(t * 360.0f, 0.0f, 1.0f, 0.0f);
                GlStateManager.rotate(-60.0f, 1.0f, 0.0f, 0.0f);
                break;

            case 7: // Swing
                GlStateManager.translate(-swing * 0.2f, swing * 0.15f, 0.0f);
                GlStateManager.rotate(-swingSqrt * 50.0f, 1.0f, 0.0f, 0.0f);
                GlStateManager.rotate(swing * 40.0f, 0.0f, 0.0f, 1.0f);
                break;

            case 8: // Tap
                GlStateManager.translate(0.0f, -swing * 0.15f, 0.0f);
                GlStateManager.rotate(-swing * 20.0f, 1.0f, 0.0f, 0.0f);
                break;

            case 9: // Jello
                float jello = MathHelper.sin(t * 8.0f) * (1.0f - swing) * 0.1f;
                GlStateManager.translate(jello, jello * 0.5f, 0.0f);
                GlStateManager.rotate(-swing * 30.0f + jello * 30, 1.0f, 0.0f, 0.0f);
                break;

            case 10: // Down
                GlStateManager.translate(0.0f, -0.2f, 0.1f);
                GlStateManager.rotate(60.0f, 1.0f, 0.0f, 0.0f);
                GlStateManager.rotate(-20.0f, 0.0f, 1.0f, 0.0f);
                break;

            case 11: // Avatar
                GlStateManager.translate(0.1f, 0.15f, -0.1f);
                GlStateManager.rotate(-swingSqrt * 70.0f, 1.0f, 0.0f, 0.0f);
                GlStateManager.rotate(-swing * 30.0f, 0.0f, 1.0f, 0.0f);
                GlStateManager.rotate(swing * 20.0f, 0.0f, 0.0f, 1.0f);
                break;

            case 12: // Stab
                GlStateManager.translate(0.0f, 0.0f, -swingSqrt * 0.5f);
                GlStateManager.translate(0.0f, swingSqrt * 0.1f, 0.0f);
                break;

            case 13: // Flux
                GlStateManager.translate(-0.1f, 0.1f, 0.1f);
                GlStateManager.rotate(-swingSqrt * 45.0f, 1.0f, 0.5f, 0.0f);
                GlStateManager.rotate(-40.0f, 0.0f, 1.0f, 0.0f);
                break;

            case 14: // Swank
                float waveS = MathHelper.sin(t * 4.0f) * 0.05f;
                GlStateManager.translate(waveS, 0.2f + waveS, 0.1f);
                GlStateManager.rotate(-swingSqrt * 55.0f, 1.0f, 0.0f, 0.0f);
                GlStateManager.rotate(-45.0f, 0.0f, 1.0f, 0.0f);
                break;

            case 15: // Swong
                GlStateManager.translate(-0.05f, 0.15f, 0.15f);
                GlStateManager.rotate(-swingSqrt * 65.0f, 1.0f, 0.0f, 0.0f);
                GlStateManager.rotate(-swing * 40.0f, 0.0f, 1.0f, 0.0f);
                GlStateManager.rotate(swing * 15.0f, 0.0f, 0.0f, 1.0f);
                break;

            case 16: // ETB
                GlStateManager.translate(0.0f, 0.1f, 0.0f);
                GlStateManager.rotate(-swing * 60.0f, 1.0f, 0.0f, 0.0f);
                GlStateManager.rotate(-30.0f, 0.0f, 1.0f, 0.0f);
                GlStateManager.rotate(swing * 25.0f, 0.0f, 0.0f, 1.0f);
                break;

            case 17: // Leaked
                float pulse = MathHelper.sin(t * 5.0f) * 0.08f;
                GlStateManager.translate(pulse, 0.15f, 0.05f);
                GlStateManager.rotate(-swing * 40.0f - 20.0f, 1.0f, 0.0f, 0.0f);
                GlStateManager.rotate(-55.0f + pulse * 20, 0.0f, 1.0f, 0.0f);
                break;

            // case 18 is Vanilla - handled above

            case 19: // Rotate
                GlStateManager.translate(0.0f, 0.1f, 0.0f);
                GlStateManager.rotate(t * 180.0f, 0.0f, 0.0f, 1.0f);
                GlStateManager.rotate(-30.0f, 1.0f, 0.0f, 0.0f);
                break;

            case 20: // Reverse
                GlStateManager.translate(0.0f, 0.1f, 0.0f);
                GlStateManager.rotate(swingSqrt * 50.0f, 1.0f, 0.0f, 0.0f);
                GlStateManager.rotate(-20.0f, 0.0f, 1.0f, 0.0f);
                break;

            case 21: // Lucky
                GlStateManager.translate(0.05f, 0.2f, 0.0f);
                GlStateManager.rotate(-swingSqrt * 75.0f, 1.0f, 0.5f, 0.5f);
                GlStateManager.rotate(-30.0f, 0.0f, 1.0f, 0.0f);
                break;

            case 22: // Zoom
                float zoom = 1.0f + MathHelper.sin(t * 3.0f) * 0.15f;
                GlStateManager.scale(zoom, zoom, zoom);
                GlStateManager.rotate(-swing * 30.0f, 1.0f, 0.0f, 0.0f);
                break;

            case 23: // Move
                float moveX = MathHelper.sin(t * 2) * 0.1f;
                float moveY = MathHelper.cos(t * 3) * 0.08f;
                GlStateManager.translate(moveX, moveY + 0.1f, 0.0f);
                GlStateManager.rotate(-35.0f, 1.0f, 0.0f, 0.0f);
                break;

            case 24: // Punch
                GlStateManager.translate(swingSqrt * 0.1f, -swingSqrt * 0.1f, -swingSqrt * 0.3f);
                GlStateManager.rotate(swingSqrt * 15.0f, 0.0f, 1.0f, 0.0f);
                break;

            case 25: // Stella
                GlStateManager.translate(-0.08f, 0.18f, 0.1f);
                GlStateManager.rotate(-swingSqrt * 50.0f, 1.0f, 0.2f, 0.0f);
                GlStateManager.rotate(-swing * 35.0f, 0.0f, 1.0f, 0.0f);
                break;

            case 26: // Interia
                float bounce = Math.abs(MathHelper.sin(t * 6.0f)) * 0.1f * (1.0f - swing);
                GlStateManager.translate(0.0f, bounce + 0.15f, 0.05f);
                GlStateManager.rotate(-swing * 45.0f - 15.0f, 1.0f, 0.0f, 0.0f);
                GlStateManager.rotate(-50.0f, 0.0f, 1.0f, 0.0f);
                break;

            case 27: // Float
                float floatY = MathHelper.sin(t * 2.0f) * 0.1f;
                float floatX = MathHelper.cos(t * 1.5f) * 0.05f;
                GlStateManager.translate(floatX, floatY + 0.15f, 0.0f);
                GlStateManager.rotate(-30.0f, 1.0f, 0.0f, 0.0f);
                GlStateManager.rotate(MathHelper.sin(t) * 10.0f, 0.0f, 0.0f, 1.0f);
                break;

            case 28: // Chill
                float chill = MathHelper.sin(t * 1.5f) * 0.03f;
                GlStateManager.translate(chill, 0.12f + chill, 0.0f);
                GlStateManager.rotate(-swing * 25.0f - 20.0f, 1.0f, 0.0f, 0.0f);
                GlStateManager.rotate(-35.0f, 0.0f, 1.0f, 0.0f);
                break;

            case 29: // Epic
                float epic = MathHelper.sin(t * 5.0f) * 0.1f;
                GlStateManager.translate(epic * 0.5f, 0.2f, epic);
                GlStateManager.rotate(-swingSqrt * 80.0f, 1.0f, 0.3f, 0.0f);
                GlStateManager.rotate(-60.0f + epic * 30, 0.0f, 1.0f, 0.0f);
                GlStateManager.rotate(swingSqrt * 20.0f, 0.0f, 0.0f, 1.0f);
                break;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  SWING ANIMATIONS (for non-blocking attacks)
    // ═══════════════════════════════════════════════════════════════════

    private void applySwingAnimation(int mode, float swingProgress, float time, float speed) {
        if (swingProgress <= 0) return;

        float swingSqrt = MathHelper.sin(MathHelper.sqrt_float(swingProgress) * (float) Math.PI);
        float t = time * speed;

        switch (mode) {
            case 6: // Spin - continuous rotation during swing
                GlStateManager.rotate(swingSqrt * 360.0f, 0.0f, 1.0f, 0.0f);
                break;

            case 19: // Rotate - roll during swing
                GlStateManager.rotate(t * 90.0f, 0.0f, 0.0f, 1.0f);
                break;

            case 7: // Swing - exaggerated swing
                GlStateManager.rotate(swingSqrt * 20.0f, 0.0f, 0.0f, 1.0f);
                break;

            case 29: // Epic - dramatic swing
                GlStateManager.rotate(swingSqrt * 15.0f, 1.0f, 0.0f, 0.0f);
                break;
        }
    }
}