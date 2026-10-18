package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.betterai.evaluation.LocalStatStageMarginalEvaluator
import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.mcc.betterai.mechanics.LocalStatStageChange
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.betterai.simulation.LocalOpponentStatAssumption
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveCandidateView
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget
import jbro.cobblemon.mcc.internal.ai.BattleOwnStageValue
import jbro.cobblemon.mcc.internal.ai.BattlePokemonActionCatalogView
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattlePublicActionCatalogView
import jbro.cobblemon.mcc.internal.ai.BattlePublicMoveKnowledge
import jbro.cobblemon.mcc.internal.ai.BattlePublicMoveOptionView
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import jbro.cobblemon.mcc.internal.ai.PublicIds
import java.util.UUID

/**
 * [BattleOwnStageValue] on the board in [source]: an attack's own stat changes are worth the mean change in its user's
 * one-on-one worth, `(score + 1) / 2` of [PokemonMatchupScore.score] from its side, against every living Pokemon of the
 * other side, the user attacking with that move alone before and after the change. Scale Shot's +1 Speed is worth
 * what it turns: an exchange it now moves first in, or nothing against a team it already outspeeds. Opposing stats
 * follow the tier's assumption, as the rules read them. One reading per Pokemon and move.
 */
internal class LocalOwnStageValue(
    source: BattleDecisionContext,
    tier: BattleTrainerTier,
) : BattleOwnStageValue {
    private val context = LocalOpponentStatAssumption.applyToPublicState(source, tier)
    private val cache = LocalProjectedActionCalculationCache()
    private val memo = HashMap<Pair<UUID, String>, Double?>()

    @Synchronized
    override fun worthChange(pokemon: BattlePokemonStateView, moveId: String, move: BattleMoveCandidateView): Double? =
        memo.getOrPut(pokemon.battlePokemonId to PublicIds.canonical(moveId)) { read(pokemon, moveId, move) }

    private fun read(pokemon: BattlePokemonStateView, moveId: String, move: BattleMoveCandidateView): Double? {
        val own = move.effects?.effects.orEmpty().filter {
            it.kind == BattleMoveEffectKind.STAT_STAGE && it.target == BattleMoveEffectTarget.USER && it.statStages.isNotEmpty()
        }
        if (own.isEmpty()) return 0.0
        val state = context.state
        val subject = state.pokemon.firstOrNull { it.battlePokemonId == pokemon.battlePokemonId } ?: return null
        val others = state.pokemon.filter { it.side != subject.side && !it.fainted && it.hpFraction > 0.0 }
        if (others.isEmpty()) return null
        // The user attacks with this move alone.
        val catalog = context.publicActionCatalog
        val option = BattlePublicMoveOptionView(moveId, move, BattlePublicMoveKnowledge.PUBLICLY_REVEALED)
        val probe = context.copy(publicActionCatalog = BattlePublicActionCatalogView(
            catalog.entries.filter { it.battlePokemonId != subject.battlePokemonId } +
                BattlePokemonActionCatalogView(subject.battlePokemonId, listOf(option), moveSetComplete = true),
            catalog.originalEntries.filter { it.battlePokemonId != subject.battlePokemonId },
            catalog.candidatePools,
            emptyList(),
        ))
        val changes = others.mapNotNull { other ->
            val faced = LocalMatchupPosition.face(probe, other, subject, cache) ?: placed(probe, other, subject) ?: return@mapNotNull null
            fun worth(position: BattleDecisionContext): Double? = LocalMatchupScoreCalculator.pairMatchup(
                position, other.battlePokemonId, subject.battlePokemonId, MatchupSpeedField.CURRENT, cache,
            )?.score?.let { (1.0 - it) / 2.0 }
            var board = faced
            var current = worth(board) ?: return@mapNotNull null
            var change = 0.0
            own.forEach { effect ->
                val holder = board.state.pokemon.firstOrNull { it.battlePokemonId == subject.battlePokemonId } ?: return@forEach
                val changed = board.copy(state = LocalStatStageMarginalEvaluator.applyStages(board.state, setOf(subject.battlePokemonId),
                    LocalStatStageChange.reshape(board.state, holder, subject.battlePokemonId, effect.statStages)))
                val after = worth(changed) ?: return@forEach
                val probability = (effect.probability ?: 1.0).coerceIn(0.0, 1.0)
                change += probability * (after - current)
                if (probability >= CERTAIN) {
                    board = changed
                    current = after
                }
            }
            change
        }
        return changes.takeIf { it.isNotEmpty() }?.average()
    }

    /**
     * [first] and [second] put in front of each other directly, stages reset for one coming in, when the public switch
     * projection has no switch to do it with (a request to replace a fainted Pokemon offers none): a Mega Tyranitar's
     * attacks went unread in 51 decisions of seven measured games (2026-10-18).
     */
    private fun placed(
        position: BattleDecisionContext,
        first: BattlePokemonStateView,
        second: BattlePokemonStateView,
    ): BattleDecisionContext? {
        var pokemon = position.state.pokemon
        for (entering in listOf(first, second)) {
            val current = pokemon.firstOrNull { it.battlePokemonId == entering.battlePokemonId } ?: return null
            if (current.activeSlot != null) continue
            val slot = pokemon.firstOrNull { it.side == entering.side && it.activeSlot != null }?.activeSlot ?: 0
            pokemon = pokemon.map {
                when {
                    it.battlePokemonId == entering.battlePokemonId -> it.copyState(activeSlot = slot, statStages = emptyMap())
                    it.side == entering.side && it.activeSlot == slot -> it.copyState(activeSlot = null)
                    else -> it
                }
            }
        }
        return position.copy(state = position.state.copyState(pokemon = pokemon))
    }

    private companion object {
        const val CERTAIN = 0.999_999
    }
}
