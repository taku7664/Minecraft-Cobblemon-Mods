package jbro.cobblemon.mcc.betterai.search

import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind

/** Applies confidence to an expected reply's effect, never to its absolute signed score. */
internal object LocalExpectedMoveResponseConfidence {
    fun adjust(
        values: List<LocalOpponentResponseValue>,
        /**
         * The reply with every slot unknown. A doubles opponent with one fully revealed Pokemon has no such
         * reply, so it may be null; each expected reply then falls back on its own matching baseline alone.
         */
        noResponseBaseline: Double?,
        confidence: Double,
        bestTieTolerance: Double,
        unknownReserve: Double = 0.0,
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
            ?.let { pure -> noResponseBaseline?.let {
                (it - pure.value.value).coerceAtLeast(0.0) / if (perSlotReserve()) unknownSlots(pure.action).coerceAtLeast(1) else 1
            } }
            ?: unknownReserve
        return values.map { response ->
            if (!response.action.containsTag(EXPECTED_TAG) || response.value.value <= best + bestTieTolerance) {
                response
            } else {
                val baseline = matchingNoResponseBaseline(response.action, values, reserve)
                    ?: noResponseBaseline
                    ?: return@map response
                response.copy(value = response.value.copy(
                    value = baseline + confidence * (response.value.value - baseline),
                ))
            }
        }
    }

    fun noResponseBaseline(values: List<LocalOpponentResponseValue>, reserve: Double): Double? =
        values.singleOrNull { it.action.isPureUnknownResponse() }?.let { it.value.value + reserveFor(it.action, reserve) }

    /**
     * The uncertainty reserve an unknown reply carries. With [LocalDecisionTuning.doublesSlotReplies] (Codex e7df6328)
     * each unknown submitted slot costs it once and a known action or forced pass none; off, any unknown reply costs it once.
     */
    fun reserveFor(action: BattleActionCandidate, perSlot: Double): Double = when {
        perSlotReserve() -> unknownSlots(action) * perSlot
        action.isUnknownResponse() -> perSlot
        else -> 0.0
    }

    private fun perSlotReserve(): Boolean = jbro.cobblemon.mcc.betterai.evaluation.LocalActiveTuning.current().doublesSlotReplies

    private fun unknownSlots(action: BattleActionCandidate): Int = when (action.kind) {
        BattleActionKind.COMPOSITE -> action.componentActions.sumOf(::unknownSlots)
        BattleActionKind.WAIT -> if (UNKNOWN_TAG in action.tags) 1 else 0
        else -> 0
    }

    private fun matchingNoResponseBaseline(
        expectedAction: BattleActionCandidate,
        values: List<LocalOpponentResponseValue>,
        reserve: Double,
    ): Double? {
        val desired = expectedAction.baselineKey(replaceExpected = true) ?: return null
        return values.singleOrNull { response ->
            response.action.baselineKey(replaceExpected = false) == desired
        }?.let { response ->
            response.value.value + reserveFor(response.action, reserve)
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
