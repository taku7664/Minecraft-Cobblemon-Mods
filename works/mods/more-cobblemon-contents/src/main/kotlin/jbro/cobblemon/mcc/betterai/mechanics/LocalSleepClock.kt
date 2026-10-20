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
        // Put to sleep by its own move that sets the counter (Rest: 3, read from the move's code).
        val ownCounter = events.lastOrNull { event ->
            event.kind == BattleObservedEventKind.MOVE_USED && event.actorPokemonId == pokemon.battlePokemonId &&
                event.turn == fell.turn && event.sequence < fell.sequence
        }?.let { LocalMoveCode.userSleepTurns(it.publicValueId) }
        // Each later turn it used a move (Sleep Talk) or was stopped by its sleep is one attempt.
        val attempts = events.filter { event ->
            event.sequence > fell.sequence && event.turn > fell.turn && (
                event.kind == BattleObservedEventKind.MOVE_USED && event.actorPokemonId == pokemon.battlePokemonId ||
                    event.kind == BattleObservedEventKind.MOVE_OUTCOME && event.moveOutcome?.kind == BattleMoveOutcomeKind.CANNOT_ACT &&
                    (event.actorPokemonId == pokemon.battlePokemonId || pokemon.battlePokemonId in event.targetPokemonIds))
        }.map { it.turn }.distinct().size
        // A counter of c wakes it on attempt c: (c - 1 - attempts) sleeping turns are left.
        val counters = ownCounter?.let(::listOf) ?: COUNTERS
        val possible = counters.filter { it > attempts }.ifEmpty { listOf(attempts + 1) }
        return possible.map { (it - 1 - attempts).coerceAtLeast(0) to 1.0 / possible.size }
    }

    /**
     * The chance [pokemon] wakes on this turn's attempt: from its counter where the record shows the sleep starting, a
     * third where it does not; null when it is not asleep.
     */
    fun wakeChance(state: BattleStateView, pokemon: BattlePokemonStateView): Double? {
        if (PublicIds.canonical(pokemon.statusId.orEmpty()) !in SLEEP) return null
        return remaining(state, pokemon)?.filter { it.first == 0 }?.sumOf { it.second } ?: UNSEEN_WAKE
    }

    /**
     * The chance [move] runs for [pokemon] this turn: a sleeper's move only as it wakes, one usable asleep (Sleep Talk,
     * Snore) only while it sleeps on; 1 when it is not asleep.
     */
    fun moveRuns(state: BattleStateView, pokemon: BattlePokemonStateView, move: jbro.cobblemon.mcc.internal.ai.BattleActionCandidate): Double {
        val wake = wakeChance(state, pokemon) ?: return 1.0
        val usableAsleep = move.moveDetails?.effects?.effects.orEmpty()
            .any { it.kind == jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind.USABLE_WHILE_ASLEEP }
        return if (usableAsleep) 1.0 - wake else wake
    }

    /** Sleep Talk seen: it calls one of the other three moves each turn it sleeps. */
    fun talks(pokemon: BattlePokemonStateView): Boolean = pokemon.knownMoveIds.any(LocalMoveCode::callsOwnMoveAsleep)

    private val SLEEP = setOf("slp", "sleep", "asleep")
    private val COUNTERS = listOf(2, 3, 4)
    const val SLEEP_TALK_SHARE = 1.0 / 3.0
    private const val UNSEEN_WAKE = 1.0 / 3.0
}
