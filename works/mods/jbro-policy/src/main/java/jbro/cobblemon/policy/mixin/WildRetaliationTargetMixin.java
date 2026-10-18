package jbro.cobblemon.policy.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import jbro.cobblemon.policy.pokemon.WildRetaliation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A wild Pokemon may keep a player as its target on Peaceful, so it is not dropped right away (see {@link WildRetaliation}). */
@Mixin(LivingEntity.class)
abstract class WildRetaliationTargetMixin {
    @ModifyExpressionValue(method = "canAttack(Lnet/minecraft/world/entity/LivingEntity;)Z",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getDifficulty()Lnet/minecraft/world/Difficulty;"))
    private Difficulty jbroPolicy$wildIgnoresPeaceful(Difficulty difficulty) {
        return WildRetaliation.difficultyFor((LivingEntity) (Object) this, difficulty);
    }
}
