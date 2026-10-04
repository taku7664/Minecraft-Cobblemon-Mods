package jbro.cobblemon.policy.mixin;

import com.cobblemon.mod.common.api.spawning.detail.PokemonSpawnDetail;
import com.cobblemon.mod.common.api.spawning.detail.SpawnDetail;
import com.cobblemon.mod.common.api.spawning.position.SpawnablePosition;
import com.cobblemon.mod.common.block.entity.PokeSnackBlockEntity;
import jbro.cobblemon.policy.legend.LegendPolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A Poke Snack's Legends follow its placer: no Legend they already caught, none whose entry Pokemon is missing from
 * their party, and none at all while they are offline.
 */
@Mixin(value = PokeSnackBlockEntity.class, remap = false)
abstract class PokeSnackLegendFilterMixin {
    @Inject(method = "affectSpawnable", at = @At("HEAD"), cancellable = true)
    private void jbroPolicy$placerLegends(SpawnDetail detail, SpawnablePosition position, CallbackInfoReturnable<Boolean> cir) {
        if (detail instanceof PokemonSpawnDetail pokemon
            && !LegendPolicy.snackMayOffer((PokeSnackBlockEntity) (Object) this, pokemon.getPokemon())) {
            cir.setReturnValue(false);
        }
    }
}
