package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*

/** Projects only side conditions whose public duration or stacking rule is unambiguous. */
internal object LocalFieldEffectProjector {
    fun apply(
        state: BattleStateView,
        actingSide: BattleSide,
        effect: BattleMoveEffectView,
    ): BattleStateView {
        if (effect.kind != BattleMoveEffectKind.SIDE_CONDITION) return state
        val effectId = effect.valueId ?: return state
        val canonical = canonical(effectId)
        if (canonical !in SUPPORTED_SIDE_CONDITIONS) return state
        val targetSide = when (effect.target) {
            BattleMoveEffectTarget.USER_SIDE -> actingSide
            BattleMoveEffectTarget.TARGET_SIDE -> opposite(actingSide)
            else -> return state
        }
        val current = state.field.sideConditions.getValue(targetSide)
        val existing = current.firstOrNull { canonical(it.effectId) == canonical }
        val maximumStacks = effect.amountRange?.maximum?.coerceAtLeast(1)
        val nextEffect = if (maximumStacks != null) {
            BattleTimedEffectView(
                effectId = effectId,
                remainingTurns = null,
                stacks = ((existing?.stacks ?: 0) + 1).coerceAtMost(maximumStacks),
            )
        } else {
            BattleTimedEffectView(effectId, DURATION_BY_ID.getValue(canonical))
        }
        val nextConditions = state.field.sideConditions.toMutableMap().also { bySide ->
            bySide[targetSide] = current.filterNot { canonical(it.effectId) == canonical } + nextEffect
        }
        val nextField = BattleFieldStateView(
            weather = state.field.weather,
            terrain = state.field.terrain,
            roomEffects = state.field.roomEffects,
            globalEffects = state.field.globalEffects,
            sideConditions = nextConditions,
        )
        return copyState(state, nextField)
    }

    private fun copyState(state: BattleStateView, field: BattleFieldStateView) = BattleStateView(
        battleId = state.battleId,
        format = state.format,
        turn = state.turn,
        pokemon = state.pokemon,
        field = field,
        remainingPokemonBySide = state.remainingPokemonBySide,
        observedEvents = state.observedEvents,
        inferences = state.inferences,
    )

    private fun opposite(side: BattleSide) = if (side == BattleSide.ALLY) BattleSide.OPPONENT else BattleSide.ALLY
    private fun canonical(value: String): String = value.substringAfter(':').lowercase().filter(Char::isLetterOrDigit)

    private val DURATION_BY_ID = mapOf(
        "tailwind" to 4,
        "reflect" to 5,
        "lightscreen" to 5,
        "auroraveil" to 5,
    )
    private val SUPPORTED_SIDE_CONDITIONS = DURATION_BY_ID.keys
}
