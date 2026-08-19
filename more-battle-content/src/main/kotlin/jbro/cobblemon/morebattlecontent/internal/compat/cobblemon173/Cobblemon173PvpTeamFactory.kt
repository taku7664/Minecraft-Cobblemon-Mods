package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.pokemon.Pokemon
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpBattleFormat
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpPokemonRegistration
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpTeamRegistrationResult
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpTeamRules
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerPlayer

internal object Cobblemon173PvpTeamFactory {
    fun register(player: ServerPlayer, format: PvpBattleFormat): PvpTeamRegistrationResult =
        PvpTeamRules.register(
            Cobblemon.storage.getParty(player).toList().map(::registration),
            format,
        )

    fun registration(pokemon: Pokemon): PvpPokemonRegistration {
        val heldItem = pokemon.heldItem()
        return PvpPokemonRegistration(
            pokemonId = pokemon.uuid,
            speciesId = pokemon.species.resourceIdentifier.toString(),
            heldItemId = if (heldItem.isEmpty) null else BuiltInRegistries.ITEM.getKey(heldItem.item).toString(),
            level = pokemon.level,
        )
    }
}
