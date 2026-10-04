package jbro.cobblemon.policy.mixin;

import com.cobblemon.mod.common.command.GivePokemon;
import com.cobblemon.mod.common.pokemon.Pokemon;
import jbro.cobblemon.policy.wild.WildPokemonPolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** /givepokemon rolls the hidden ability chance too. */
@Mixin(value = GivePokemon.class, remap = false)
abstract class GivePokemonHiddenAbilityMixin {
    @ModifyArg(method = "execute", at = @At(value = "INVOKE",
        target = "Lcom/cobblemon/mod/common/api/storage/party/PlayerPartyStore;add(Lcom/cobblemon/mod/common/pokemon/Pokemon;)Z"))
    private Pokemon jbroPolicy$hiddenAbilityChance(Pokemon pokemon) {
        return WildPokemonPolicy.applyHiddenAbilityChance(pokemon);
    }
}
