package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.moves.Move
import com.cobblemon.mod.common.api.moves.MoveSet
import com.cobblemon.mod.common.api.moves.Moves
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

    private fun createBattlePokemon(set: FactoryRentalSet, levelMode: FactoryLevelMode): BattlePokemon {
        val orderedMoves = set.moveIds.map { moveId ->
            val showdownName = moveId.toCobblemonShowdownName()
            requireNotNull(Moves.getByName(showdownName)) { "Unknown Factory move: $moveId" }.create()
        }
        val pokemon = Cobblemon173CatalogPokemonCreator.create(toProperties(set, levelMode), set.formId)
        enforceMoveOrder(pokemon.moveSet, orderedMoves)
        return BattlePokemon.safeCopyOf(pokemon).also { battlePokemon ->
            check(battlePokemon.effectedPokemon.moveSet.filterNotNull().map { it.name } == orderedMoves.map { it.name }) {
                "Cobblemon changed the fixed move order while copying Factory rental ${set.setId}"
            }
            battlePokemon.effectedPokemon.heal()
        }
    }

    internal fun enforceMoveOrder(moveSet: MoveSet, orderedMoves: List<Move>) {
        require(orderedMoves.size in 1..4) { "Factory move order must contain one to four moves" }
        require(orderedMoves.map { it.name }.distinct().size == orderedMoves.size) {
            "Factory move order must not contain duplicates"
        }
        moveSet.doWithoutEmitting {
            repeat(4) { slot -> moveSet.setMove(slot, orderedMoves.getOrNull(slot)) }
        }
        moveSet.update()
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
