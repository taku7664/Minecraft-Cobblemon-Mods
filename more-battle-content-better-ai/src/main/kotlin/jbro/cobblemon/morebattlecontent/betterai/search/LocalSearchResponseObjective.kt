package jbro.cobblemon.morebattlecontent.betterai.search

import jbro.cobblemon.morebattlecontent.api.ai.*

internal data class LocalResponseValue(
    val value: Double,
    val ownExecutionProbability: Double,
    val ownRemainingHpFraction: Double,
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
        val categories = values.groupBy { LocalOpponentResponseModel.responseKind(it.action) }
            .filterKeys { it == BattlePredictedResponse.MOVE || it == BattlePredictedResponse.SWITCH }
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
        )
        return LocalResponseValue(
            value = robust.value * (1.0 - learned.influence) + modeled.value * learned.influence,
            ownExecutionProbability = robust.ownExecutionProbability * (1.0 - learned.influence) +
                modeled.ownExecutionProbability * learned.influence,
            ownRemainingHpFraction = minOf(robust.ownRemainingHpFraction, modeled.ownRemainingHpFraction),
        )
    }

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
        )
    }

    private const val RESPONSE_SOFTMIN_TEMPERATURE = 0.35
}
