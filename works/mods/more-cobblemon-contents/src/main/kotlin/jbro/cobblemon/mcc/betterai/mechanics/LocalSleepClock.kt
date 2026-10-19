package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.BattleMoveOutcomeKind
import jbro.cobblemon.mcc.internal.ai.BattleObservedEventKind
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * The chance a sleeping Pokemon gets a move off this turn, from the public record of its sleep.
 *
 * Showdown's sleep counter starts at 2, 3 or 4 (evenly) and Rest's at 3; each attempt to move takes one off, and the
 * attempt that reaches 0 wakes it and it moves. So it wakes on its next attempt with chance
 * ```
 * Rest:  1 once it has tried twice since, else 0
 * other: 0 / 1/3 / 1/2 / 1 after 0 / 1 / 2 / 3+ attempts
 * ```
 * While it sleeps, Sleep Talk (seen) calls one of its other three moves at random: a third for any one of them.
 * ```
 * acts = wake + (1 - wake) x (Sleep Talk ? 1/3 : 0)
 * ```
 * Read flat at a half, a Dondozo two turns into Rest that woke and Wave Crashed was taken to sleep on, and a 22%
 * Conkeldurr stayed to Drain Punch it (hand-played Bea, 2026-10-20). Null when [pokemon] is not asleep or its sleep was
 * not seen starting (it came in asleep from before the record).
 */
internal object LocalSleepClock {
    fun acts(state: BattleStateView, pokemon: BattlePokemonStateView): Double? {
        val left = remaining(state, pokemon) ?: return null
        val wake = left.filter { it.first == 0 }.sumOf { it.second }
        return wake + (1.0 - wake) * (if (talks(pokemon)) SLEEP_TALK_SHARE else 0.0)
    }

    /**
     * The turns [pokemon] still sleeps through before the one it moves on, with their chances: Rest's are fixed, other
     * sleep's spread evenly over what its counter can still hold. Null when it is not asleep or its sleep was not seen
     * starting.
     */
    fun remaining(state: BattleStateView, pokemon: BattlePokemonStateView): List<Pair<Int, Double>>? {
        if (PublicIds.canonical(pokemon.statusId.orEmpty()) !in SLEEP) return null
        val events = state.observedEvents
        val fell = events.lastOrNull { event ->
            event.kind == BattleObservedEventKind.STATUS_CHANGED && event.actorPokemonId == pokemon.battlePokemonId &&
                PublicIds.canonical(event.publicValueId.orEmpty()) in SLEEP
        } ?: return null
        val rest = events.any { event ->
            event.kind == BattleObservedEventKind.MOVE_USED && event.actorPokemonId == pokemon.battlePokemonId &&
                event.turn == fell.turn && event.sequence < fell.sequence && PublicIds.canonical(event.publicValueId.orEmpty()) == REST
        }
        // Each later turn it used a move (Sleep Talk) or was stopped by its sleep is one attempt.
        val attempts = events.filter { event ->
            event.sequence > fell.sequence && event.turn > fell.turn && (
                event.kind == BattleObservedEventKind.MOVE_USED && event.actorPokemonId == pokemon.battlePokemonId ||
                    event.kind == BattleObservedEventKind.MOVE_OUTCOME && event.moveOutcome?.kind == BattleMoveOutcomeKind.CANNOT_ACT &&
                    (event.actorPokemonId == pokemon.battlePokemonId || pokemon.battlePokemonId in event.targetPokemonIds))
        }.map { it.turn }.distinct().size
        // A counter of c wakes it on attempt c: (c - 1 - attempts) sleeping turns are left.
        val counters = if (rest) listOf(REST_COUNTER) else COUNTERS
        val possible = counters.filter { it > attempts }.ifEmpty { listOf(attempts + 1) }
        return possible.map { (it - 1 - attempts).coerceAtLeast(0) to 1.0 / possible.size }
    }

    /** Sleep Talk seen: it calls one of the other three moves each turn it sleeps. */
    fun talks(pokemon: BattlePokemonStateView): Boolean = pokemon.knownMoveIds.any { PublicIds.canonical(it) == SLEEP_TALK }

    private val SLEEP = setOf("slp", "sleep", "asleep")
    private const val REST = "rest"
    private const val SLEEP_TALK = "sleeptalk"
    private const val REST_COUNTER = 3
    private val COUNTERS = listOf(2, 3, 4)
    const val SLEEP_TALK_SHARE = 1.0 / 3.0
}
