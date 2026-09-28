package jbro.cobblemon.mcc.betterai.evaluation

import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattlePublicActionCatalogView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * What stat stages are worth beyond the next hit, AI side minus opponent side, in stage units (one Attack
 * stage on a Pokemon at full HP is 1).
 *
 * The leaf prices stages by the pressure they add against the Pokemon in front, capped at its HP: a boost
 * that turns a knockout into a bigger knockout, or that threatens the Pokemon still in the back, was worth
 * nothing there, so a sweeper that had boosted looked no better than one that had not. Stages last while the
 * Pokemon stays in, so this is a price on the board, with the diminishing returns of the stage multipliers
 * (the scale a competitive search bot, Foul Play, uses), weighed by the HP left to carry them and counted only
 * for the attacking stat the Pokemon's known moves use.
 */
internal object LocalPersistentStageValue {
    fun evaluate(state: BattleStateView, catalog: BattlePublicActionCatalogView): Double {
        var total = 0.0
        for (pokemon in state.pokemon) {
            if (pokemon.activeSlot == null || pokemon.fainted || pokemon.hpFraction <= 0.0 || pokemon.statStages.isEmpty()) continue
            val sign = if (pokemon.side == BattleSide.ALLY) 1.0 else -1.0
            total += sign * pokemon.hpFraction * pokemonValue(pokemon, catalog)
        }
        return total
    }

    private fun pokemonValue(pokemon: BattlePokemonStateView, catalog: BattlePublicActionCatalogView): Double {
        val moves = catalog.forPokemon(pokemon.battlePokemonId)
        val physical = moves.any { it.details.damageCategory == BattleMoveDamageCategory.PHYSICAL && (it.details.power ?: 0.0) > 0.0 }
        val special = moves.any { it.details.damageCategory == BattleMoveDamageCategory.SPECIAL && (it.details.power ?: 0.0) > 0.0 }
        // No known attack yet: either attacking stat may be the one that matters, each at half.
        val unknown = !physical && !special
        var value = 0.0
        for ((stat, stage) in pokemon.statStages) {
            val weight = when (PublicIds.canonical(stat)) {
                "attack", "atk" -> if (physical) 1.0 else if (unknown) 0.5 else 0.0
                "specialattack", "spa" -> if (special) 1.0 else if (unknown) 0.5 else 0.0
                "defense", "defence", "def", "specialdefense", "specialdefence", "spd" -> DEFENSIVE_WEIGHT
                "speed", "spe" -> SPEED_WEIGHT
                else -> 0.0
            }
            if (weight != 0.0) value += weight * scale(stage)
        }
        return value
    }

    /** Diminishing: +1 and +2 are full steps, later ones less, as the stage multiplier's relative gain falls. */
    private fun scale(stage: Int): Double {
        val magnitude = SCALE[kotlin.math.abs(stage).coerceAtMost(6)]
        return if (stage < 0) -magnitude else magnitude
    }

    private val SCALE = doubleArrayOf(0.0, 1.0, 2.0, 2.5, 3.0, 3.15, 3.3)
    private const val DEFENSIVE_WEIGHT = 0.5
    private const val SPEED_WEIGHT = 1.0
}
