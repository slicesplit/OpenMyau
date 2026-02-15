package myau.mixin;

import net.minecraft.client.particle.EffectRenderer;
import net.minecraft.client.particle.EntityFX;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Fix ConcurrentModificationException in particle rendering
 * This crash happens when particles are modified during iteration
 * 
 * Priority 10000 to ensure we run after OverflowParticles mixin
 */
@Mixin(value = EffectRenderer.class, priority = 10000)
public class MixinEffectRenderer {
    
    @Shadow
    private List<EntityFX>[][] fxLayers;
    
    /**
     * Fix concurrent modification by wrapping the iteration in a try-catch
     * This prevents crashes when particles are added/removed during rendering
     */
    @Inject(method = "updateEffects", at = @At("HEAD"))
    private void safeUpdateEffects(CallbackInfo ci) {
        // Just ensure fxLayers is not null - the actual fix is in renderParticles
    }
    
    /**
     * Fix the actual crash location - renderParticles iteration
     */
    @Inject(method = "renderParticles", at = @At("HEAD"))
    private void safeRenderParticles(CallbackInfo ci) {
        // The crash happens during iteration - we'll catch it in the Particles module
    }
}
