package jbro.cobblemon.policy.mixin;

import com.cobblemon.mod.common.api.ai.config.task.SwitchToFightTaskConfig;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import jbro.cobblemon.policy.pokemon.WildRetaliation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A wild Pokemon with an attack target enters its fight activity on Peaceful too (see {@link WildRetaliation}). */
@Mixin(value = SwitchToFightTaskConfig.class, remap = false)
abstract class WildRetaliationFightMixin {
    // Cobblemon 1.8.1's Kotlin lambda that switches to the fight activity.
    @ModifyExpressionValue(method = "createTask$lambda$0$0$0",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/DifficultyInstance;getDifficulty()Lnet/minecraft/world/Difficulty;", remap = true))
    private static Difficulty jbroPolicy$wildIgnoresPeaceful(Difficulty difficulty, @Local(argsOnly = true) LivingEntity entity) {
        return WildRetaliation.difficultyFor(entity, difficulty);
    }
}
