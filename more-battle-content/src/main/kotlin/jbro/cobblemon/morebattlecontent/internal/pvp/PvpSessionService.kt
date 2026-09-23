package jbro.cobblemon.morebattlecontent.internal.pvp

import java.util.UUID

internal interface PvpSessionSnapshots<P> {
    fun snapshot(playerId: UUID, team: PvpRegisteredTeam): PvpRegisteredTeamSnapshotResult

    fun discard(playerId: UUID)
}

internal enum class PvpTeamRegistrationMutation {
    STORED,
    SNAPSHOT_REJECTED,
    UNKNOWN_MATCH,
    NOT_PARTICIPANT,
    INVALID_STATE,
}

internal enum class PvpSelectionMutation {
    SELECTION_STORED,
    WAITING_FOR_OPPONENT,
    BATTLE_STARTED,
    BATTLE_UNAVAILABLE,
    UNKNOWN_MATCH,
    NOT_PARTICIPANT,
    INVALID_STATE,
    INVALID_SELECTION,
    ENTRY_EXPIRED,
}

internal data class PvpEntryTimeoutResolution(
    val matchId: UUID,
    val autoSelectedPlayerIds: Set<UUID>,
    val launchResult: PvpSelectionMutation,
)

internal data class PvpSessionView(
    val matchId: UUID,
    val playerId: UUID,
    val opponentId: UUID,
    val format: PvpBattleFormat,
    val phase: PvpMatchPhase,
    val ownTeam: PvpRegisteredTeam,
    val opponentPreview: PvpTeamPreview,
    val selection: PvpSelectedTeam?,
    val ready: Boolean,
)

internal data class PvpSpectatorPreview(
    val matchId: UUID,
    val format: PvpBattleFormat,
    val leftPlayerId: UUID,
    val rightPlayerId: UUID,
    val leftTeam: PvpTeamPreview,
    val rightTeam: PvpTeamPreview,
)

internal fun interface PvpBattleCompletionSink {
    fun record(winnerId: UUID, loserId: UUID, format: PvpBattleFormat)
}

