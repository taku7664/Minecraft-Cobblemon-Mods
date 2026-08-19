package jbro.cobblemon.morebattlecontent.internal.pvp

import java.util.Collections
import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.team.RegisteredTeamSnapshotStore
import jbro.cobblemon.morebattlecontent.internal.team.TeamSnapshotCaptureResult
import jbro.cobblemon.morebattlecontent.internal.team.TeamSnapshotMaterializationResult

internal sealed interface PvpRegisteredTeamSnapshotResult {
    data object Stored : PvpRegisteredTeamSnapshotResult
    data object SourceUnavailable : PvpRegisteredTeamSnapshotResult
    data class Rejected(val pokemonId: UUID, val cause: RuntimeException? = null) : PvpRegisteredTeamSnapshotResult
}

internal sealed interface PvpRegisteredBattleTeamResult<out B> {
    class Created<B>(members: Collection<B>) : PvpRegisteredBattleTeamResult<B> {
        val members: List<B> = Collections.unmodifiableList(ArrayList(members))
    }

    data object NoSnapshot : PvpRegisteredBattleTeamResult<Nothing>
    data class SnapshotMismatch(val pokemonId: UUID) : PvpRegisteredBattleTeamResult<Nothing>
    data class CopyFailed(val pokemonId: UUID, val cause: RuntimeException) : PvpRegisteredBattleTeamResult<Nothing>
}

internal class PvpRegisteredTeamSnapshotStore<S, T, B>(
    sourcesFor: (UUID) -> Collection<S>?,
    registrationOf: (S) -> PvpPokemonRegistration,
    snapshotOf: (S, battleLevel: Int) -> T,
    battleCopyOf: (T) -> B,
) {
    private val delegate = RegisteredTeamSnapshotStore(
        sourcesFor = sourcesFor,
        registrationOf = registrationOf,
        memberIdOf = PvpPokemonRegistration::pokemonId,
        battleLevelOf = PvpPokemonRegistration::battleLevel,
        snapshotOf = snapshotOf,
        battleCopyOf = battleCopyOf,
    )

    fun snapshot(playerId: UUID, team: PvpRegisteredTeam): PvpRegisteredTeamSnapshotResult =
        when (val result = delegate.snapshot(playerId, team.members)) {
            TeamSnapshotCaptureResult.Stored -> PvpRegisteredTeamSnapshotResult.Stored
            TeamSnapshotCaptureResult.SourceUnavailable -> PvpRegisteredTeamSnapshotResult.SourceUnavailable
            is TeamSnapshotCaptureResult.RegistrationMismatch ->
                PvpRegisteredTeamSnapshotResult.Rejected(result.memberId)
            is TeamSnapshotCaptureResult.SnapshotFailed ->
                PvpRegisteredTeamSnapshotResult.Rejected(result.memberId, result.cause)
        }

    fun materialize(playerId: UUID, team: PvpSelectedTeam): PvpRegisteredBattleTeamResult<B> =
        when (val result = delegate.materialize(playerId, team.members)) {
            TeamSnapshotMaterializationResult.NoSnapshot -> PvpRegisteredBattleTeamResult.NoSnapshot
            is TeamSnapshotMaterializationResult.SnapshotMismatch ->
                PvpRegisteredBattleTeamResult.SnapshotMismatch(result.memberId)
            is TeamSnapshotMaterializationResult.CopyFailed ->
                PvpRegisteredBattleTeamResult.CopyFailed(result.memberId, result.cause)
            is TeamSnapshotMaterializationResult.Created -> PvpRegisteredBattleTeamResult.Created(result.members)
        }

    fun discard(playerId: UUID) = delegate.discard(playerId)
}
