package jbro.cobblemon.mcc.betterai.search

import java.util.UUID
import jbro.cobblemon.mcc.betterai.matchup.MatchupScores
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.PublicIds

/** How promising an action looks before it is searched; only the order actions are tried in follows it. */
internal fun interface NativeActionPrior {
    /** Higher is tried first; null when nothing is known about it. */
    fun score(state: BattleStateView, side: BattleSide, action: BattleActionCandidate): Double?
}

/**
 * Reads the decision's [MatchupScores], worked out once on the root board, for either side:
 * - a damaging move scores `1 / expected hits to knock its target out` (1.0 for a sure one-hit knockout);
 * - a switch scores half the incoming Pokemon's one-on-one win chance against the opposing active, times
 *   the share of that exchange the current active loses: a switch looks promising only when staying in
 *   looks bad and the incoming Pokemon looks good.
 *
 * Pokemon the scores never met (an opponent the AI has not seen, a world's guessed bench) are unknown.
 * The opponent's actions are read by the opponent's own interest, never by the AI's priorities.
 */
internal class NativeMatchupPrior(private val scores: MatchupScores) : NativeActionPrior {
    override fun score(state: BattleStateView, side: BattleSide, action: BattleActionCandidate): Double? {
        if (action.kind == BattleActionKind.COMPOSITE) {
            return action.componentActions.mapNotNull { score(state, side, it) }.maxOrNull()
        }
        val actor = state.pokemon.firstOrNull {
            it.side == side && it.activeSlot == action.actorSlot && !it.fainted
        } ?: return null
        val opposing = opposingTarget(state, side, action) ?: return null
        return when (action.kind) {
            BattleActionKind.USE_MOVE -> {
                val moveId = action.moveId?.let(PublicIds::canonical) ?: return null
                scores.moves(actor.battlePokemonId, opposing).firstOrNull { PublicIds.canonical(it.moveId) == moveId }?.score
            }
            BattleActionKind.SWITCH -> {
                val incoming = action.switchPokemonId ?: return null
                val win = scores.pokemon(incoming, opposing)?.winProbability ?: return null
                val staying = scores.pokemon(actor.battlePokemonId, opposing)?.winProbability ?: 0.5
                SWITCH_SCALE * win * (1.0 - staying)
            }
            else -> null
        }
    }

    private fun opposingTarget(state: BattleStateView, side: BattleSide, action: BattleActionCandidate): UUID? {
        val other = if (side == BattleSide.ALLY) BattleSide.OPPONENT else BattleSide.ALLY
        val targetSlot = action.targets.firstOrNull { it.side == other }?.slot
        val foes = state.pokemon.filter { it.side == other && it.activeSlot != null && !it.fainted }
        return (foes.firstOrNull { it.activeSlot == targetSlot } ?: foes.firstOrNull())?.battlePokemonId
    }

    companion object {
        /** Where an action nothing is known about sits: after a sure two-hit knockout, before a three-hit one. */
        const val UNKNOWN = 0.4
        private const val SWITCH_SCALE = 0.5

        /** [actions] best first, unknown ones at [UNKNOWN], ties kept in their given order. */
        fun order(
            prior: NativeActionPrior?,
            state: BattleStateView,
            side: BattleSide,
            actions: List<BattleActionCandidate>,
        ): List<BattleActionCandidate> {
            if (prior == null || actions.size < 2) return actions
            return actions.withIndex()
                .sortedWith(compareByDescending<IndexedValue<BattleActionCandidate>> {
                    prior.score(state, side, it.value) ?: UNKNOWN
                }.thenBy { it.index })
                .map { it.value }
        }
    }
}
