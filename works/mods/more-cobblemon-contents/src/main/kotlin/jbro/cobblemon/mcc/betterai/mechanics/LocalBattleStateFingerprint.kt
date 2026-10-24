package jbro.cobblemon.mcc.betterai.mechanics

import java.util.IdentityHashMap
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
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
    /** Exact structural pieces: long observation text is shared instead of copied into every board key.
     * Hashes only select a bucket; equality still compares every piece, including collision cases.
     */
    internal class Key internal constructor(private val pieces: List<String>) {
        private val hash = pieces.hashCode()
        override fun hashCode(): Int = hash
        override fun equals(other: Any?): Boolean = this === other ||
            other is Key && hash == other.hash && pieces == other.pieces
        override fun toString(): String = pieces.joinToString("")
    }

    private val compactByIdentity = IdentityHashMap<BattleStateView, Key>()
    private val compactActionsByIdentity = IdentityHashMap<BattleStateView, Key>()
    private val compactWithoutHpByIdentity = IdentityHashMap<BattleStateView, Key>()

    fun keyOf(state: BattleStateView): Key = compactByIdentity[state] ?: compact(state, 0).also { compactByIdentity[state] = it }
    fun keyWithoutHp(state: BattleStateView): Key = compactWithoutHpByIdentity[state] ?:
        compact(state, 1).also { compactWithoutHpByIdentity[state] = it }
    fun keyOfActionInputs(state: BattleStateView): Key = compactActionsByIdentity[state] ?:
        compact(state, 2).also { compactActionsByIdentity[state] = it }

    private fun compact(state: BattleStateView, mode: Int): Key {
        val pieces = ArrayList<String>(state.pokemon.size + 3)
        pieces.add("${state.battleId}:${state.format}:${state.turn}|")
        for (pokemon in state.pokemon.sortedBy { it.battlePokemonId }) {
            val part = pokemonByIdentity.getOrPut(pokemon) { pokemonText(pokemon) }
            pieces.add(when {
                mode == 1 && !pokemon.fainted && pokemon.hpFraction > 0.0 ->
                    part.text.substring(0, part.hpStart) + "1" + part.text.substring(part.hpEnd)
                mode == 2 && pokemon.activeSlot != null ->
                    part.text.substring(0, part.hpStart) + hpBand(pokemon.hpFraction) + part.text.substring(part.hpEnd)
                else -> part.text
            })
        }
        pieces.add(buildString { appendField(state) })
        pieces.add(evidence(state))
        return Key(pieces)
    }

    private val byIdentity = IdentityHashMap<BattleStateView, String>()
    private val actionInputsByIdentity = IdentityHashMap<BattleStateView, String>()
    private val withoutHpByIdentity = IdentityHashMap<BattleStateView, String>()

    fun of(state: BattleStateView): String = byIdentity[state] ?: build(state)

    /** Same full state key with only living HP erased, built from existing text without copying a battle board. */
    fun ofWithoutHp(state: BattleStateView): String = withoutHpByIdentity[state] ?: run {
        val full = of(state)
        var offset = full.indexOf('|') + 1
        var from = 0
        val key = buildString(full.length) {
            for (pokemon in state.pokemon.sortedBy { it.battlePokemonId }) {
                val part = pokemonByIdentity.getValue(pokemon)
                if (!pokemon.fainted && pokemon.hpFraction > 0.0) {
                    append(full, from, offset + part.hpStart).append('1')
                    from = offset + part.hpEnd
                }
                offset += part.text.length
            }
            append(full, from, full.length)
        }
        withoutHpByIdentity[state] = key
        key
    }

    /**
     * [of] with each active Pokemon's HP reduced to what a side's action list reads from it: standing or
     * not, full (Gale Wings), below half (how early a recovery move is listed). Positions a damage roll
     * apart share an action list, and the list is exactly the same for them.
     */
    fun ofActionInputs(state: BattleStateView): String =
        actionInputsByIdentity[state] ?: build(state).let { actionInputsByIdentity.getValue(state) }

    /**
     * Both keys of [state] at once, remembered for it; returns [of]'s. A leaf asks for both, and they differ only in
     * the active Pokemon's HP, so the band form is the full key with those HP values replaced instead of a second
     * build of every Pokemon's sorted sets and stat text.
     */
    private fun build(state: BattleStateView): String {
        val bands = ArrayList<Int>()
        val full = buildString { build(state, bands) }
        val banded = if (bands.isEmpty()) full else buildString(full.length) {
            var from = 0
            for (i in bands.indices step 3) {
                append(full, from, bands[i]).append(bands[i + 2].toChar())
                from = bands[i + 1]
            }
            append(full, from, full.length)
        }
        byIdentity[state] = full
        actionInputsByIdentity[state] = banded
        return full
    }

    /** Appends [state]'s full key; [bands] gets start, end and band character of every active Pokemon's HP text. */
    private fun StringBuilder.build(state: BattleStateView, bands: MutableList<Int>) {
        append(state.battleId).append(':').append(state.format).append(':').append(state.turn).append('|')
        state.pokemon.sortedBy { it.battlePokemonId }.forEach { pokemon ->
            val key = pokemonByIdentity.getOrPut(pokemon) { pokemonText(pokemon) }
            val offset = length
            append(key.text)
            for (i in key.bands.indices step 3) {
                bands.add(offset + key.bands[i])
                bands.add(offset + key.bands[i + 1])
                bands.add(key.bands[i + 2])
            }
        }
        appendField(state)
        // Protect chains, last hits and public ability hypotheses can change mechanics without
        // changing the visible HP, ranks or field. Keep that evidence in the calculation key.
        append(evidence(state))
    }

    private fun StringBuilder.appendField(state: BattleStateView) {
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

    // Public Pokemon views are immutable; unchanged members are shared by projected boards.
    // Keep their exact text and local HP offsets once, without weakening structural equality.
    private class PokemonText(val text: String, val bands: IntArray, val hpStart: Int, val hpEnd: Int)
    private val pokemonByIdentity = IdentityHashMap<BattlePokemonStateView, PokemonText>()

    private fun pokemonText(pokemon: BattlePokemonStateView): PokemonText {
        val bands = ArrayList<Int>(3)
        var hpStart = 0
        var hpEnd = 0
        val text = buildString {
            append(pokemon.battlePokemonId).append(':')
            append(pokemon.side.ordinal).append(':')
            append(pokemon.activeSlot ?: -1).append(':')
            hpStart = length
            append(pokemon.hpFraction)
            hpEnd = length
            if (pokemon.activeSlot != null) {
                bands.add(hpStart)
                bands.add(length)
                bands.add(hpBand(pokemon.hpFraction).code)
            }
            append(':')
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
            append(pokemon.knownBaseAbilityId ?: "?").append(':')
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
            pokemon.statStages.toSortedMap().forEach { (stat, stage) ->
                append(stat).append('=').append(stage).append(',')
            }
            append(';')
        }
        return PokemonText(text, bands.toIntArray(), hpStart, hpEnd)
    }

    /**
     * The observation and inference part of the key. Search positions derived from one another share these lists,
     * and printing every event again was most of a mid-battle key; the text is kept per list pair.
     */
    private val evidenceByEvents = IdentityHashMap<List<*>, Pair<List<*>, String>>()

    private fun evidence(state: BattleStateView): String {
        evidenceByEvents[state.observedEvents]?.let { (inferences, text) -> if (inferences === state.inferences) return text }
        val text = jbro.cobblemon.mcc.betterai.calculation.LocalDecisionCalculationScope.reuse("stateEvidence",
            jbro.cobblemon.mcc.betterai.calculation.LocalDecisionCalculationScope.Identity(state.observedEvents),
            jbro.cobblemon.mcc.betterai.calculation.LocalDecisionCalculationScope.Identity(state.inferences)) { buildString {
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
        } }
        evidenceByEvents[state.observedEvents] = state.inferences to text
        return text
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
        append(effect.stacks).append(':').append(effect.sourcePokemonId).append(':')
            .append(effect.targetSlot).append(':').append(effect.sourceMoveId).append(';')
    }
}
