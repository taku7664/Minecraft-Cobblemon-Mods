package jbro.cobblemon.mcc.betterai.evaluation

import jbro.cobblemon.mcc.betterai.matchup.LocalMatchupPosition
import jbro.cobblemon.mcc.betterai.matchup.LocalMatchupScoreCalculator
import jbro.cobblemon.mcc.betterai.matchup.MatchupSpeedField
import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattlePublicActionCatalogView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import java.util.UUID

/**
 * The board read through the matchup scores, for the search's leaf (singles): how well each side's living Pokemon
 * answer the other's, and who wins the matchup on the field.
 *
 * - Team: for each living opponent, the best win chance any living ally has against it, averaged; less the same
 *   from the opponent's side. A switch into the right Pokemon, a sweeper that has boosted or the last answer to a
 *   threat kept alive all show here, where the pressure term saw only the next hit between the two in front.
 * - Field: the [jbro.cobblemon.mcc.betterai.matchup.PokemonMatchupScore] of the two Pokemon facing each other.
 *
 * Each pair is scored as the matchup scores do, both Pokemon entered as the public projection has it.
 * The key includes the whole public position and move catalog: item loss, ability changes, decoys,
 * hazards and one remaining HP point can all change who wins the exchange.
 */
internal object LocalLeafMatchups {
    class Value(val team: Double, val field: Double)

    fun evaluate(
        state: BattleStateView,
        source: BattleDecisionContext,
        cache: LocalProjectedActionCalculationCache,
        shouldContinue: () -> Boolean,
    ): Value? {
        if (state.format != BattleFormat.SINGLE) return null
        val allies = scored(state, BattleSide.ALLY)
        val opponents = scored(state, BattleSide.OPPONENT)
        if (allies.isEmpty() || opponents.isEmpty()) return null
        val context = source.copy(state = state)
        val win = HashMap<Pair<UUID, UUID>, Double>()
        var fieldScore = 0.0
        for (ally in allies) for (opponent in opponents) {
            if (!shouldContinue()) return null
            val pair = pair(context, state, ally, opponent, cache) ?: continue
            win[ally.battlePokemonId to opponent.battlePokemonId] = pair.first
            if (ally.activeSlot != null && opponent.activeSlot != null) fieldScore = pair.second
        }
        if (win.isEmpty()) return null
        val ours = opponents.mapNotNull { opponent ->
            allies.mapNotNull { win[it.battlePokemonId to opponent.battlePokemonId] }.maxOrNull()
        }
        val theirs = allies.mapNotNull { ally ->
            opponents.mapNotNull { win[ally.battlePokemonId to it.battlePokemonId]?.let { chance -> 1.0 - chance } }.maxOrNull()
        }
        if (ours.isEmpty() || theirs.isEmpty()) return null
        return Value(ours.average() - theirs.average(), fieldScore)
    }

    /** The ally's win chance and matchup score against the opponent, cached per search. */
    private fun pair(
        context: BattleDecisionContext,
        state: BattleStateView,
        ally: BattlePokemonStateView,
        opponent: BattlePokemonStateView,
        cache: LocalProjectedActionCalculationCache,
    ): Pair<Double, Double>? {
        val key = PairKey(
            cache.fingerprints.of(state), ally.battlePokemonId, opponent.battlePokemonId,
            context.publicActionCatalog, cache.matchupRecovery,
        )
        cache.leafMatchups[key]?.let { return it.value }
        val position = LocalMatchupPosition.face(context, ally, opponent, cache)
        val score = position?.let {
            LocalMatchupScoreCalculator.pairMatchup(it, ally.battlePokemonId, opponent.battlePokemonId, MatchupSpeedField.CURRENT, cache)
        }
        val value = score?.let { it.winProbability to it.score }
        cache.leafMatchups[key] = LocalProjectedActionCalculationCache.Cached(value)
        return value
    }

    /** Living Pokemon with public types and stats: an unseen one cannot be scored. */
    private fun scored(state: BattleStateView, side: BattleSide): List<BattlePokemonStateView> = state.pokemon.filter {
        it.side == side && !it.fainted && it.hpFraction > 0.0 && it.knownTypeIds.isNotEmpty() && it.combatStats != null
    }

    private data class PairKey(
        val state: String, val ally: UUID, val opponent: UUID,
        val catalog: BattlePublicActionCatalogView, val recovery: Boolean,
    )
}
