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
 * A Poke Snack spawns for no player, so a Legend from one would skip its owner, entry and once-per-player rules.
 * Legends only come from player spawners.
 */
@Mixin(value = PokeSnackBlockEntity.class, remap = false)
abstract class PokeSnackNoLegendsMixin {
    @Inject(method = "affectSpawnable", at = @At("HEAD"), cancellable = true)
    private void jbroPolicy$noLegends(SpawnDetail detail, SpawnablePosition position, CallbackInfoReturnable<Boolean> cir) {
        if (detail instanceof PokemonSpawnDetail pokemon && LegendPolicy.isLegend(pokemon.getPokemon().getSpecies())) {
            cir.setReturnValue(false);
        }
    }
}
