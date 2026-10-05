package jbro.cobblemon.mcc.betterai.search

import jbro.cobblemon.mcc.internal.ai.*
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

        val weights = if (jbro.cobblemon.mcc.betterai.evaluation.LocalActiveTuning.current().doublesJointResponses) slotWeights(actions, moveRate, switchRate) ?: return null
            else wholeWeights(actions, moveRate, switchRate) ?: return null

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

    /** Each whole response takes its category's rate, shared among the responses of that category. */
    private fun wholeWeights(actions: List<BattleActionCandidate>, moveRate: Double, switchRate: Double): Map<BattleActionCandidate, Double>? {
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
        return weights
    }

    /**
     * Codex 8d0328d5, under [jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning.doublesJointResponses]: the rates
     * apply to each slot's part, and a doubles response is the product of its parts' chances.
     */
    private fun slotWeights(actions: List<BattleActionCandidate>, moveRate: Double, switchRate: Double): Map<BattleActionCandidate, Double>? {
        val rates = mapOf(
            BattlePredictedResponse.MOVE to moveRate,
            BattlePredictedResponse.SWITCH to switchRate,
        )
        val partsByAction = actions.associateWith(::choiceParts)
        val categoriesBySlot = partsByAction.values.flatten().groupBy { it.actorSlot }.mapValues { (_, parts) ->
            parts.distinctBy { it.actionId }.groupBy(::slotKind).filterKeys(rates::containsKey)
        }
        if (categoriesBySlot.values.none { it.size > 1 }) return null
        val weightsBySlot = categoriesBySlot.mapValues { (_, categories) ->
            val totalRate = categories.keys.sumOf { rates.getValue(it) }
            if (totalRate <= 0.0 || !totalRate.isFinite()) return null
            categories.flatMap { (kind, parts) ->
                parts.map { it.actionId to rates.getValue(kind) / totalRate / parts.size }
            }.toMap()
        }
        val raw = partsByAction.mapValues { (_, parts) ->
            // A forced pass consumes no behavioural choice. Unrelated OTHER actions keep zero mass.
            if (parts.isEmpty()) 0.0 else parts.fold(1.0) { weight, part ->
                weight * (weightsBySlot[part.actorSlot]?.get(part.actionId) ?: 0.0)
            }
        }
        val total = raw.values.sum()
        if (total <= 0.0 || !total.isFinite()) return null
        return raw.mapValues { (_, weight) -> weight / total }
    }

    /** Ordered slot categories preserve MOVE+SWITCH separately from SWITCH+MOVE. */
    fun responsePattern(action: BattleActionCandidate): List<BattlePredictedResponse> =
        choiceParts(action).sortedBy { it.actorSlot }.map(::slotKind)

    private fun slotKind(action: BattleActionCandidate): BattlePredictedResponse = when (action.kind) {
        BattleActionKind.SWITCH -> BattlePredictedResponse.SWITCH
        BattleActionKind.USE_MOVE -> BattlePredictedResponse.MOVE
        else -> BattlePredictedResponse.OTHER
    }

    private fun choiceParts(action: BattleActionCandidate): List<BattleActionCandidate> = when (action.kind) {
        BattleActionKind.COMPOSITE -> action.componentActions.flatMap(::choiceParts)
        BattleActionKind.WAIT -> emptyList()
        else -> listOf(action)
    }

    fun responseKind(action: BattleActionCandidate): BattlePredictedResponse = when (action.kind) {
        BattleActionKind.SWITCH -> BattlePredictedResponse.SWITCH
        BattleActionKind.USE_MOVE -> BattlePredictedResponse.MOVE
        BattleActionKind.COMPOSITE -> when {
            action.componentActions.any { it.kind == BattleActionKind.SWITCH } -> BattlePredictedResponse.SWITCH
            action.componentActions.any { it.kind == BattleActionKind.USE_MOVE } -> BattlePredictedResponse.MOVE
            else -> BattlePredictedResponse.OTHER
        }
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
