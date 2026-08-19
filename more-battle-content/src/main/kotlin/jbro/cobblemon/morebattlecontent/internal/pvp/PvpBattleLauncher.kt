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
) {
    fun launch(request: PvpBattleLaunchRequest): PvpBattleLaunchResult {
        val first = materialize.materialize(request.firstPlayerId, request.firstSelection)
        if (first !is PvpRegisteredBattleTeamResult.Created) return PvpBattleLaunchResult.Unavailable
        val second = materialize.materialize(request.secondPlayerId, request.secondSelection)
        if (second !is PvpRegisteredBattleTeamResult.Created) return PvpBattleLaunchResult.Unavailable
        val preparedPlacement = placement.prepare(request) ?: return PvpBattleLaunchResult.Unavailable
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
        } catch (_: RuntimeException) {
            false
        }
        if (activated) return result
        try {
            abortBattle(result.battleId)
        } finally {
            preparedPlacement.rollback()
        }
        return PvpBattleLaunchResult.Unavailable
    }
}
