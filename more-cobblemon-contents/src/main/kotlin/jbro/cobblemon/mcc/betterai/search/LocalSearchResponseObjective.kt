package jbro.cobblemon.mcc.betterai.search

import jbro.cobblemon.mcc.internal.ai.*

internal data class LocalResponseValue(
    val value: Double,
    val ownExecutionProbability: Double,
    val ownRemainingHpFraction: Double,
    /**
     * Change of the AI's threat-weighted material over the root turn, in material units.
     *
     * Never used to choose or weight the opponent's responses: those weights come from [value] alone,
     * so the modelled opponent keeps acting on its own interests. The root adds this afterwards.
     */
    val threatDelta: Double = 0.0,
    /** Opposing Pokemon expected to be knocked out on the root turn: the knockouts the search itself re-derived. */
    val opponentKnockouts: Double = 0.0,
)
internal data class LocalOpponentResponseValue(val action: BattleActionCandidate, val value: LocalResponseValue)

/**
 * Local Brain response objective shared independently of search scheduling.
 * Values are full-turn evaluations, not raw sampled leaf means. Unknown-response reserves,
 * projection effects and root heuristic corrections remain the caller's responsibilities.
 * Keep input ordering: tied worst values retain the first response's execution diagnostic.
 */
internal object LocalSearchResponseObjective {
    fun worstCaseWeight(tier: BattleTrainerTier): Double = when (tier) {
        BattleTrainerTier.INTRODUCTORY -> 0.20
        BattleTrainerTier.STANDARD -> 0.40
        BattleTrainerTier.ADVANCED -> 0.65
        BattleTrainerTier.BOSS -> 0.85
    }

    fun aggregate(
        values: List<LocalOpponentResponseValue>,
        memory: BattleTacticalMemoryView,
        profile: BattleTrainerProfile,
        situations: Set<BattleSituation>,
    ): LocalResponseValue? {
        if (values.isEmpty()) return null
        val robust = robust(values.map(LocalOpponentResponseValue::value), profile.difficulty.tier)
        val learned = LocalOpponentResponseModel.distribution(
            actions = values.map(LocalOpponentResponseValue::action),
            memory = memory,
            information = profile.personality.information,
            situations = situations,
        ) ?: return robust
        val categories = values.groupBy { LocalOpponentResponseModel.responsePattern(it.action) }
            .filterKeys { pattern -> pattern.isNotEmpty() && pattern.all {
                it == BattlePredictedResponse.MOVE || it == BattlePredictedResponse.SWITCH
            } }
        val weightedCategories = categories.mapNotNull { (_, responses) ->
            val mass = responses.sumOf { learned.weights[it.action] ?: 0.0 }
            if (mass <= 0.0 || !mass.isFinite()) null else mass to robust(
                responses.map(LocalOpponentResponseValue::value), profile.difficulty.tier,
            )
        }
        val learnedTotal = weightedCategories.sumOf { it.first }
        if (learnedTotal <= 0.0 || !learnedTotal.isFinite()) return robust
        val modeled = LocalResponseValue(
            value = weightedCategories.sumOf { (mass, response) -> response.value * mass } / learnedTotal,
            ownExecutionProbability = weightedCategories.sumOf { (mass, response) ->
                response.ownExecutionProbability * mass
            } / learnedTotal,
            ownRemainingHpFraction = weightedCategories.minOf { (_, response) ->
                response.ownRemainingHpFraction
            },
            threatDelta = weightedCategories.sumOf { (mass, response) -> response.threatDelta * mass } / learnedTotal,
            opponentKnockouts = weightedCategories.sumOf { (mass, response) -> response.opponentKnockouts * mass } / learnedTotal,
        )
        return LocalResponseValue(
            value = robust.value * (1.0 - learned.influence) + modeled.value * learned.influence,
            ownExecutionProbability = robust.ownExecutionProbability * (1.0 - learned.influence) +
                modeled.ownExecutionProbability * learned.influence,
            ownRemainingHpFraction = minOf(robust.ownRemainingHpFraction, modeled.ownRemainingHpFraction),
            threatDelta = robust.threatDelta * (1.0 - learned.influence) + modeled.threatDelta * learned.influence,
            opponentKnockouts = robust.opponentKnockouts * (1.0 - learned.influence) + modeled.opponentKnockouts * learned.influence,
        )
    }

