package jbro.cobblemon.battlecam.iris;

import jbro.cobblemon.battlecam.client.BattlecamSeeThrough;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.shaderpack.IdMap;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the battle camera's see-through uniforms to the set Iris gives every program of a shader pack:
 * mcc_seeThrough (0 to 1) and mcc_seeThroughA/B (targets relative to the camera). Iris 1.8.8 has no public way for a
 * mod to hand a pack values, so this hooks CommonUniforms.addNonDynamicUniforms, which the rendering pipeline calls
 * for its programs (HardcodedCustomUniforms looks like the place but nothing in 1.8.8 calls it). Only loads with Iris.
 */
@Mixin(value = CommonUniforms.class, remap = false)
abstract class IrisSeeThroughUniformsMixin {
    @Inject(method = "addNonDynamicUniforms", at = @At("TAIL"))
    private static void battlecam$addSeeThrough(UniformHolder holder, IdMap idMap, PackDirectives directives,
                                                FrameUpdateNotifier updateNotifier, CallbackInfo ci) {
        holder.uniform1f(UniformUpdateFrequency.PER_FRAME, "mcc_seeThrough", BattlecamSeeThrough::strength);
        holder.uniform3f(UniformUpdateFrequency.PER_FRAME, "mcc_seeThroughA", BattlecamSeeThrough::relativeA);
        holder.uniform3f(UniformUpdateFrequency.PER_FRAME, "mcc_seeThroughB", BattlecamSeeThrough::relativeB);
    }
}
