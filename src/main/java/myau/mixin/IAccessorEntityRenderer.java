package myau.mixin;

import net.minecraft.client.renderer.EntityRenderer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@SideOnly(Side.CLIENT)
@Mixin({EntityRenderer.class})
public interface IAccessorEntityRenderer {
    @Invoker
    void callSetupCameraTransform(float float1, int integer);
    
    @Accessor("fovModifierHand")
    float getFovModifierHand();
    
    @Accessor("fovModifierHand")
    void setFovModifierHand(float value);
    
    @Accessor("fovModifierHandPrev")
    float getFovModifierHandPrev();
    
    @Accessor("fovModifierHandPrev")
    void setFovModifierHandPrev(float value);
    
    @Invoker("renderWorldPass")
    void callRenderWorldPass(int pass, float partialTicks, long finishTimeNano);
}
