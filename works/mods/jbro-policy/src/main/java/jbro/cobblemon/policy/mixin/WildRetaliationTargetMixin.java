package jbro.cobblemon.policy.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import jbro.cobblemon.policy.pokemon.WildRetaliation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A wild Pokemon may keep the player who hurt it as its target on Peaceful (see {@link WildRetaliation}). */
@Mixin(LivingEntity.class)
abstract class WildRetaliationTargetMixin {
    @ModifyExpressionValue(method = "canAttack(Lnet/minecraft/world/entity/LivingEntity;)Z",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getDifficulty()Lnet/minecraft/world/Difficulty;"))
    private Difficulty jbroPolicy$wildIgnoresPeaceful(Difficulty difficulty, @Local(argsOnly = true) LivingEntity target) {
        return WildRetaliation.difficultyFor((LivingEntity) (Object) this, target, difficulty);
    }
}
