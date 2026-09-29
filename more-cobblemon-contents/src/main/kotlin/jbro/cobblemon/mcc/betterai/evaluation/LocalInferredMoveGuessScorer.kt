package jbro.cobblemon.mcc.betterai.evaluation

import jbro.cobblemon.mcc.internal.ai.*

/** A broad group guess is not evidence for choosing a specific counter move. */
internal object LocalInferredMoveGuessScorer {
    fun score(
        @Suppress("UNUSED_PARAMETER") candidate: BattleActionCandidate,
        @Suppress("UNUSED_PARAMETER") context: BattleDecisionContext,
    ): Double = 0.0
}
