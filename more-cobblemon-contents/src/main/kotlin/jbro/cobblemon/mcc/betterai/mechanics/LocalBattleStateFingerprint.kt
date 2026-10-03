package jbro.cobblemon.mcc.betterai.mechanics

import java.util.IdentityHashMap
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.BattleTimedEffectView

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
    private val actionInputsByIdentity = IdentityHashMap<BattleStateView, String>()

    fun of(state: BattleStateView): String = byIdentity.getOrPut(state) { build(state) }

    /**
     * [of] with each active Pokemon's HP reduced to what a side's action list reads from it: standing or
     * not, full (Gale Wings), below half (how early a recovery move is listed). Positions a damage roll
     * apart share an action list, and the list is exactly the same for them.
     */
    fun ofActionInputs(state: BattleStateView): String = actionInputsByIdentity.getOrPut(state) { build(state, activeHpBands = true) }

    private fun build(state: BattleStateView, activeHpBands: Boolean = false): String = buildString {
        append(state.battleId).append(':').append(state.format).append(':').append(state.turn).append('|')
        state.pokemon.sortedBy { it.battlePokemonId }.forEach { pokemon ->
            append(pokemon.battlePokemonId).append(':')
            append(pokemon.side.ordinal).append(':')
            append(pokemon.activeSlot ?: -1).append(':')
            if (activeHpBands && pokemon.activeSlot != null) append(hpBand(pokemon.hpFraction)).append(':')
            else append(pokemon.hpFraction).append(':')
            append(pokemon.fainted).append(':')
            append(pokemon.speciesId).append(':').append(pokemon.level).append(':')
            append(pokemon.statusId ?: "-").append(':')
            append(pokemon.formId ?: "-").append(':')
            append(pokemon.knownTypeIds.sorted().joinToString(",")).append(':')
            append(pokemon.knownBaseStabTypeIds.sorted().joinToString(",")).append(':')
            append(pokemon.knownTeraTypeId ?: "-").append(':')
            append(pokemon.knownStellarBoostedTypeIds?.sorted()?.joinToString(",") ?: "?").append(':')
            append(pokemon.knownHeldItemId ?: "-").append(':')
            append(pokemon.knownAbilityId ?: "-").append(':')
            append(pokemon.knownMoveIds.sorted().joinToString(",")).append(':')
            // Stat ranges are a data class, including their knowledge boundary.
            append(pokemon.combatStats).append(':')
            pokemon.knownFormStates.toSortedMap().forEach { (id, form) ->
                append("form:").append(id).append(':').append(form.formId).append(':')
                append(form.knownTypeIds.sorted().joinToString(",")).append(':')
                append(form.combatStats).append(':').append(form.abilityId).append(';')
            }
            append(pokemon.actionConstraints.taunted).append(':')
            append(pokemon.actionConstraints.encoreMoveId ?: "-").append(':')
            append(pokemon.actionConstraints.trapped).append(':')
            append(pokemon.actionConstraints.mustRecharge).append(':')
            append(pokemon.knownVolatileEffectIds.sorted().joinToString(",")).append(':')
            append(pokemon.knownSubstituteHpFractionRange).append(':')
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
        // Protect chains, last hits and public ability hypotheses can change mechanics without
        // changing the visible HP, ranks or field. Keep that evidence in the calculation key.
        state.observedEvents.forEach { event ->
            append("event:").append(event.sequence).append(':').append(event.turn).append(':').append(event.kind).append(':')
            append(event.actorPokemonId).append(':').append(event.actorSlot).append(':')
            append(event.targetPokemonIds).append(':').append(event.publicValueId).append(':').append(event.hpFractionDelta).append(':')
            append(event.baseMovePriority).append(':').append(event.precedingActionSequence).append(':')
            append(event.precedingActionActorPokemonId).append(':').append(event.precedingActionMoveId).append(':')
            append(event.publicSourceEffectId).append(':').append(event.moveOutcome).append(';')
        }
        state.inferences.forEach { inference ->
            append("inference:").append(inference.subjectPokemonId).append(':').append(inference.categoryId).append(':')
            append(inference.candidateId).append(':').append(inference.confidence).append(':').append(inference.probabilityRange).append(':')
            append(inference.basis.sorted()).append(':').append(inference.evidenceEventSequences).append(':')
            append(inference.relatedPokemonId).append(':').append(inference.abilityAvailability).append(';')
        }
    }

    private fun hpBand(hp: Double): Char = when {
        hp <= 0.0 -> '0'
        hp >= 1.0 -> 'F'
        hp < 0.5 -> 'L'
        else -> 'H'
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
