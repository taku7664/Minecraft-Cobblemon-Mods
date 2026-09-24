package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import com.cobblemon.mod.common.pokemon.Pokemon
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleTeamMaterialization
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleTeamMaterializer
import jbro.cobblemon.morebattlecontent.internal.tower.TowerPokemonRegistration
import jbro.cobblemon.morebattlecontent.internal.tower.TowerSelectedTeam
import net.minecraft.server.level.ServerPlayer

internal object Cobblemon173TowerBattleTeamMaterializer {
    fun materialize(
        player: ServerPlayer,
        selection: TowerSelectedTeam,
    ): TowerBattleTeamMaterialization<BattlePokemon> =
        TowerBattleTeamMaterializer<Pokemon, BattlePokemon>(
            registrationOf = ::registration,
            cloneForBattle = ::cloneForBattle,
        ).materialize(selection, Cobblemon.storage.getParty(player).toList())

    private fun registration(pokemon: Pokemon): TowerPokemonRegistration = pokemon.toTowerPokemonRegistration()

    private fun cloneForBattle(pokemon: Pokemon, battleLevel: Int): BattlePokemon {
        val battlePokemon = BattlePokemon.Companion.safeCopyOf(pokemon)
        check(battlePokemon.effectedPokemon !== pokemon) { "Cobblemon returned the original Pokemon as a battle clone" }
        check(battlePokemon.effectedPokemon.isBattleClone()) { "Cobblemon battle clone marker is missing" }
        battlePokemon.effectedPokemon.level = battleLevel
        battlePokemon.effectedPokemon.heal()
        return battlePokemon
    }
}
