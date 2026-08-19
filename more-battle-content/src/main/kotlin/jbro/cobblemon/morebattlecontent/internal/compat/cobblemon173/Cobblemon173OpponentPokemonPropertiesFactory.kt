package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.pokemon.EVs
import com.cobblemon.mod.common.pokemon.IVs
import jbro.cobblemon.morebattlecontent.internal.tower.TOWER_BATTLE_LEVEL_CAP
import jbro.cobblemon.morebattlecontent.internal.tower.opponent.TowerPokemonSet
import jbro.cobblemon.morebattlecontent.internal.tower.opponent.TowerStatSpread

internal object Cobblemon173OpponentPokemonPropertiesFactory {
    fun toProperties(set: TowerPokemonSet): PokemonProperties = PokemonProperties().apply {
        species = set.speciesId
        form = set.formId
        ability = set.abilityId?.toCobblemonShowdownName()
        nature = set.natureId
        heldItem = set.heldItemId
        moves = set.moves.map(String::toCobblemonShowdownName)
        level = TOWER_BATTLE_LEVEL_CAP
        ivs = set.ivs.toIVs()
        evs = set.evs.toEVs()
        teraType = set.teraType
        dmaxLevel = set.dmaxLevel
        gmaxFactor = set.gmaxFactor
    }

    fun toBattlePokemon(set: TowerPokemonSet): BattlePokemon =
        BattlePokemon.Companion.safeCopyOf(toProperties(set).create()).also { battlePokemon ->
            Cobblemon173OpponentPokemonSafety.apply(battlePokemon.originalPokemon)
            Cobblemon173OpponentPokemonSafety.apply(battlePokemon.effectedPokemon)
            battlePokemon.effectedPokemon.heal()
        }

    private fun TowerStatSpread.toIVs(): IVs = IVs().also { stats -> applyTo(stats) }

    private fun TowerStatSpread.toEVs(): EVs = EVs().also { stats -> applyTo(stats) }

    private fun TowerStatSpread.applyTo(stats: IVs) {
        stats[Stats.HP] = hp
        stats[Stats.ATTACK] = attack
        stats[Stats.DEFENCE] = defense
        stats[Stats.SPECIAL_ATTACK] = specialAttack
        stats[Stats.SPECIAL_DEFENCE] = specialDefense
        stats[Stats.SPEED] = speed
    }

    private fun TowerStatSpread.applyTo(stats: EVs) {
        stats[Stats.HP] = hp
        stats[Stats.ATTACK] = attack
        stats[Stats.DEFENCE] = defense
        stats[Stats.SPECIAL_ATTACK] = specialAttack
        stats[Stats.SPECIAL_DEFENCE] = specialDefense
        stats[Stats.SPEED] = speed
    }
}
