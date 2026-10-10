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

/** Poke Snacks never offer Legends, regardless of their placer's progress or online status. */
@Mixin(value = PokeSnackBlockEntity.class, remap = false)
abstract class PokeSnackLegendFilterMixin {
    @Inject(method = "affectSpawnable", at = @At("HEAD"), cancellable = true)
    private void jbroPolicy$blockLegends(SpawnDetail detail, SpawnablePosition position, CallbackInfoReturnable<Boolean> cir) {
        if (detail instanceof PokemonSpawnDetail pokemon
            && LegendPolicy.INSTANCE.legendOf(pokemon.getPokemon()) != null) {
            cir.setReturnValue(false);
        }
    }
}
