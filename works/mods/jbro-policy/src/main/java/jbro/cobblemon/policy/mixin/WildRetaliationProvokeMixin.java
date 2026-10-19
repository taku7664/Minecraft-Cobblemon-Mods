package jbro.cobblemon.policy.mixin;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import jbro.cobblemon.policy.pokemon.WildRetaliation;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Remembers which player hurt a wild Pokemon, the only one it may hit back at on Peaceful (see {@link WildRetaliation}). */
@Mixin(PokemonEntity.class)
abstract class WildRetaliationProvokeMixin {
    @Inject(method = "hurt", at = @At("RETURN"))
    private void jbroPolicy$noteHurt(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) WildRetaliation.noteHurt((PokemonEntity) (Object) this, source);
    }
}