internal class PvpSessionService<P>(
    private val snapshots: PvpSessionSnapshots<P>,
    private val launcher: PvpBattleLauncher<P>,
    private val rules: PvpRulesPreset = PvpRulesPreset.champions(),
    private val timeSource: PvpTimeSource = SystemPvpTimeSource,
) {
    private val challenges = PvpChallengeService()
    private val matches = LinkedHashMap<UUID, PvpMatchSession>()
    private val activeBattleIds = HashMap<UUID, UUID>()
    private val timers = LinkedHashMap<UUID, PvpMatchTimer>()

    @Synchronized
    fun invite(request: PvpChallengeRequest): PvpChallengeMutationResult = challenges.invite(request)

    @Synchronized
    fun accept(matchId: UUID, playerId: UUID): PvpChallengeMutationResult {
        val result = challenges.accept(matchId, playerId)
        if (result is PvpChallengeMutationResult.Applied) {
            val request = result.challenge.request
            matches[matchId] = PvpMatchSession(
                matchId = request.challengeId,
                challengerId = request.challengerId,
                opponentId = request.opponentId,
                format = request.format,
                enabledMechanics = request.immutableEnabledMechanics,
            )
            timers[matchId] = PvpMatchTimer(
                setOf(request.challengerId, request.opponentId),
                rules,
                timeSource,
            ).also(PvpMatchTimer::beginEntrySelection)
        }
        return result
    }

    @Synchronized
    fun reject(matchId: UUID, playerId: UUID): PvpChallengeMutationResult = challenges.reject(matchId, playerId)

    @Synchronized
    fun registerTeam(
        matchId: UUID,
        playerId: UUID,
        team: PvpRegisteredTeam,
    ): PvpTeamRegistrationMutation {
        val match = matches[matchId] ?: return PvpTeamRegistrationMutation.UNKNOWN_MATCH
        if (!match.isParticipant(playerId)) return PvpTeamRegistrationMutation.NOT_PARTICIPANT
        if (match.phase != PvpMatchPhase.TEAM_REGISTRATION || team.format != match.format) {
            return PvpTeamRegistrationMutation.INVALID_STATE
        }
        if (snapshots.snapshot(playerId, team) !is PvpRegisteredTeamSnapshotResult.Stored) {
            return PvpTeamRegistrationMutation.SNAPSHOT_REJECTED
        }
        match.register(playerId, team)
        return PvpTeamRegistrationMutation.STORED
    }

    @Synchronized
    fun prepareRoomMatch(
        request: PvpChallengeRequest,
        challengerTeam: PvpRegisteredTeam,
        opponentTeam: PvpRegisteredTeam,
    ): Boolean {
        if (invite(request) !is PvpChallengeMutationResult.Applied) return false
        val accepted = accept(request.challengeId, request.opponentId)
        if (accepted !is PvpChallengeMutationResult.Applied) {
            rollbackMatchPreparation(request, null)
            return false
        }
        return registerAcceptedTeams(accepted, challengerTeam, opponentTeam) is PvpChallengeMutationResult.Applied
    }

    @Synchronized
    fun acceptRegisteredMatch(
        matchId: UUID,
        opponentId: UUID,
        challengerTeam: PvpRegisteredTeam,
        opponentTeam: PvpRegisteredTeam,
    ): PvpChallengeMutationResult {
        val accepted = accept(matchId, opponentId)
        if (accepted !is PvpChallengeMutationResult.Applied) return accepted
        return registerAcceptedTeams(accepted, challengerTeam, opponentTeam)
    }

    @Synchronized
    fun select(
        matchId: UUID,
        playerId: UUID,
        pokemonIds: List<UUID>,
    ): PvpSelectionMutation {
        val match = matches[matchId] ?: return PvpSelectionMutation.UNKNOWN_MATCH
        if (!match.isParticipant(playerId)) return PvpSelectionMutation.NOT_PARTICIPANT
        if (match.phase != PvpMatchPhase.TEAM_PREVIEW) return PvpSelectionMutation.INVALID_STATE
        if (match.isReady(playerId)) return PvpSelectionMutation.INVALID_STATE
        try {
            match.select(playerId, pokemonIds)
        } catch (_: IllegalArgumentException) {
            return PvpSelectionMutation.INVALID_SELECTION
        }
        return PvpSelectionMutation.SELECTION_STORED
    }

    @Synchronized
    fun ready(matchId: UUID, playerId: UUID): PvpSelectionMutation {
        val match = matches[matchId] ?: return PvpSelectionMutation.UNKNOWN_MATCH
        if (!match.isParticipant(playerId)) return PvpSelectionMutation.NOT_PARTICIPANT
        if (match.phase != PvpMatchPhase.TEAM_PREVIEW || match.selectionFor(playerId) == null) {
            return PvpSelectionMutation.INVALID_STATE
        }
        val timed = requireNotNull(timers[matchId]).submitEntrySelection(playerId)
        if (timed == PvpTimedSubmissionStatus.TIMED_OUT) return PvpSelectionMutation.ENTRY_EXPIRED
        match.ready(playerId)
        return if (match.phase == PvpMatchPhase.READY) launchReady(matchId) else PvpSelectionMutation.WAITING_FOR_OPPONENT
    }

    @Synchronized
    fun unready(matchId: UUID, playerId: UUID): Boolean {
        val match = matches[matchId] ?: return false
        if (!match.isParticipant(playerId)) return false
        if (match.phase != PvpMatchPhase.TEAM_PREVIEW) return false
        if (!match.isReady(playerId)) return false
        val timer = requireNotNull(timers[matchId]) { "PvP entry timer is missing" }
        check(timer.withdrawEntrySelection(playerId)) { "Ready player has no submitted PvP entry selection" }
        check(match.unready(playerId)) { "Ready player could not be returned to PvP team preview" }
        return true
    }

    @Synchronized
    fun launchReady(matchId: UUID): PvpSelectionMutation {
        val match = matches[matchId] ?: return PvpSelectionMutation.UNKNOWN_MATCH
        if (match.phase != PvpMatchPhase.READY) return PvpSelectionMutation.INVALID_STATE
        val challenge = challenges.get(matchId)
        if (challenge?.phase != PvpChallengePhase.TEAM_REGISTRATION) {
            return PvpSelectionMutation.INVALID_STATE
        }
        val firstSelection = requireNotNull(match.selectionFor(match.challengerId))
        val secondSelection = requireNotNull(match.selectionFor(match.opponentId))
        val launchResult = launcher.launch(
            PvpBattleLaunchRequest(
                matchId = match.matchId,
                firstPlayerId = match.challengerId,
                secondPlayerId = match.opponentId,
                format = match.format,
                enabledMechanics = match.enabledMechanics,
                firstSelection = firstSelection,
                secondSelection = secondSelection,
            ),
        )
        if (launchResult !is PvpBattleLaunchResult.Started) {
            return PvpSelectionMutation.BATTLE_UNAVAILABLE
        }
        val transition = challenges.startMatch(matchId)
        check(transition is PvpChallengeMutationResult.Applied) { "PvP challenge failed to become active" }
        match.markActive()
        activeBattleIds[matchId] = launchResult.battleId
        return PvpSelectionMutation.BATTLE_STARTED
    }

    @Synchronized
    fun viewFor(playerId: UUID): PvpSessionView? {
        val challenge = challenges.forPlayer(playerId) ?: return null
        val match = matches[challenge.request.challengeId] ?: return null
        if (match.phase !in setOf(PvpMatchPhase.TEAM_PREVIEW, PvpMatchPhase.READY, PvpMatchPhase.ACTIVE)) return null
        val opponentId = if (playerId == match.challengerId) match.opponentId else match.challengerId
        return PvpSessionView(
            matchId = match.matchId,
            playerId = playerId,
            opponentId = opponentId,
            format = match.format,
            phase = match.phase,
            ownTeam = requireNotNull(match.registeredTeamFor(playerId)),
            opponentPreview = match.previewFor(playerId),
            selection = match.selectionFor(playerId),
            ready = match.isReady(playerId),
        )
    }

    @Synchronized
    fun spectatorPreview(matchId: UUID): PvpSpectatorPreview? {
        val match = matches[matchId] ?: return null
        if (match.phase !in setOf(PvpMatchPhase.TEAM_PREVIEW, PvpMatchPhase.READY)) return null
        return PvpSpectatorPreview(
            matchId = match.matchId,
            format = match.format,
            leftPlayerId = match.challengerId,
            rightPlayerId = match.opponentId,
            leftTeam = match.publicPreviewOf(match.challengerId),
            rightTeam = match.publicPreviewOf(match.opponentId),
        )
    }

    @Synchronized
    fun challengeFor(playerId: UUID): PvpChallenge? = challenges.forPlayer(playerId)

    @Synchronized
    fun challenge(matchId: UUID): PvpChallenge? = challenges.get(matchId)

    @Synchronized
    fun entryDeadlineFor(matchId: UUID): Long? = timers[matchId]?.entryDeadlineMillis()

    @Synchronized
    fun battleIdFor(matchId: UUID): UUID? = activeBattleIds[matchId]

    @Synchronized
    fun timerForBattle(battleId: UUID): PvpMatchTimer? {
        val matchId = activeBattleIds.entries.firstOrNull { it.value == battleId }?.key ?: return null
        return timers[matchId]
    }

    @Synchronized
    fun activeBattles(): Map<UUID, UUID> = LinkedHashMap(activeBattleIds)

    /** Drops every server-owned session after callers have attempted to terminate its live battles. */
    @Synchronized
    fun clear() {
        val participantIds = matches.values
            .flatMap { match -> listOf(match.challengerId, match.opponentId) }
            .toSet()
        activeBattleIds.clear()
        timers.clear()
        matches.clear()
        challenges.clear()

        runCleanupActions(
            *participantIds.map { playerId ->
                { snapshots.discard(playerId) }
            }.toTypedArray(),
        )
    }

    @Synchronized
    fun expireEntrySelections(): List<PvpEntryTimeoutResolution> {
        val resolutions = ArrayList<PvpEntryTimeoutResolution>()
        matches.values.toList().forEach { match ->
            if (match.phase != PvpMatchPhase.TEAM_PREVIEW) return@forEach
            val timer = timers[match.matchId] ?: return@forEach
            val timedOut = timer.entrySelectionTimeouts()
            if (timedOut.isEmpty()) return@forEach
            timedOut.forEach { playerId ->
                val team = requireNotNull(match.registeredTeamFor(playerId))
                val automatic = team.members.take(match.format.selectionSize).map(PvpPokemonRegistration::pokemonId)
                if (match.selectionFor(playerId) == null) match.select(playerId, automatic)
                match.ready(playerId)
                timer.resolveTimedOutEntrySelection(playerId)
            }
            val launchResult = if (match.phase == PvpMatchPhase.READY) {
                launchReady(match.matchId)
            } else {
                PvpSelectionMutation.WAITING_FOR_OPPONENT
            }
            resolutions += PvpEntryTimeoutResolution(match.matchId, timedOut, launchResult)
        }
        return resolutions
    }

    @Synchronized
    fun completeBattle(
        matchId: UUID,
        battleId: UUID,
        winnerId: UUID,
        loserId: UUID,
        completionSink: PvpBattleCompletionSink,
    ): Boolean {
        val match = matches[matchId] ?: return false
        if (match.phase != PvpMatchPhase.ACTIVE || activeBattleIds[matchId] != battleId) return false
        if (winnerId == loserId || !match.isParticipant(winnerId) || !match.isParticipant(loserId)) return false
        if (setOf(winnerId, loserId) != setOf(match.challengerId, match.opponentId)) return false
        if (challenges.get(matchId)?.phase != PvpChallengePhase.ACTIVE) return false

        // Keep the active match intact until the paired record has committed. The battle-end callback
        // can then retry the exact same settlement instead of silently losing both players' result.
        completionSink.record(winnerId, loserId, match.format)
        match.complete()
        val transition = challenges.complete(matchId)
        check(transition is PvpChallengeMutationResult.Applied) { "PvP challenge failed to complete" }
        cleanup(match)
        return true
    }

    /** Explicitly owns active-participant disconnect instead of relying on Cobblemon to emit onEnd. */
    @Synchronized
    fun disconnectActiveBattle(playerId: UUID, terminateBattle: (UUID) -> Unit): UUID? {
        val challenge = challenges.forPlayer(playerId) ?: return null
        val match = matches[challenge.request.challengeId] ?: return null
        if (match.phase != PvpMatchPhase.ACTIVE || !match.isParticipant(playerId)) return null
        val battleId = activeBattleIds[match.matchId] ?: return null
        try {
            terminateBattle(battleId)
        } finally {
            // Battle termination normally invokes the onEnd callback synchronously. If an integration
            // failure skips that callback, cancel the still-owned match here so snapshots cannot leak.
            if (activeBattleIds[match.matchId] == battleId) {
                cancelBattle(match.matchId, battleId)
            }
        }
        return match.matchId
    }

    @Synchronized
    fun cancelBattle(matchId: UUID, battleId: UUID): Boolean {
        val match = matches[matchId] ?: return false
        if (match.phase != PvpMatchPhase.ACTIVE || activeBattleIds[matchId] != battleId) return false
        val transition = challenges.abort(matchId)
        check(transition is PvpChallengeMutationResult.Applied) { "PvP challenge failed to cancel" }
        cleanup(match)
        return true
    }

    @Synchronized
    fun cancel(matchId: UUID, playerId: UUID): PvpChallengeMutationResult {
        val challenge = challenges.get(matchId)
        val result = challenges.cancel(matchId, playerId)
        if (result is PvpChallengeMutationResult.Applied && challenge != null) {
            cleanup(challenge.request)
        }
        return result
    }

    private fun cleanup(match: PvpMatchSession) {
        cleanup(match.matchId, match.challengerId, match.opponentId)
    }

    private fun cleanup(request: PvpChallengeRequest) {
        cleanup(request.challengeId, request.challengerId, request.opponentId)
    }

    private fun cleanup(matchId: UUID, challengerId: UUID, opponentId: UUID) {
        activeBattleIds.remove(matchId)
        timers.remove(matchId)
        matches.remove(matchId)

        runCleanupActions(
            { challenges.discard(matchId) },
            { snapshots.discard(challengerId) },
            { snapshots.discard(opponentId) },
        )
    }

    private fun registerAcceptedTeams(
        accepted: PvpChallengeMutationResult.Applied,
        challengerTeam: PvpRegisteredTeam,
        opponentTeam: PvpRegisteredTeam,
    ): PvpChallengeMutationResult {
        val request = accepted.challenge.request
        try {
            val challengerStored =
                registerTeam(request.challengeId, request.challengerId, challengerTeam) ==
                PvpTeamRegistrationMutation.STORED
            val opponentStored = challengerStored &&
                registerTeam(request.challengeId, request.opponentId, opponentTeam) ==
                PvpTeamRegistrationMutation.STORED
            if (opponentStored) return accepted
        } catch (failure: Throwable) {
            rollbackMatchPreparation(request, failure)
            throw failure
        }
        rollbackMatchPreparation(request, null)
        return PvpChallengeMutationResult.Rejected(PvpChallengeMutationError.INVALID_PHASE)
    }

    private fun rollbackMatchPreparation(request: PvpChallengeRequest, primaryFailure: Throwable?) {
        try {
            check(cancel(request.challengeId, request.challengerId) is PvpChallengeMutationResult.Applied) {
                "PvP match could not be cancelled during preparation rollback"
            }
        } catch (cleanupFailure: Throwable) {
            if (primaryFailure == null) throw cleanupFailure
            if (primaryFailure !== cleanupFailure) primaryFailure.addSuppressed(cleanupFailure)
        }
    }

    private fun runCleanupActions(vararg actions: () -> Unit) {
        var failure: Throwable? = null
        actions.forEach { action ->
            try {
                action()
            } catch (error: Exception) {
                val original = failure
                if (original == null) failure = error else if (original !== error) original.addSuppressed(error)
            } catch (error: LinkageError) {
                val original = failure
                if (original == null) failure = error else if (original !== error) original.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}
