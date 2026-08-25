package jbro.cobblemon.morebattlecontent.betterai.search

import jbro.cobblemon.morebattlecontent.api.ai.*
import kotlin.math.abs

/**
 * Converts repeated public opponent behaviour into a cautious response prior.
 *
 * The result never replaces robust search. Callers blend it with the existing soft-min using
 * [LocalOpponentResponseDistribution.influence].
 */
internal object LocalOpponentResponseModel {
    fun distribution(
        actions: List<BattleActionCandidate>,
        memory: BattleTacticalMemoryView,
        information: Double = 1.0,
        situations: Set<BattleSituation> = setOf(BattleSituation.GENERAL),
    ): LocalOpponentResponseDistribution? {
        require(information.isFinite() && information in 0.0..1.0)
        if (actions.isEmpty()) return null
        val pairs = SITUATION_PRIORITY.mapNotNull { situation ->
            tendencyPair(situation, memory)?.let { situation to it }
        }.toMap()
        val selectedSituation = SITUATION_PRIORITY.firstOrNull { it in situations && it in pairs } ?: return null
        val selected = pairs.getValue(selectedSituation)
        val general = pairs[BattleSituation.GENERAL]
        val reliability = if (selectedSituation == BattleSituation.GENERAL || general == null) {
            1.0
        } else {
            selected.effectiveWeight / (selected.effectiveWeight + GENERAL_PRIOR_STRENGTH)
        }
        val moveRate = blend(selected.move.estimatedRate, general?.move?.estimatedRate, reliability)
        val switchRate = blend(selected.switch.estimatedRate, general?.switch?.estimatedRate, reliability)

        val grouped = actions.groupBy(::responseKind).filterKeys { it != BattlePredictedResponse.OTHER }
        if (grouped.size < 2) return null
        val categoryRates = mapOf(
            BattlePredictedResponse.MOVE to moveRate,
            BattlePredictedResponse.SWITCH to switchRate,
        ).filterKeys(grouped::containsKey)
        val totalRate = categoryRates.values.sum()
        if (totalRate <= 0.0 || !totalRate.isFinite()) return null

        val weights = linkedMapOf<BattleActionCandidate, Double>()
        categoryRates.forEach { (response, rate) ->
            val category = grouped.getValue(response)
            val perAction = rate / totalRate / category.size
            category.forEach { action -> weights[action] = perAction }
        }
        actions.filterNot(weights::containsKey).forEach { action -> weights[action] = 0.0 }

        val evidence = (selected.effectiveWeight / EVIDENCE_SATURATION_WEIGHT).coerceIn(0.0, 1.0)
        val separation = abs(moveRate - switchRate).coerceIn(0.0, 1.0)
        val missPenalty = 1.0 / (1.0 + memory.predictionCalibration.consecutiveMisses * MISS_PENALTY_RATE)
        val calibration = memory.predictionCalibration.brierSkillScoreAgainstAlwaysMove?.let { skill ->
            ((skill + 1.0) / 2.0).coerceIn(MINIMUM_CALIBRATION_FACTOR, 1.0)
        } ?: 1.0
        val behaviorStability = (1.0 - memory.opponentResponseVolatility * MAXIMUM_SHIFT_DISCOUNT)
            .coerceIn(MINIMUM_BEHAVIOR_STABILITY, 1.0)
        val influence = (MAXIMUM_INFLUENCE * information * evidence * (0.5 + separation * 0.5) *
            missPenalty * calibration * behaviorStability).coerceIn(0.0, MAXIMUM_INFLUENCE)
        return LocalOpponentResponseDistribution(weights, influence, selectedSituation)
    }

    fun responseKind(action: BattleActionCandidate): BattlePredictedResponse = when (action.kind) {
        BattleActionKind.SWITCH -> BattlePredictedResponse.SWITCH
        BattleActionKind.USE_MOVE, BattleActionKind.COMPOSITE -> BattlePredictedResponse.MOVE
        BattleActionKind.WAIT, BattleActionKind.FORFEIT -> BattlePredictedResponse.OTHER
    }

    private fun tendencyPair(
        situation: BattleSituation,
        memory: BattleTacticalMemoryView,
    ): TendencyPair? {
        val byResponse = memory.tendencies.filter { it.situation == situation }
            .associateBy(BattleTendencyView::response)
        val move = byResponse[BattlePredictedResponse.MOVE] ?: return null
        val switch = byResponse[BattlePredictedResponse.SWITCH] ?: return null
        if (minOf(move.samples, switch.samples) < MINIMUM_SAMPLES) return null
        val effectiveWeight = move.recentWeight + switch.recentWeight
        if (!effectiveWeight.isFinite() || effectiveWeight < MINIMUM_EFFECTIVE_WEIGHT) return null
        return TendencyPair(move, switch, effectiveWeight)
    }

    private fun blend(selected: Double, general: Double?, reliability: Double): Double =
        if (general == null) selected else selected * reliability + general * (1.0 - reliability)

    private data class TendencyPair(
        val move: BattleTendencyView,
        val switch: BattleTendencyView,
        val effectiveWeight: Double,
    )

    private const val MINIMUM_SAMPLES = 3
    private const val MINIMUM_EFFECTIVE_WEIGHT = 1.5
    private const val EVIDENCE_SATURATION_WEIGHT = 8.0
    private const val GENERAL_PRIOR_STRENGTH = 6.0
    private const val MAXIMUM_INFLUENCE = 0.55
    private const val MISS_PENALTY_RATE = 0.35
    private const val MINIMUM_CALIBRATION_FACTOR = 0.20
    private const val MAXIMUM_SHIFT_DISCOUNT = 0.80
    private const val MINIMUM_BEHAVIOR_STABILITY = 0.20
    private val SITUATION_PRIORITY = listOf(
        BattleSituation.UNDER_KO_THREAT,
        BattleSituation.AFTER_SETUP,
        BattleSituation.LOW_HP,
        BattleSituation.FASTER,
        BattleSituation.MECHANIC_AVAILABLE,
        BattleSituation.DOUBLE_FOCUS_TARGET,
        BattleSituation.GENERAL,
    )
}

internal data class LocalOpponentResponseDistribution(
    val weights: Map<BattleActionCandidate, Double>,
    val influence: Double,
    val situation: BattleSituation,
)
