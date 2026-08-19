package jbro.cobblemon.morebattlecontent.internal.pvp

import java.util.Collections
import java.util.UUID

internal enum class PvpTimedSubmissionStatus {
    ACCEPTED,
    ALREADY_SUBMITTED,
    TIMED_OUT,
    STALE_TURN,
    NOT_REQUIRED,
    NOT_STARTED,
}

internal class PvpMatchTimer(
    participants: Set<UUID>,
    private val rules: PvpRulesPreset,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    private val participants = Collections.unmodifiableSet(LinkedHashSet(participants))
    private val remainingPersonalMillis = participants.associateWith {
        Math.multiplyExact(rules.totalBattleSecondsPerPlayer.toLong(), MILLIS_PER_SECOND)
    }.toMutableMap()
    private var entryDeadlineMillis: Long? = null
    private val entrySubmissions = LinkedHashSet<UUID>()
    private var turn: TurnClock? = null

    init {
        require(this.participants.size == 2) { "A PvP timer requires exactly two participants" }
    }

    @Synchronized
    fun beginEntrySelection() {
        check(entryDeadlineMillis == null) { "PvP entry selection has already started" }
        entryDeadlineMillis = deadlineAfter(rules.entrySelectionSeconds)
    }

    @Synchronized
    fun submitEntrySelection(playerId: UUID): PvpTimedSubmissionStatus {
        requireParticipant(playerId)
        if (playerId in entrySubmissions) return PvpTimedSubmissionStatus.ALREADY_SUBMITTED
        val deadline = entryDeadlineMillis ?: return PvpTimedSubmissionStatus.NOT_STARTED
        if (now() >= deadline) return PvpTimedSubmissionStatus.TIMED_OUT
        entrySubmissions += playerId
        return PvpTimedSubmissionStatus.ACCEPTED
    }

    @Synchronized
    fun entrySelectionTimeouts(): Set<UUID> {
        val deadline = entryDeadlineMillis ?: return emptySet()
        return if (now() >= deadline) {
            Collections.unmodifiableSet(LinkedHashSet(participants - entrySubmissions))
        } else {
            emptySet()
        }
    }

    @Synchronized
    fun resolveTimedOutEntrySelection(playerId: UUID) {
        requireParticipant(playerId)
        check(playerId in entrySelectionTimeouts()) { "PvP entry selection has not timed out for this player" }
        entrySubmissions += playerId
    }

    @Synchronized
    fun entryDeadlineMillis(): Long? = entryDeadlineMillis

    @Synchronized
    fun beginTurn(turnId: Long, requiredPlayers: Set<UUID>) {
        require(turnId > 0) { "PvP turn ID must be positive" }
        require(requiredPlayers.isNotEmpty() && participants.containsAll(requiredPlayers)) {
            "PvP turn choices must belong to match participants"
        }
        turn?.let { previous ->
            require(turnId > previous.turnId) { "PvP turn IDs must increase" }
            check(previous.resolvedPlayers().containsAll(previous.requiredPlayers)) {
                "The previous PvP turn still has unresolved choices"
            }
        }
        turn = TurnClock(turnId, now(), LinkedHashSet(requiredPlayers))
    }

    @Synchronized
    fun submitTurn(turnId: Long, playerId: UUID): PvpTimedSubmissionStatus {
        requireParticipant(playerId)
        val active = turn ?: return PvpTimedSubmissionStatus.NOT_STARTED
        if (turnId != active.turnId) return PvpTimedSubmissionStatus.STALE_TURN
        if (playerId !in active.requiredPlayers) return PvpTimedSubmissionStatus.NOT_REQUIRED
        if (playerId in active.submittedPlayers) return PvpTimedSubmissionStatus.ALREADY_SUBMITTED
        if (playerId in active.timedOutPlayers) return PvpTimedSubmissionStatus.TIMED_OUT

        val elapsed = elapsedSince(active.startedAtMillis)
        val allowed = allowedMillis(playerId)
        if (elapsed >= allowed) {
            consume(playerId, allowed)
            active.timedOutPlayers += playerId
            return PvpTimedSubmissionStatus.TIMED_OUT
        }
        consume(playerId, elapsed)
        active.submittedPlayers += playerId
        return PvpTimedSubmissionStatus.ACCEPTED
    }

    @Synchronized
    fun turnTimeouts(turnId: Long): Set<UUID> {
        val active = turn ?: return emptySet()
        if (turnId != active.turnId) return emptySet()
        val elapsed = elapsedSince(active.startedAtMillis)
        active.requiredPlayers.forEach { playerId ->
            if (playerId !in active.submittedPlayers && playerId !in active.timedOutPlayers) {
                val allowed = allowedMillis(playerId)
                if (elapsed >= allowed) {
                    consume(playerId, allowed)
                    active.timedOutPlayers += playerId
                }
            }
        }
        return Collections.unmodifiableSet(LinkedHashSet(active.timedOutPlayers))
    }

    @Synchronized
    fun remainingPersonalTime(playerId: UUID): Long {
        requireParticipant(playerId)
        return remainingPersonalMillis.getValue(playerId)
    }

    private fun allowedMillis(playerId: UUID): Long = minOf(
        remainingPersonalMillis.getValue(playerId),
        Math.multiplyExact(rules.turnSelectionSeconds.toLong(), MILLIS_PER_SECOND),
    )

    private fun consume(playerId: UUID, elapsedMillis: Long) {
        val remaining = remainingPersonalMillis.getValue(playerId)
        remainingPersonalMillis[playerId] = (remaining - elapsedMillis).coerceAtLeast(0)
    }

    private fun deadlineAfter(seconds: Int): Long = Math.addExact(
        now(),
        Math.multiplyExact(seconds.toLong(), MILLIS_PER_SECOND),
    )

    private fun elapsedSince(startedAtMillis: Long): Long {
        val current = now()
        check(current >= startedAtMillis) { "PvP monotonic time moved backwards" }
        return current - startedAtMillis
    }

    private fun now(): Long = currentTimeMillis().also { require(it >= 0) { "PvP monotonic time must be non-negative" } }

    private fun requireParticipant(playerId: UUID) {
        require(playerId in participants) { "Player is not part of this PvP timer" }
    }

    private class TurnClock(
        val turnId: Long,
        val startedAtMillis: Long,
        val requiredPlayers: Set<UUID>,
        val submittedPlayers: MutableSet<UUID> = LinkedHashSet(),
        val timedOutPlayers: MutableSet<UUID> = LinkedHashSet(),
    ) {
        fun resolvedPlayers(): Set<UUID> = submittedPlayers + timedOutPlayers
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
