package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import com.cobblemon.mod.common.pokemon.EVs
import com.cobblemon.mod.common.pokemon.IVs
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryRentalSet
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryLevelMode
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryStatSpread

internal object Cobblemon173FactoryPokemonFactory {
    fun toProperties(set: FactoryRentalSet, levelMode: FactoryLevelMode): PokemonProperties {
        return PokemonProperties().apply {
            species = set.speciesId
            form = set.formId
            ability = set.abilityId.toCobblemonShowdownName()
            nature = set.natureId
            heldItem = set.heldItemId
            moves = set.moveIds.map(String::toCobblemonShowdownName)
            level = levelMode.battleLevel
            ivs = set.ivs.toIVs()
            evs = set.evs.toEVs()
        }
    }

    fun toPlayerBattlePokemon(set: FactoryRentalSet, levelMode: FactoryLevelMode): BattlePokemon =
        createBattlePokemon(set, levelMode)

    fun toOpponentBattlePokemon(set: FactoryRentalSet, levelMode: FactoryLevelMode): BattlePokemon =
        createBattlePokemon(set, levelMode).also { battlePokemon ->
            Cobblemon173OpponentPokemonSafety.apply(battlePokemon.originalPokemon)
            Cobblemon173OpponentPokemonSafety.apply(battlePokemon.effectedPokemon)
        }

    private fun createBattlePokemon(set: FactoryRentalSet, levelMode: FactoryLevelMode): BattlePokemon =
        BattlePokemon.safeCopyOf(toProperties(set, levelMode).create()).also { battlePokemon ->
            battlePokemon.effectedPokemon.heal()
        }

    private fun FactoryStatSpread.toIVs(): IVs = IVs().also { stats -> applyTo(stats) }

    private fun FactoryStatSpread.toEVs(): EVs = EVs().also { stats -> applyTo(stats) }

    private fun FactoryStatSpread.applyTo(stats: IVs) {
        stats[Stats.HP] = hp
        stats[Stats.ATTACK] = attack
        stats[Stats.DEFENCE] = defense
        stats[Stats.SPECIAL_ATTACK] = specialAttack
        stats[Stats.SPECIAL_DEFENCE] = specialDefense
        stats[Stats.SPEED] = speed
    }

    private fun FactoryStatSpread.applyTo(stats: EVs) {
        stats[Stats.HP] = hp
        stats[Stats.ATTACK] = attack
        stats[Stats.DEFENCE] = defense
        stats[Stats.SPECIAL_ATTACK] = specialAttack
        stats[Stats.SPECIAL_DEFENCE] = specialDefense
        stats[Stats.SPEED] = speed
    }
}
