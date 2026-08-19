package jbro.cobblemon.morebattlecontent.internal.tower

import java.util.Collections
import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.team.RegisteredTeamSnapshotStore
import jbro.cobblemon.morebattlecontent.internal.team.TeamSnapshotCaptureResult
import jbro.cobblemon.morebattlecontent.internal.team.TeamSnapshotMaterializationResult

internal sealed interface TowerRegisteredTeamSnapshotResult {
    data object Stored : TowerRegisteredTeamSnapshotResult
    data object SourceUnavailable : TowerRegisteredTeamSnapshotResult
    data class Rejected(val reason: String) : TowerRegisteredTeamSnapshotResult {
        init {
            require(reason.isNotBlank()) { "Snapshot rejection reason cannot be blank" }
        }
    }
}

internal sealed interface TowerRegisteredBattleTeamResult<out T> {
    class Created<T>(members: Collection<T>) : TowerRegisteredBattleTeamResult<T> {
        val members: List<T> = Collections.unmodifiableList(ArrayList(members))
    }

    data object NoSnapshot : TowerRegisteredBattleTeamResult<Nothing>
    data class SnapshotMismatch(val pokemonId: UUID) : TowerRegisteredBattleTeamResult<Nothing>
    data class CopyFailed(val pokemonId: UUID, val cause: RuntimeException) : TowerRegisteredBattleTeamResult<Nothing>
}

internal interface TowerRegisteredTeamSnapshots {
    fun snapshot(playerId: UUID, team: TowerRegisteredTeam): TowerRegisteredTeamSnapshotResult
    fun discard(playerId: UUID)
}

internal object UnavailableTowerRegisteredTeamSnapshots : TowerRegisteredTeamSnapshots {
    override fun snapshot(playerId: UUID, team: TowerRegisteredTeam): TowerRegisteredTeamSnapshotResult =
        TowerRegisteredTeamSnapshotResult.SourceUnavailable

    override fun discard(playerId: UUID) = Unit
}

internal class TowerRegisteredTeamSnapshotStore<S, T, B>(
    private val sourcesFor: (UUID) -> Collection<S>?,
    private val registrationOf: (S) -> TowerPokemonRegistration,
    private val snapshotOf: (S, battleLevel: Int) -> T,
    private val battleCopyOf: (T) -> B,
) : TowerRegisteredTeamSnapshots {
    private val delegate = RegisteredTeamSnapshotStore(
        sourcesFor = sourcesFor,
        registrationOf = registrationOf,
        memberIdOf = TowerPokemonRegistration::pokemonId,
        battleLevelOf = TowerPokemonRegistration::battleLevel,
        snapshotOf = snapshotOf,
        battleCopyOf = battleCopyOf,
    )

    @Synchronized
    override fun snapshot(playerId: UUID, team: TowerRegisteredTeam): TowerRegisteredTeamSnapshotResult {
        return when (val result = delegate.snapshot(playerId, team.members)) {
            TeamSnapshotCaptureResult.Stored -> TowerRegisteredTeamSnapshotResult.Stored
            TeamSnapshotCaptureResult.SourceUnavailable -> TowerRegisteredTeamSnapshotResult.SourceUnavailable
            is TeamSnapshotCaptureResult.RegistrationMismatch ->
                TowerRegisteredTeamSnapshotResult.Rejected("SnapshotMismatch:${result.memberId}")
            is TeamSnapshotCaptureResult.SnapshotFailed ->
                TowerRegisteredTeamSnapshotResult.Rejected("SnapshotFailed:${result.memberId}")
        }
    }

    @Synchronized
    fun materialize(
        playerId: UUID,
        selection: TowerSelectedTeam,
    ): TowerRegisteredBattleTeamResult<B> {
        return when (val result = delegate.materialize(playerId, selection.members)) {
            TeamSnapshotMaterializationResult.NoSnapshot -> TowerRegisteredBattleTeamResult.NoSnapshot
            is TeamSnapshotMaterializationResult.SnapshotMismatch ->
                TowerRegisteredBattleTeamResult.SnapshotMismatch(result.memberId)
            is TeamSnapshotMaterializationResult.CopyFailed ->
                TowerRegisteredBattleTeamResult.CopyFailed(result.memberId, result.cause)
            is TeamSnapshotMaterializationResult.Created -> TowerRegisteredBattleTeamResult.Created(result.members)
        }
    }

    @Synchronized
    override fun discard(playerId: UUID) {
        delegate.discard(playerId)
    }
}
