package jbro.cobblemon.morebattlecontent.internal.pvp

import java.util.Collections
import java.util.UUID

internal enum class PvpMatchPhase {
    TEAM_REGISTRATION,
    TEAM_PREVIEW,
    READY,
    ACTIVE,
    COMPLETE,
}

internal data class PvpTeamPreviewMember(val speciesId: String, val formId: String?)

internal class PvpTeamPreview internal constructor(members: List<PvpTeamPreviewMember>) {
    val members: List<PvpTeamPreviewMember> = Collections.unmodifiableList(ArrayList(members))

    val speciesIds: List<String>
        get() = members.map(PvpTeamPreviewMember::speciesId)
}

internal class PvpMatchSession(
    val matchId: UUID,
    val challengerId: UUID,
    val opponentId: UUID,
    val format: PvpBattleFormat,
    enabledMechanics: Set<PvpBattleMechanic> = PvpRoomDefaults.ENABLED_MECHANICS,
) {
    val enabledMechanics: Set<PvpBattleMechanic> = enabledMechanics.toSet()
    var phase: PvpMatchPhase = PvpMatchPhase.TEAM_REGISTRATION
        private set
    private val registeredTeams = LinkedHashMap<UUID, PvpRegisteredTeam>()
    private val selections = LinkedHashMap<UUID, PvpSelectedTeam>()
    private val readyPlayers = LinkedHashSet<UUID>()

    init {
        require(challengerId != opponentId) { "A PvP match requires two different players" }
    }

    @Synchronized
    fun register(playerId: UUID, team: PvpRegisteredTeam) {
        requireParticipant(playerId)
        check(phase == PvpMatchPhase.TEAM_REGISTRATION) { "PvP match is not accepting team registration" }
        require(team.format == format) { "PvP registered team format does not match the match" }
        registeredTeams[playerId] = team
        if (registeredTeams.keys.containsAll(listOf(challengerId, opponentId))) {
            phase = PvpMatchPhase.TEAM_PREVIEW
        }
    }

    @Synchronized
    fun previewFor(playerId: UUID): PvpTeamPreview {
        requireParticipant(playerId)
        check(phase in setOf(PvpMatchPhase.TEAM_PREVIEW, PvpMatchPhase.READY, PvpMatchPhase.ACTIVE)) {
            "PvP team preview is unavailable"
        }
        val otherPlayer = if (playerId == challengerId) opponentId else challengerId
        return PvpTeamPreview(
            requireNotNull(registeredTeams[otherPlayer]).members.map { member ->
                PvpTeamPreviewMember(member.speciesId, member.formId)
            },
        )
    }

    @Synchronized
    fun select(playerId: UUID, pokemonIds: List<UUID>) {
        requireParticipant(playerId)
        check(phase == PvpMatchPhase.TEAM_PREVIEW) { "PvP match is not accepting private selections" }
        check(playerId !in readyPlayers) { "A ready player must unready before changing selection" }
        val team = requireNotNull(registeredTeams[playerId]) { "Player has not registered a PvP team" }
        val result = PvpTeamRules.select(team, pokemonIds, format)
        require(result is PvpTeamSelectionResult.Accepted) { "Invalid PvP team selection" }
        selections[playerId] = result.team
    }

    @Synchronized
    fun ready(playerId: UUID) {
        requireParticipant(playerId)
        check(phase == PvpMatchPhase.TEAM_PREVIEW) { "PvP match is not accepting ready changes" }
        check(playerId in selections) { "Player must select a team before becoming ready" }
        readyPlayers += playerId
        if (readyPlayers.containsAll(listOf(challengerId, opponentId))) phase = PvpMatchPhase.READY
    }

    @Synchronized
    fun unready(playerId: UUID): Boolean {
        requireParticipant(playerId)
        if (phase != PvpMatchPhase.TEAM_PREVIEW) return false
        return readyPlayers.remove(playerId)
    }

    @Synchronized
    fun isReady(playerId: UUID): Boolean {
        requireParticipant(playerId)
        return playerId in readyPlayers
    }

    @Synchronized
    fun selectionFor(playerId: UUID): PvpSelectedTeam? {
        requireParticipant(playerId)
        return selections[playerId]
    }

    @Synchronized
    fun registeredTeamFor(playerId: UUID): PvpRegisteredTeam? {
        requireParticipant(playerId)
        return registeredTeams[playerId]
    }

    @Synchronized
    fun markActive() {
        check(phase == PvpMatchPhase.READY) { "PvP match is not ready to become active" }
        phase = PvpMatchPhase.ACTIVE
    }

    @Synchronized
    fun complete() {
        check(phase == PvpMatchPhase.ACTIVE) { "PvP match is not active" }
        phase = PvpMatchPhase.COMPLETE
    }

    fun isParticipant(playerId: UUID): Boolean = playerId == challengerId || playerId == opponentId

    private fun requireParticipant(playerId: UUID) {
        require(isParticipant(playerId)) { "Player is not part of this PvP match" }
    }
}
