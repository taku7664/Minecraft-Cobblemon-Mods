package jbro.cobblemon.battlecam.iris;

import jbro.cobblemon.battlecam.client.BattlecamSeeThrough;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.HardcodedCustomUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the battle camera's see-through uniforms next to the ones Iris hardcodes: mcc_seeThrough (0 to 1) and
 * mcc_seeThroughA/B (targets relative to the camera). Iris 1.8.8 has no public way for a mod to hand a shader pack
 * values, so this hooks its internal registry; the mixin only loads with Iris present.
 */
@Mixin(value = HardcodedCustomUniforms.class, remap = false)
abstract class IrisSeeThroughUniformsMixin {
    @Inject(method = "addHardcodedCustomUniforms", at = @At("TAIL"))
    private static void battlecam$addSeeThrough(UniformHolder holder, FrameUpdateNotifier updateNotifier, CallbackInfo ci) {
        holder.uniform1f(UniformUpdateFrequency.PER_FRAME, "mcc_seeThrough", BattlecamSeeThrough::strength);
        holder.uniform3f(UniformUpdateFrequency.PER_FRAME, "mcc_seeThroughA", BattlecamSeeThrough::relativeA);
        holder.uniform3f(UniformUpdateFrequency.PER_FRAME, "mcc_seeThroughB", BattlecamSeeThrough::relativeB);
    }
}
