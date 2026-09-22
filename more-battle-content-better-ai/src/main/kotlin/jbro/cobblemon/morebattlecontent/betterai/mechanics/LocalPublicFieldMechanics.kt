package jbro.cobblemon.morebattlecontent.betterai.mechanics

import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTimedEffectView

/** Shared field gates for mechanics whose answer must agree across damage, speed, items and grounding. */
internal object LocalPublicFieldMechanics {
    fun magicRoomActive(state: BattleStateView): Boolean = roomActive(state, "magicroom")

    fun wonderRoomActive(state: BattleStateView): Boolean = roomActive(state, "wonderroom")

    fun trickRoomActive(state: BattleStateView): Boolean = roomActive(state, "trickroom")

    fun effectiveWeatherId(state: BattleStateView): String? {
        val weatherSuppressed = state.pokemon.any { pokemon ->
            pokemon.activeSlot != null && !pokemon.fainted && pokemon.hpFraction > 0.0 &&
                LocalPublicAbilityState.effectiveKnownAbility(state, pokemon) in WEATHER_SUPPRESSING_ABILITIES
        }
        if (weatherSuppressed) return null
        return state.field.weather?.takeIf(::active)?.effectId?.let(::canonical)
    }

    fun terrainId(state: BattleStateView): String? = state.field.terrain?.takeIf(::active)?.effectId?.let(::canonical)

    private fun roomActive(state: BattleStateView, id: String): Boolean = state.field.roomEffects.any {
        active(it) && canonical(it.effectId) == id
    }

    private fun active(effect: BattleTimedEffectView): Boolean = effect.remainingTurns?.let { it > 0 } ?: true

    private fun canonical(value: String?): String = value
        ?.substringAfter(':')
        ?.lowercase()
        ?.filter(Char::isLetterOrDigit)
        .orEmpty()

    private val WEATHER_SUPPRESSING_ABILITIES = setOf("airlock", "cloudnine")
}
