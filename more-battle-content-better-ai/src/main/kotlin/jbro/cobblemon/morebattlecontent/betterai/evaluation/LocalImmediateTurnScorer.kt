package jbro.cobblemon.morebattlecontent.betterai.evaluation

import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalProjectedActionCalculationCache

internal data class LocalImmediateTurnScore(
    val materialDelta: Double,
    val stageDelta: Double,
    val statusDelta: Double,
    val speedControlDelta: Double,
    val fieldDelta: Double,
) {
    val total: Double = materialDelta + stageDelta + statusDelta + speedControlDelta + fieldDelta
}

/**
 * Scores only what changed during one projected turn.
 *
 * Damage keeps separate KO/survival states with a representative roll within each class.
 * Removal value is already part of materialDelta; secondary effects can still use weighted score.
 */
internal object LocalImmediateTurnScorer {
    fun score(
        before: BattleStateView,
        after: BattleStateView,
        source: BattleDecisionContext? = null,
        calculationCache: LocalProjectedActionCalculationCache = LocalProjectedActionCalculationCache(),
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
        shouldContinue: () -> Boolean = { true },
    ): LocalImmediateTurnScore {
        val beforeMaterial = LocalBoardMaterial.evaluate(before)
        val afterMaterial = LocalBoardMaterial.evaluate(after)
        val stageDelta = source?.let {
            LocalStatStageMarginalEvaluator.transitionValue(
                before,
                after,
                it,
                calculationCache,
                tuning,
                shouldContinue,
            ).pressureBoardDelta
        } ?: 0.0
        val beforeStatus = positionStatus(before)
        val afterStatus = positionStatus(after)
        val speedControlDelta = LocalStatStageMarginalEvaluator.speedTransitionBoardDelta(before, after, tuning)
        val beforeField = positionField(before)
        val afterField = positionField(after)
        return LocalImmediateTurnScore(
            materialDelta = afterMaterial - beforeMaterial,
            stageDelta = stageDelta,
            statusDelta = afterStatus - beforeStatus,
            speedControlDelta = speedControlDelta,
            fieldDelta = afterField - beforeField,
        )
    }

    fun expectedEffectScore(
        before: BattleStateView,
        afterEffect: BattleStateView,
        probability: Double,
        source: BattleDecisionContext,
        calculationCache: LocalProjectedActionCalculationCache = LocalProjectedActionCalculationCache(),
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
        shouldContinue: () -> Boolean = { true },
    ): Double = score(
        before,
        afterEffect,
        source,
        calculationCache,
        tuning,
        shouldContinue,
    ).total * probability.coerceIn(0.0, 1.0)

    private fun positionStatus(state: BattleStateView): Double =
        sideStatusBurden(state, BattleSide.OPPONENT) - sideStatusBurden(state, BattleSide.ALLY)

    private fun sideStatusBurden(state: BattleStateView, side: BattleSide): Double = state.pokemon
        .asSequence()
        .filter { it.side == side && !it.fainted && it.hpFraction > 0.0 }
        .sumOf { statusBurden(it.statusId) }

    private fun statusBurden(statusId: String?): Double = when (canonicalId(statusId)) {
        null -> 0.0
        "tox", "toxic", "badlypoisoned" -> 0.35
        "slp", "sleep", "frz", "freeze", "frozen" -> 0.35
        "par", "paralysis", "paralyzed", "paralysed", "brn", "burn", "burned", "burnt" -> 0.25
        "psn", "poison", "poisoned" -> 0.20
        else -> 0.15
    }

    private fun positionField(state: BattleStateView): Double =
        sideFieldValue(state, BattleSide.ALLY) - sideFieldValue(state, BattleSide.OPPONENT)

    private fun sideFieldValue(state: BattleStateView, side: BattleSide): Double =
        state.field.sideConditions.getValue(side).sumOf { effect ->
            val stacks = effect.stacks ?: 1
            when (canonicalId(effect.effectId)) {
                "stealthrock", "spikes", "toxicspikes", "stickyweb" -> -HAZARD_STACK_VALUE * stacks
                "reflect", "lightscreen", "auroraveil", "safeguard", "mist" -> BENEFICIAL_SIDE_EFFECT_VALUE
                else -> 0.0
            }
        }

    private fun canonicalId(value: String?): String? = value
        ?.substringAfter(':')
        ?.lowercase()
        ?.filter(Char::isLetterOrDigit)

    private const val HAZARD_STACK_VALUE = 0.10
    private const val BENEFICIAL_SIDE_EFFECT_VALUE = 0.15
}