    /**
     * [base] with [INTENT_WEIGHT] of it moved to the responses' expected value under the predicted
     * [probabilities] (one per response, summing to 1). The rest keeps the worst-case share [base] has,
     * so a wrong prediction still meets the cautious reading.
     */
    fun withIntent(
        base: LocalResponseValue,
        values: List<LocalOpponentResponseValue>,
        probabilities: List<Double>,
        weight: Double = INTENT_WEIGHT,
    ): LocalResponseValue {
        require(values.size == probabilities.size)
        if (values.isEmpty() || weight <= 0.0) return base
        fun expected(of: (LocalResponseValue) -> Double) = values.indices.sumOf { of(values[it].value) * probabilities[it] }
        fun blend(baseValue: Double, predicted: Double) = baseValue * (1.0 - weight) + predicted * weight
        return LocalResponseValue(
            value = blend(base.value, expected(LocalResponseValue::value)),
            ownExecutionProbability = blend(base.ownExecutionProbability, expected(LocalResponseValue::ownExecutionProbability)),
            ownRemainingHpFraction = base.ownRemainingHpFraction,
            threatDelta = blend(base.threatDelta, expected(LocalResponseValue::threatDelta)),
            opponentKnockouts = blend(base.opponentKnockouts, expected(LocalResponseValue::opponentKnockouts)),
        )
    }

    /** How much of the root response value the opponent-intent prediction takes. */
    const val INTENT_WEIGHT = 0.3

    fun robust(turns: List<LocalResponseValue>, tier: BattleTrainerTier): LocalResponseValue {
        require(turns.isNotEmpty())
        val worst = turns.minBy(LocalResponseValue::value)
        if (turns.size == 1) return worst
        val temperature = RESPONSE_SOFTMIN_TEMPERATURE
        val weights = turns.map { response -> kotlin.math.exp((worst.value - response.value) / temperature) }
        val weightTotal = weights.sum()
        val expected = if (weightTotal > 0.0 && weightTotal.isFinite()) {
            LocalResponseValue(
                value = turns.indices.sumOf { index -> turns[index].value * weights[index] } / weightTotal,
                ownExecutionProbability = turns.indices.sumOf { index ->
                    turns[index].ownExecutionProbability * weights[index]
                } / weightTotal,
                ownRemainingHpFraction = turns.minOf(LocalResponseValue::ownRemainingHpFraction),
                threatDelta = turns.indices.sumOf { index -> turns[index].threatDelta * weights[index] } / weightTotal,
                opponentKnockouts = turns.indices.sumOf { index -> turns[index].opponentKnockouts * weights[index] } / weightTotal,
            )
        } else {
            worst
        }
        val worstWeight = worstCaseWeight(tier)
        return LocalResponseValue(
            value = expected.value * (1.0 - worstWeight) + worst.value * worstWeight,
            ownExecutionProbability = expected.ownExecutionProbability * (1.0 - worstWeight) +
                worst.ownExecutionProbability * worstWeight,
            ownRemainingHpFraction = minOf(expected.ownRemainingHpFraction, worst.ownRemainingHpFraction),
            threatDelta = expected.threatDelta * (1.0 - worstWeight) + worst.threatDelta * worstWeight,
            opponentKnockouts = expected.opponentKnockouts * (1.0 - worstWeight) + worst.opponentKnockouts * worstWeight,
        )
    }

    private const val RESPONSE_SOFTMIN_TEMPERATURE = 0.35
}
