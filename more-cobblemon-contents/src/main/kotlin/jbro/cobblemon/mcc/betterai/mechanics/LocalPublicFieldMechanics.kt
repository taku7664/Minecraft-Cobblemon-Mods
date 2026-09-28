package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.PublicIds
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.BattleTimedEffectView

/** Shared field gates for mechanics whose answer must agree across damage, speed, items and grounding. */
internal object LocalPublicFieldMechanics {
    fun magicRoomActive(state: BattleStateView): Boolean = roomActive(state, "magicroom")

    fun wonderRoomActive(state: BattleStateView): Boolean = roomActive(state, "wonderroom")

    fun trickRoomActive(state: BattleStateView): Boolean = roomActive(state, "trickroom")

    fun effectiveWeatherId(state: BattleStateView): String? = weather[state]

    fun terrainId(state: BattleStateView): String? = state.field.terrain?.takeIf(::active)?.effectId?.let(::canonical)

    // These gates are asked many times per search node about the same state; each is worked out once.
    private val weather = LocalStateMemo { state ->
        val weatherSuppressed = state.pokemon.any { pokemon ->
            pokemon.activeSlot != null && !pokemon.fainted && pokemon.hpFraction > 0.0 &&
                LocalPublicAbilityState.effectiveKnownAbility(state, pokemon) in WEATHER_SUPPRESSING_ABILITIES
        }
        if (weatherSuppressed) null else state.field.weather?.takeIf(::active)?.effectId?.let(::canonical)
    }

    private val activeRooms = LocalStateMemo { state ->
        state.field.roomEffects.filter(::active).mapTo(hashSetOf()) { canonical(it.effectId) }
    }

    private fun roomActive(state: BattleStateView, id: String): Boolean = id in activeRooms[state]

    private fun active(effect: BattleTimedEffectView): Boolean = effect.remainingTurns?.let { it > 0 } ?: true

    private fun canonical(value: String?): String = value?.let(PublicIds::canonical)
        .orEmpty()

    private val WEATHER_SUPPRESSING_ABILITIES = setOf("airlock", "cloudnine")
}
