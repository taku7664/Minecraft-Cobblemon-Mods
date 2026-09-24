package jbro.cobblemon.morebattlecontent.betterai.search

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind

/** Applies confidence to an expected reply's effect, never to its absolute signed score. */
internal object LocalExpectedMoveResponseConfidence {
    fun adjust(
        values: List<LocalOpponentResponseValue>,
        noResponseBaseline: Double,
        confidence: Double,
        bestTieTolerance: Double,
    ): List<LocalOpponentResponseValue> {
        require(confidence in 0.0..1.0)
        require(bestTieTolerance >= 0.0)
        if (values.none { it.action.containsTag(EXPECTED_TAG) }) return values
        // Rank against every concrete reply. The synthetic unknown-reserve branch is a coverage
        // safeguard, not a move that can earn first place.
        val best = values.asSequence()
            .filterNot { it.action.isUnknownResponse() }
            .minOfOrNull { it.value.value }
            ?: return values
        val reserve = values.singleOrNull { it.action.isPureUnknownResponse() }
            ?.let { (noResponseBaseline - it.value.value).coerceAtLeast(0.0) }
            ?: 0.0
        return values.map { response ->
            if (!response.action.containsTag(EXPECTED_TAG) || response.value.value <= best + bestTieTolerance) {
                response
            } else {
                val baseline = matchingNoResponseBaseline(response.action, values, reserve)
                    ?: noResponseBaseline
                response.copy(value = response.value.copy(
                    value = baseline + confidence * (response.value.value - baseline),
                ))
            }
        }
    }

    fun noResponseBaseline(values: List<LocalOpponentResponseValue>, reserve: Double): Double? =
        values.singleOrNull { it.action.isPureUnknownResponse() }?.value?.value?.plus(reserve)

    private fun matchingNoResponseBaseline(
        expectedAction: BattleActionCandidate,
        values: List<LocalOpponentResponseValue>,
        reserve: Double,
    ): Double? {
        val desired = expectedAction.baselineKey(replaceExpected = true) ?: return null
        return values.singleOrNull { response ->
            response.action.baselineKey(replaceExpected = false) == desired
        }?.let { response ->
            response.value.value + if (response.action.isUnknownResponse()) reserve else 0.0
        }
    }

    /** Composite component order follows active-slot order, so only uncertain positions are replaced. */
    private fun BattleActionCandidate.baselineKey(replaceExpected: Boolean): List<String>? {
        val actions = if (kind == BattleActionKind.COMPOSITE) componentActions else listOf(this)
        if (replaceExpected && actions.none { it.containsTag(EXPECTED_TAG) }) return null
        return actions.map { action ->
            when {
                replaceExpected && action.containsTag(EXPECTED_TAG) -> UNKNOWN_COMPONENT
                action.isPureUnknownResponse() -> UNKNOWN_COMPONENT
                else -> action.actionId
            }
        }
    }

    private fun BattleActionCandidate.containsTag(tag: String): Boolean =
        tag in tags || componentActions.any { it.containsTag(tag) }

    private fun BattleActionCandidate.isPureUnknownResponse(): Boolean = when (kind) {
        BattleActionKind.COMPOSITE -> componentActions.isNotEmpty() && componentActions.all {
            it.isPureUnknownResponse()
        }
        BattleActionKind.WAIT -> UNKNOWN_TAG in tags
        else -> false
    }

    private fun BattleActionCandidate.isUnknownResponse(): Boolean =
        isPureUnknownResponse() || componentActions.any { it.isUnknownResponse() }

    private const val EXPECTED_TAG = "expected_opponent_move"
    private const val UNKNOWN_TAG = "unknown_public_response"
    private const val UNKNOWN_COMPONENT = "<unknown-response>"
}
