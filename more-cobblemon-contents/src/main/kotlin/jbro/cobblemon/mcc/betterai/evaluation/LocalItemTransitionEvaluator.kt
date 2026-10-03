package jbro.cobblemon.mcc.betterai.evaluation

import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.betterai.state.LocalEndTurnStateProjector
import jbro.cobblemon.mcc.internal.ai.*

/** Prices an item transition after its real body damage, preserving ownership of projected value. */
internal object LocalItemTransitionEvaluator {
    internal data class Score(val total: Double = 0.0, val statStageUtility: Double = 0.0,
        /** The subset already re-priced by immediate material/status/order/stage projection. */
        val itemUtility: Double = 0.0)

    fun evaluate(before: BattleStateView, after: BattleStateView, context: BattleDecisionContext,
        cache: LocalProjectedActionCalculationCache, tuning: LocalDecisionTuning): Score {
        if (before.pokemon.zip(after.pokemon).none { (old, next) ->
                old.knownHeldItemId != next.knownHeldItemId || old.statStages != next.statStages
            }) return Score()
        val beforeResidual = LocalEndTurnStateProjector.project(before)
        val afterResidual = LocalEndTurnStateProjector.project(after)
        val residualValue = LocalBoardMaterial.evaluate(afterResidual) - LocalBoardMaterial.evaluate(beforeResidual)
        // Orbs can change public status at the residual event. Restore HP for pressure comparison,
        // so residual HP itself is billed only once.
        fun pressureState(base: BattleStateView, residual: BattleStateView) = base.copyState(pokemon = base.pokemon.map { pokemon ->
            val next = residual.pokemon.first { it.battlePokemonId == pokemon.battlePokemonId }
            pokemon.copyState(statusId = next.statusId, statStages = next.statStages)
        })
        val beforePressure = pressureState(before, beforeResidual)
        val afterPressure = pressureState(after, afterResidual)
        fun pressure(state: BattleStateView) =
            LocalLookaheadStateEvaluator.attackPressure(state, BattleSide.ALLY, context, cache, tuning = tuning, capDamageToRemainingHp = true) -
                LocalLookaheadStateEvaluator.attackPressure(state, BattleSide.OPPONENT, context, cache, tuning = tuning, capDamageToRemainingHp = true)
        val pressureValue = pressure(afterPressure) - pressure(beforePressure)
        val speedValue = LocalStatStageMarginalEvaluator.speedTransitionBoardDelta(beforePressure, afterPressure, tuning)
        // White Herb's restored stages belong to this item, unlike the retained root price of a
        // Choice item. A pure-status lookahead must not also withdraw them as ordinary setup.
        val stagePressure = LocalStatStageMarginalEvaluator.transitionValue(beforePressure, afterPressure,
            context, cache, tuning).pressureBoardDelta
        val statusValue = LocalImmediateTurnScorer.positionEffectValue(afterResidual, context) -
            LocalImmediateTurnScorer.positionEffectValue(beforeResidual, context)
        return Score(total = (residualValue + pressureValue + speedValue + statusValue) * 100.0,
            itemUtility = (residualValue + speedValue + statusValue + stagePressure) * 100.0)
    }
}
