package jbro.cobblemon.policy.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import jbro.cobblemon.policy.pokemon.WildRetaliation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * On Peaceful, Player.hurt turns difficulty-scaled damage into 0. A wild Pokemon's hit skips that scaling there, so it lands
 * at its full attack damage (see {@link WildRetaliation}).
 */
@Mixin(Player.class)
abstract class WildRetaliationDamageMixin {
    @ModifyExpressionValue(method = "hurt",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/damagesource/DamageSource;scalesWithDifficulty()Z"))
    private boolean jbroPolicy$wildHitsLand(boolean scales, @Local(argsOnly = true) DamageSource source) {
        if (!scales) return false;
        Player self = (Player) (Object) this;
        return self.level().getDifficulty() != Difficulty.PEACEFUL || !WildRetaliation.isWild(source.getEntity());
    }
}
