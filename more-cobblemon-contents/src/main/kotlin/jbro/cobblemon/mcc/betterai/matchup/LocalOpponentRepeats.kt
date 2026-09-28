package jbro.cobblemon.mcc.betterai.matchup

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.BattleObservedEventKind
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * What the opponent did the last times the same Pokemon faced each other, kept for one battle.
 *
 * The intent predictor reads each turn afresh from the board, so a trainer that answers the same matchup the
 * same way every time was never read: the AI clicked Close Combat into the Ghost that switched in on it three
 * times running. A player reads that by the second time. Each repeat of a matchup moves the predicted chances
 * toward what was done there before: half of them after one sighting, three quarters after two, [MAXIMUM_SHARE]
 * from three on.
 *
 * A matchup is the AI's active Pokemon and the opponent's, and the opposing Pokemon whose choice it is. A move
 * it used counts, and so does a switch it made instead of moving; a replacement after it fainted is no choice.
 */
internal class LocalOpponentRepeats {
    private val seen = HashMap<String, MutableMap<String, Int>>()
    private var pendingTurn = -1
    private var pending: Map<UUID, Pair<String, Int>> = emptyMap()

    /** Records what the opponent did on the turn last decided, then remembers this turn's matchups. */
    @Synchronized
    fun observe(state: BattleStateView) {
        if (pendingTurn >= 0 && state.turn > pendingTurn) {
            for ((pokemonId, keyed) in pending) {
                val (key, slot) = keyed
                val action = actionOn(state, pendingTurn, pokemonId, slot) ?: continue
                seen.getOrPut(key, ::HashMap).merge(action, 1, Int::plus)
            }
        }
        pendingTurn = state.turn
        pending = keys(state)
    }

    /** [intents] with each one's chances moved toward what that Pokemon did in this matchup before. */
    @Synchronized
    fun apply(intents: List<OpponentIntent>, state: BattleStateView): List<OpponentIntent> {
        if (seen.isEmpty()) return intents
        val keys = keys(state)
        return intents.map { intent ->
            val counts = keys[intent.pokemonId]?.first?.let(seen::get) ?: return@map intent
            val total = counts.values.sum()
            if (total <= 0) return@map intent
            val share = minOf(1.0 - Math.pow(0.5, total.toDouble()), MAXIMUM_SHARE)
            val byLabel = intent.options.groupBy(::label)
            val moved = intent.options.map { option ->
                val same = byLabel.getValue(label(option))
                val labelProbability = same.sumOf { it.probability }
                val within = if (labelProbability > 0.0) option.probability / labelProbability else 1.0 / same.size
                val repeated = (counts[label(option)] ?: 0).toDouble() / total
                option.copy(probability = option.probability * (1.0 - share) + share * repeated * within)
            }
            val sum = moved.sumOf { it.probability }
            if (sum <= 0.0) intent
            else intent.copy(options = moved.map { it.copy(probability = it.probability / sum) }.sortedByDescending { it.probability })
        }
    }

    private fun keys(state: BattleStateView): Map<UUID, Pair<String, Int>> {
        val active = state.pokemon.filter { it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
        val own = active.filter { it.side == BattleSide.ALLY }.map { it.battlePokemonId.toString() }.sorted()
        val opponents = active.filter { it.side == BattleSide.OPPONENT }
        val theirs = opponents.map { it.battlePokemonId.toString() }.sorted()
        val board = own.joinToString(",") + "|" + theirs.joinToString(",")
        return opponents.associate { it.battlePokemonId to ("$board|${it.battlePokemonId}" to requireNotNull(it.activeSlot)) }
    }

    /** What [pokemonId], in [slot] at the start of [turn], chose that turn; null when it chose nothing public. */
    private fun actionOn(state: BattleStateView, turn: Int, pokemonId: UUID, slot: Int): String? {
        val events = state.observedEvents.filter { it.turn == turn }.sortedBy { it.sequence }
        events.firstOrNull { it.kind == BattleObservedEventKind.MOVE_USED && it.actorPokemonId == pokemonId }?.let { move ->
            return move.publicValueId?.let { "move:${PublicIds.canonical(it)}" }
        }
        if (events.any { it.kind == BattleObservedEventKind.FAINTED && it.actorPokemonId == pokemonId }) return null
        val opponents = state.pokemon.filter { it.side == BattleSide.OPPONENT }.mapTo(hashSetOf()) { it.battlePokemonId }
        return events.firstOrNull {
            it.kind == BattleObservedEventKind.SWITCHED && it.actorSlot == slot && it.actorPokemonId != pokemonId &&
                it.actorPokemonId in opponents
        }?.let { "switch:${it.actorPokemonId}" }
    }

    private fun label(option: IntentOption): String =
        option.moveId?.let { "move:${PublicIds.canonical(it)}" } ?: "switch:${option.switchInId}"

    companion object {
        const val MAXIMUM_SHARE = 0.8
    }
}
