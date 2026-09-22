package jbro.cobblemon.morebattlecontent.internal.pvp

import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit

internal interface PvpTimeSource {
    fun epochMillis(): Long
    fun monotonicMillis(): Long
}

internal object SystemPvpTimeSource : PvpTimeSource {
    override fun epochMillis(): Long = System.currentTimeMillis()

    override fun monotonicMillis(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime())
}

internal enum class PvpTimedSubmissionStatus {
    ACCEPTED,
    ALREADY_SUBMITTED,
    TIMED_OUT,
    STALE_TURN,
    NOT_REQUIRED,
    NOT_STARTED,
    REJECTED,
}

internal class PvpTurnSubmission internal constructor(
    internal val timerIdentity: Any,
    internal val turnIdentity: Any?,
    internal val turnId: Long,
    internal val playerId: UUID,
    internal val receivedAtMonotonicMillis: Long,
)

internal class PvpMatchTimer(
    participants: Set<UUID>,
    private val rules: PvpRulesPreset,
    private val timeSource: PvpTimeSource = SystemPvpTimeSource,
) {
    private val identity = Any()
    private val participants = Collections.unmodifiableSet(LinkedHashSet(participants))
    private val remainingPersonalMillis = participants.associateWith {
        Math.multiplyExact(rules.totalBattleSecondsPerPlayer.toLong(), MILLIS_PER_SECOND)
    }.toMutableMap()
    private var entryDeadlineEpochMillis: Long? = null
    private var entryStartedMonotonicMillis: Long? = null
    private val entrySubmissions = LinkedHashSet<UUID>()
    private var turn: TurnClock? = null

    init {
        require(this.participants.size == 2) { "A PvP timer requires exactly two participants" }
    }

    @Synchronized
    fun beginEntrySelection() {
        check(entryDeadlineEpochMillis == null) { "PvP entry selection has already started" }
        val deadlineEpochMillis = deadlineAfterEpoch(rules.entrySelectionSeconds)
        val startedMonotonicMillis = monotonicNow()
        entryDeadlineEpochMillis = deadlineEpochMillis
        entryStartedMonotonicMillis = startedMonotonicMillis
    }

    @Synchronized
    fun submitEntrySelection(playerId: UUID): PvpTimedSubmissionStatus {
        requireParticipant(playerId)
        if (playerId in entrySubmissions) return PvpTimedSubmissionStatus.ALREADY_SUBMITTED
        if (entryStartedMonotonicMillis == null) return PvpTimedSubmissionStatus.NOT_STARTED
        if (entrySelectionTimedOut()) return PvpTimedSubmissionStatus.TIMED_OUT
        entrySubmissions += playerId
        return PvpTimedSubmissionStatus.ACCEPTED
    }

    @Synchronized
    fun entrySelectionTimeouts(): Set<UUID> {
        if (entryStartedMonotonicMillis == null) return emptySet()
        return if (entrySelectionTimedOut()) {
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
    fun entryDeadlineMillis(): Long? = entryDeadlineEpochMillis

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
        turn = TurnClock(turnId, monotonicNow(), LinkedHashSet(requiredPlayers))
    }

    @Synchronized
    fun submitTurn(turnId: Long, playerId: UUID): PvpTimedSubmissionStatus {
        return submitTurn(captureTurnSubmission(turnId, playerId))
    }

    @Synchronized
    fun captureTurnSubmission(turnId: Long, playerId: UUID): PvpTurnSubmission {
        requireParticipant(playerId)
        val active = turn?.takeIf { it.turnId == turnId }
        val submission = PvpTurnSubmission(identity, active?.identity, turnId, playerId, monotonicNow())
        if (
            active != null &&
            playerId in active.requiredPlayers &&
            playerId !in active.resolvedPlayers() &&
            elapsedBetween(active.startedAtMillis, submission.receivedAtMonotonicMillis) < allowedMillis(playerId)
        ) {
            active.pendingSubmissions.getOrPut(playerId, ::LinkedHashSet) += submission
        }
        return submission
    }

    @Synchronized
    fun submitTurn(submission: PvpTurnSubmission): PvpTimedSubmissionStatus {
        require(submission.timerIdentity === identity) { "PvP turn submission belongs to another timer" }
        requireParticipant(submission.playerId)
        val active = turn ?: return PvpTimedSubmissionStatus.NOT_STARTED
        if (submission.turnId != active.turnId) return PvpTimedSubmissionStatus.STALE_TURN
        if (submission.turnIdentity !== active.identity) {
            return PvpTimedSubmissionStatus.STALE_TURN
        }
        if (submission in active.rejectedSubmissions) return PvpTimedSubmissionStatus.REJECTED
        releasePending(active, submission)
        if (submission.playerId !in active.requiredPlayers) {
            return PvpTimedSubmissionStatus.NOT_REQUIRED
        }
        if (submission.playerId in active.submittedPlayers) {
            return PvpTimedSubmissionStatus.ALREADY_SUBMITTED
        }
        if (submission.playerId in active.timedOutPlayers) {
            return PvpTimedSubmissionStatus.TIMED_OUT
        }

        val elapsed = elapsedBetween(active.startedAtMillis, submission.receivedAtMonotonicMillis)
        val allowed = allowedMillis(submission.playerId)
        if (elapsed >= allowed) {
            if (!active.pendingSubmissions[submission.playerId].isNullOrEmpty()) {
                return PvpTimedSubmissionStatus.TIMED_OUT
            }
            consume(submission.playerId, allowed)
            active.timedOutPlayers += submission.playerId
            return PvpTimedSubmissionStatus.TIMED_OUT
        }
        consume(submission.playerId, elapsed)
        active.submittedPlayers += submission.playerId
        return PvpTimedSubmissionStatus.ACCEPTED
    }

    @Synchronized
    fun rejectTurnSubmission(submission: PvpTurnSubmission) {
        require(submission.timerIdentity === identity) { "PvP turn submission belongs to another timer" }
        val active = turn ?: return
        if (submission.turnId == active.turnId && submission.turnIdentity === active.identity) {
            if (submission.playerId !in active.resolvedPlayers()) {
                active.rejectedSubmissions += submission
            }
            releasePending(active, submission)
        }
    }

    @Synchronized
    fun turnTimeouts(turnId: Long): Set<UUID> {
        val active = turn ?: return emptySet()
        if (turnId != active.turnId) return emptySet()
        val elapsed = elapsedSince(active.startedAtMillis)
        val newlyTimedOut = LinkedHashSet<UUID>()
        active.requiredPlayers.forEach { playerId ->
            if (
                playerId !in active.submittedPlayers &&
                playerId !in active.timedOutPlayers &&
                active.pendingSubmissions[playerId].isNullOrEmpty()
            ) {
                val allowed = allowedMillis(playerId)
                if (elapsed >= allowed) {
                    consume(playerId, allowed)
                    active.timedOutPlayers += playerId
                    newlyTimedOut += playerId
                }
            }
        }
        return Collections.unmodifiableSet(newlyTimedOut)
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

    private fun releasePending(active: TurnClock, submission: PvpTurnSubmission) {
        val pending = active.pendingSubmissions[submission.playerId] ?: return
        pending -= submission
        if (pending.isEmpty()) active.pendingSubmissions.remove(submission.playerId)
    }

    private fun deadlineAfterEpoch(seconds: Int): Long = Math.addExact(
        epochNow(),
        Math.multiplyExact(seconds.toLong(), MILLIS_PER_SECOND),
    )

    private fun entrySelectionTimedOut(): Boolean =
        elapsedSince(checkNotNull(entryStartedMonotonicMillis)) >=
            Math.multiplyExact(rules.entrySelectionSeconds.toLong(), MILLIS_PER_SECOND)

    private fun elapsedSince(startedAtMillis: Long): Long {
        return elapsedBetween(startedAtMillis, monotonicNow())
    }

    private fun elapsedBetween(startedAtMillis: Long, currentMillis: Long): Long {
        check(currentMillis >= startedAtMillis) { "PvP monotonic time moved backwards" }
        return currentMillis - startedAtMillis
    }

    private fun epochNow(): Long = timeSource.epochMillis().also {
        require(it >= 0) { "PvP epoch time must be non-negative" }
    }

    private fun monotonicNow(): Long = timeSource.monotonicMillis()

    private fun requireParticipant(playerId: UUID) {
        require(playerId in participants) { "Player is not part of this PvP timer" }
    }

    private class TurnClock(
        val turnId: Long,
        val startedAtMillis: Long,
        val requiredPlayers: Set<UUID>,
        val identity: Any = Any(),
        val submittedPlayers: MutableSet<UUID> = LinkedHashSet(),
        val timedOutPlayers: MutableSet<UUID> = LinkedHashSet(),
        val pendingSubmissions: MutableMap<UUID, MutableSet<PvpTurnSubmission>> = LinkedHashMap(),
        val rejectedSubmissions: MutableSet<PvpTurnSubmission> =
            Collections.newSetFromMap(WeakHashMap()),
    ) {
        fun resolvedPlayers(): Set<UUID> = submittedPlayers + timedOutPlayers
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
