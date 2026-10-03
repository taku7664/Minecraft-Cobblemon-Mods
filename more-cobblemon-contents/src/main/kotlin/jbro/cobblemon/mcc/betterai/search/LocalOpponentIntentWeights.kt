package jbro.cobblemon.mcc.betterai.search

import jbro.cobblemon.mcc.betterai.matchup.OpponentIntent
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * The chance of each opponent response the root search examined, from [OpponentIntent]s: the product of
 * each slot's chance of its part, normalised over the responses there are. Complete public doubles
 * attack pairs may condition the focus/split share of that prior. A part the prediction does not name
 * (a guessed move, a reserve branch) takes [UNNAMED]. Null when no response is named at all.
 */
internal object LocalOpponentIntentWeights {
    fun probabilities(actions: List<BattleActionCandidate>, intents: List<OpponentIntent>, state: BattleStateView): List<Double>? {
        if (intents.isEmpty() || actions.isEmpty()) return null
        var named = false
        val raw = actions.map { action ->
            val parts = if (action.kind == BattleActionKind.COMPOSITE) action.componentActions else listOf(action)
            parts.fold(1.0) { chance, part ->
                val slot = part(part, intents, state)
                if (slot != null) named = true
                chance * (slot ?: UNNAMED)
            }
        }
        val total = raw.sum()
        if (!named || total <= 0.0 || !total.isFinite()) return null
        return LocalPublicJointIntentPrior.condition(actions, intents, state, raw.map { it / total })
    }

    private fun part(part: BattleActionCandidate, intents: List<OpponentIntent>, state: BattleStateView): Double? {
        val intent = intents.firstOrNull { it.activeSlot == part.actorSlot } ?: return null
        return when (part.kind) {
            BattleActionKind.SWITCH -> intent.options.filter { it.switchInId != null && it.switchInId == part.switchPokemonId }
                .takeIf { it.isNotEmpty() }?.sumOf { it.probability }
            BattleActionKind.USE_MOVE -> {
                val moveId = part.moveId?.let(PublicIds::canonical) ?: return null
                val same = intent.options.filter { it.moveId == moveId }.takeIf { it.isNotEmpty() } ?: return null
                val target = part.targets.singleOrNull()?.let { slot ->
                    state.pokemon.firstOrNull { it.side == slot.side && it.activeSlot == slot.slot && !it.fainted }
                }
                val aimed = same.filter { it.targetId == null || it.targetId == target?.battlePokemonId }
                // Undeclared in singles: the one target there is, so the move's whole chance.
                if (target == null) same.sumOf { it.probability } else aimed.sumOf { it.probability }
            }
            else -> null
        }
    }

    /** The chance a response part gets when the prediction does not name it. */
    const val UNNAMED = 0.02
}
