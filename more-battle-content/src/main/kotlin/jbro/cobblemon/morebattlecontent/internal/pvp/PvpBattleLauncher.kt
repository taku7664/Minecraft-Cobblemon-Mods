package jbro.cobblemon.morebattlecontent.internal.pvp

import java.util.Collections
import java.util.UUID

internal data class PvpBattleLaunchRequest(
    val matchId: UUID,
    val firstPlayerId: UUID,
    val secondPlayerId: UUID,
    val format: PvpBattleFormat,
    val enabledMechanics: Set<PvpBattleMechanic> = PvpRoomDefaults.ENABLED_MECHANICS,
    val firstSelection: PvpSelectedTeam,
    val secondSelection: PvpSelectedTeam,
) {
    init {
        require(firstPlayerId != secondPlayerId) { "A PvP battle requires two different players" }
        require(firstSelection.format == format && secondSelection.format == format) {
            "PvP selections must match the requested battle format"
        }
    }

    val immutableEnabledMechanics: Set<PvpBattleMechanic> = enabledMechanics.toSet()
}

internal class PvpPreparedBattle<P> internal constructor(
    val request: PvpBattleLaunchRequest,
    firstTeam: Collection<P>,
    secondTeam: Collection<P>,
) {
    val firstTeam: List<P> = Collections.unmodifiableList(ArrayList(firstTeam))
    val secondTeam: List<P> = Collections.unmodifiableList(ArrayList(secondTeam))
}

internal sealed interface PvpBattleLaunchResult {
    data class Started(val battleId: UUID) : PvpBattleLaunchResult
    data object Unavailable : PvpBattleLaunchResult
}

internal fun interface PvpBattleTeamMaterializer<P> {
    fun materialize(playerId: UUID, selection: PvpSelectedTeam): PvpRegisteredBattleTeamResult<P>
}

internal fun interface PvpBattleRuntime<P> {
    fun start(prepared: PvpPreparedBattle<P>): PvpBattleLaunchResult
}

/**
 * Prepares any world placement that must exist before a battle runtime creates Cobblemon actors.
 * Activation binds the prepared placement to the successfully-created battle.
 */
internal fun interface PvpBattlePlacement {
    fun prepare(request: PvpBattleLaunchRequest): PvpPreparedBattlePlacement?
}

internal interface PvpPreparedBattlePlacement {
    fun activate(startedBattleId: UUID): Boolean
    fun rollback()
}

private object NoOpPvpBattlePlacement : PvpPreparedBattlePlacement {
    override fun activate(startedBattleId: UUID): Boolean = true
    override fun rollback() = Unit
}

internal class PvpBattleLauncher<P>(
    private val materialize: PvpBattleTeamMaterializer<P>,
    private val runtime: PvpBattleRuntime<P>,
    private val placement: PvpBattlePlacement = PvpBattlePlacement { NoOpPvpBattlePlacement },
    private val abortBattle: (UUID) -> Unit = {},
    private val diagnostics: (String) -> Unit = {},
) {
    fun launch(request: PvpBattleLaunchRequest): PvpBattleLaunchResult {
        val first = materialize.materialize(request.firstPlayerId, request.firstSelection)
        if (first !is PvpRegisteredBattleTeamResult.Created) {
            diagnostics(
                "match ${request.matchId}: the team of ${request.firstPlayerId} could not be " +
                    "materialized: ${first.describe()}",
            )
            return PvpBattleLaunchResult.Unavailable
        }
        val second = materialize.materialize(request.secondPlayerId, request.secondSelection)
        if (second !is PvpRegisteredBattleTeamResult.Created) {
            diagnostics(
                "match ${request.matchId}: the team of ${request.secondPlayerId} could not be " +
                    "materialized: ${second.describe()}",
            )
            return PvpBattleLaunchResult.Unavailable
        }
        val preparedPlacement = placement.prepare(request) ?: run {
            diagnostics("match ${request.matchId}: no lounge placement could be prepared")
            return PvpBattleLaunchResult.Unavailable
        }
        val result = try {
            runtime.start(PvpPreparedBattle(request, first.members, second.members))
        } catch (exception: RuntimeException) {
            preparedPlacement.rollback()
            throw exception
        }
        if (result !is PvpBattleLaunchResult.Started) {
            preparedPlacement.rollback()
            return result
        }
        val activated = try {
            preparedPlacement.activate(result.battleId)
        } catch (exception: RuntimeException) {
            diagnostics(
                "match ${request.matchId}: lounge placement threw while activating battle " +
                    "${result.battleId}: ${exception.message}",
            )
            false
        }
        if (activated) return result
        diagnostics(
            "match ${request.matchId}: lounge placement could not be activated for battle " +
                "${result.battleId}; aborting the battle",
        )
        try {
            abortBattle(result.battleId)
        } finally {
            preparedPlacement.rollback()
        }
        return PvpBattleLaunchResult.Unavailable
    }

    private companion object {
        fun PvpRegisteredBattleTeamResult<*>.describe(): String = when (this) {
            is PvpRegisteredBattleTeamResult.Created -> "created"
            PvpRegisteredBattleTeamResult.NoSnapshot -> "no registered team snapshot is stored"
            is PvpRegisteredBattleTeamResult.SnapshotMismatch ->
                "the snapshot no longer matches Pokemon $pokemonId"
            is PvpRegisteredBattleTeamResult.CopyFailed ->
                "the battle copy of Pokemon $pokemonId failed: ${cause.message}"
        }
    }
}
