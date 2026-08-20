package jbro.cobblemon.morebattlecontent.internal.pvp

import java.util.UUID

internal data class PvpChallengeRequest(
    val challengeId: UUID,
    val challengerId: UUID,
    val opponentId: UUID,
    val format: PvpBattleFormat,
    val enabledMechanics: Set<PvpBattleMechanic> = PvpRoomDefaults.ENABLED_MECHANICS,
) {
    init {
        require(challengerId != opponentId) { "A player cannot challenge themselves" }
    }

    val immutableEnabledMechanics: Set<PvpBattleMechanic> = enabledMechanics.toSet()
}

internal enum class PvpChallengePhase {
    PENDING,
    TEAM_REGISTRATION,
    ACTIVE,
    COMPLETED,
    REJECTED,
    CANCELLED,
}

internal data class PvpChallenge(
    val request: PvpChallengeRequest,
    val phase: PvpChallengePhase,
)

internal enum class PvpChallengeMutationError {
    UNKNOWN_CHALLENGE,
    REQUEST_CONFLICT,
    PARTICIPANT_BUSY,
    NOT_TARGET,
    NOT_PARTICIPANT,
    INVALID_PHASE,
}

internal sealed interface PvpChallengeMutationResult {
    data class Applied(val challenge: PvpChallenge) : PvpChallengeMutationResult
    data class Unchanged(val challenge: PvpChallenge) : PvpChallengeMutationResult
    data class Rejected(val error: PvpChallengeMutationError) : PvpChallengeMutationResult
}

internal fun PvpChallengeMutationResult.challengeOrNull(): PvpChallenge? = when (this) {
    is PvpChallengeMutationResult.Applied -> challenge
    is PvpChallengeMutationResult.Unchanged -> challenge
    is PvpChallengeMutationResult.Rejected -> null
}

internal fun PvpChallengeMutationResult.errorOrNull(): PvpChallengeMutationError? =
    (this as? PvpChallengeMutationResult.Rejected)?.error

internal class PvpChallengeService {
    private val challenges = LinkedHashMap<UUID, PvpChallenge>()
    private val activeByPlayer = HashMap<UUID, UUID>()

    @Synchronized
    fun invite(request: PvpChallengeRequest): PvpChallengeMutationResult {
        val existing = challenges[request.challengeId]
        if (existing != null) {
            return if (existing.request == request) {
                PvpChallengeMutationResult.Unchanged(existing)
            } else {
                PvpChallengeMutationResult.Rejected(PvpChallengeMutationError.REQUEST_CONFLICT)
            }
        }
        if (request.challengerId in activeByPlayer || request.opponentId in activeByPlayer) {
            return PvpChallengeMutationResult.Rejected(PvpChallengeMutationError.PARTICIPANT_BUSY)
        }
        val challenge = PvpChallenge(request, PvpChallengePhase.PENDING)
        challenges[request.challengeId] = challenge
        activeByPlayer[request.challengerId] = request.challengeId
        activeByPlayer[request.opponentId] = request.challengeId
        return PvpChallengeMutationResult.Applied(challenge)
    }

    @Synchronized
    fun get(challengeId: UUID): PvpChallenge? = challenges[challengeId]

    @Synchronized
    fun accept(challengeId: UUID, playerId: UUID): PvpChallengeMutationResult =
        mutateTargetPending(challengeId, playerId, PvpChallengePhase.TEAM_REGISTRATION, release = false)

    @Synchronized
    fun reject(challengeId: UUID, playerId: UUID): PvpChallengeMutationResult =
        mutateTargetPending(challengeId, playerId, PvpChallengePhase.REJECTED, release = true)

    @Synchronized
    fun cancel(challengeId: UUID, playerId: UUID): PvpChallengeMutationResult {
        val current = challenges[challengeId]
            ?: return PvpChallengeMutationResult.Rejected(PvpChallengeMutationError.UNKNOWN_CHALLENGE)
        if (playerId != current.request.challengerId && playerId != current.request.opponentId) {
            return PvpChallengeMutationResult.Rejected(PvpChallengeMutationError.NOT_PARTICIPANT)
        }
        if (current.phase !in setOf(PvpChallengePhase.PENDING, PvpChallengePhase.TEAM_REGISTRATION)) {
            return PvpChallengeMutationResult.Rejected(PvpChallengeMutationError.INVALID_PHASE)
        }
        val updated = current.copy(phase = PvpChallengePhase.CANCELLED)
        challenges[challengeId] = updated
        release(current.request)
        return PvpChallengeMutationResult.Applied(updated)
    }

    @Synchronized
    fun startMatch(challengeId: UUID): PvpChallengeMutationResult =
        transition(challengeId, PvpChallengePhase.TEAM_REGISTRATION, PvpChallengePhase.ACTIVE, release = false)

    @Synchronized
    fun complete(challengeId: UUID): PvpChallengeMutationResult =
        transition(challengeId, PvpChallengePhase.ACTIVE, PvpChallengePhase.COMPLETED, release = true)

    @Synchronized
    fun abort(challengeId: UUID): PvpChallengeMutationResult =
        transition(challengeId, PvpChallengePhase.ACTIVE, PvpChallengePhase.CANCELLED, release = true)

    @Synchronized
    fun forPlayer(playerId: UUID): PvpChallenge? = activeByPlayer[playerId]?.let(challenges::get)

    /**
     * Forgets a settled challenge so its ID can be reused. Rooms keep their ID as the challenge ID, so
     * without this a rematch inside the same room would collide with the finished challenge.
     */
    @Synchronized
    fun discard(challengeId: UUID): Boolean {
        val removed = challenges.remove(challengeId) ?: return false
        release(removed.request)
        return true
    }

    private fun mutateTargetPending(
        challengeId: UUID,
        playerId: UUID,
        targetPhase: PvpChallengePhase,
        release: Boolean,
    ): PvpChallengeMutationResult {
        val current = challenges[challengeId]
            ?: return PvpChallengeMutationResult.Rejected(PvpChallengeMutationError.UNKNOWN_CHALLENGE)
        if (playerId != current.request.opponentId) {
            return PvpChallengeMutationResult.Rejected(PvpChallengeMutationError.NOT_TARGET)
        }
        if (current.phase != PvpChallengePhase.PENDING) {
            return PvpChallengeMutationResult.Rejected(PvpChallengeMutationError.INVALID_PHASE)
        }
        val updated = current.copy(phase = targetPhase)
        challenges[challengeId] = updated
        if (release) release(current.request)
        return PvpChallengeMutationResult.Applied(updated)
    }

    private fun transition(
        challengeId: UUID,
        expectedPhase: PvpChallengePhase,
        targetPhase: PvpChallengePhase,
        release: Boolean,
    ): PvpChallengeMutationResult {
        val current = challenges[challengeId]
            ?: return PvpChallengeMutationResult.Rejected(PvpChallengeMutationError.UNKNOWN_CHALLENGE)
        if (current.phase != expectedPhase) {
            return PvpChallengeMutationResult.Rejected(PvpChallengeMutationError.INVALID_PHASE)
        }
        val updated = current.copy(phase = targetPhase)
        challenges[challengeId] = updated
        if (release) release(current.request)
        return PvpChallengeMutationResult.Applied(updated)
    }

    private fun release(request: PvpChallengeRequest) {
        activeByPlayer.remove(request.challengerId, request.challengeId)
        activeByPlayer.remove(request.opponentId, request.challengeId)
    }
}
