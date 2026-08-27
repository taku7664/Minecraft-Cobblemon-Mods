package jbro.cobblemon.morebattlecontent.betterai.mechanics

import java.util.IdentityHashMap
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTimedEffectView
import kotlin.math.roundToInt

/**
 * A structural key for a projected battle state, memoized by object identity.
 *
 * Everything the search caches used to be keyed by the state *object*. Projection always allocates a
 * fresh one, so two positions that are identical in every respect that matters shared nothing: the
 * leaf evaluation ran again, and with it a full public tactical calculation for every damaging move on
 * both sides, which is the most expensive thing in the search by a wide margin. The search's own
 * value memo already keyed structurally and showed the hit rate was there to be had.
 *
 * Building the key is not free either, so it is computed once per distinct object and remembered.
 * Identity is a sound memo for that direction - the same object always has the same key - while the
 * key is what makes different objects share.
 *
 * One instance belongs to one search. It holds references to every state it has seen, which is exactly
 * the lifetime of the decision that created it.
 */
internal class LocalBattleStateFingerprint {
    private val byIdentity = IdentityHashMap<BattleStateView, String>()

    fun of(state: BattleStateView): String = byIdentity.getOrPut(state) { build(state) }

    private fun build(state: BattleStateView): String = buildString {
        append(state.turn).append('|')
        state.pokemon.sortedBy { it.battlePokemonId }.forEach { pokemon ->
            append(pokemon.battlePokemonId).append(':')
            append(pokemon.side.ordinal).append(':')
            append(pokemon.activeSlot ?: -1).append(':')
            append((pokemon.hpFraction * 10_000).roundToInt()).append(':')
            append(pokemon.statusId ?: "-").append(':')
            append(pokemon.formId ?: "-").append(':')
            append(pokemon.knownHeldItemId ?: "-").append(':')
            append(pokemon.actionConstraints.taunted).append(':')
            append(pokemon.actionConstraints.encoreMoveId ?: "-").append(':')
            append(pokemon.actionConstraints.trapped).append(':')
            append(pokemon.actionConstraints.mustRecharge).append(':')
            pokemon.statStages.toSortedMap().forEach { (stat, stage) ->
                append(stat).append('=').append(stage).append(',')
            }
            append(';')
        }
        BattleSide.entries.forEach { side ->
            append("remaining:").append(side.ordinal).append('=')
                .append(state.remainingPokemonBySide.getValue(side)).append(';')
            state.field.sideConditions.getValue(side).sortedBy { it.effectId }.forEach { effect ->
                appendTimedEffect("side:${side.ordinal}", effect)
            }
        }
        appendTimedEffect("weather", state.field.weather)
        appendTimedEffect("terrain", state.field.terrain)
        state.field.roomEffects.sortedBy { it.effectId }.forEach { appendTimedEffect("room", it) }
        state.field.globalEffects.sortedBy { it.effectId }.forEach { appendTimedEffect("global", it) }
    }

    private fun StringBuilder.appendTimedEffect(scope: String, effect: BattleTimedEffectView?) {
        if (effect == null) return
        append(scope).append(':').append(effect.effectId).append(':')
        append(effect.remainingTurns).append(':')
        append(effect.remainingTurnsRange?.minimum).append('-')
            .append(effect.remainingTurnsRange?.maximum).append(':')
        append(effect.stacks).append(';')
    }
}
