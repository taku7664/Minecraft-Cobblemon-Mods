package jbro.cobblemon.policy.mixin;

import com.cobblemon.mod.common.entity.ai.AttackAngryAtTask;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import jbro.cobblemon.policy.pokemon.WildRetaliation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A wild Pokemon takes the one it is angry at as its attack target on Peaceful too (see {@link WildRetaliation}). */
@Mixin(value = AttackAngryAtTask.class, remap = false)
abstract class WildRetaliationAngerMixin {
    // Cobblemon 1.8.1's Kotlin lambda that sets the attack target.
    @ModifyExpressionValue(method = "create$lambda$0$0$0",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/DifficultyInstance;getDifficulty()Lnet/minecraft/world/Difficulty;", remap = true))
    private static Difficulty jbroPolicy$wildIgnoresPeaceful(Difficulty difficulty, @Local(argsOnly = true) LivingEntity entity) {
        return WildRetaliation.difficultyFor(entity, difficulty);
    }
}
