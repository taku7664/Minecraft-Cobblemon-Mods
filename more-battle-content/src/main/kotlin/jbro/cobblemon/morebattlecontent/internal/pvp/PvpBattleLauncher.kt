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
        } catch (failure: Throwable) {
            rollbackAfterRuntimeFailure(preparedPlacement, failure)
        }
        if (result !is PvpBattleLaunchResult.Started) {
            preparedPlacement.rollback()
            return result
        }
        var activationFailure: Throwable? = null
        val activated = try {
            preparedPlacement.activate(result.battleId)
        } catch (failure: Throwable) {
            activationFailure = failure
            false
        }
        if (activated) return result
        val failureMessage = activationFailure?.let { failure ->
            "match ${request.matchId}: lounge placement threw while activating battle " +
                "${result.battleId}: ${failure.message}; aborting the battle"
        } ?: "match ${request.matchId}: lounge placement could not be activated for battle " +
            "${result.battleId}; aborting the battle"
        val cleanupFailure = try {
            cleanupAfterActivationFailure(
                preparedPlacement = preparedPlacement,
                activationFailure = activationFailure,
                abortBattle = { abortBattle(result.battleId) },
            )
            null
        } catch (failure: Throwable) {
            failure
        }
        val fatalActivationFailure = activationFailure?.takeUnless { failure ->
            failure is RuntimeException || failure is LinkageError
        }
        val primaryFailure = cleanupFailure ?: fatalActivationFailure
        reportDiagnosticsSafely(diagnostics, failureMessage, primaryFailure)
        cleanupFailure?.let { throw it }
        fatalActivationFailure?.let { throw it }
        return PvpBattleLaunchResult.Unavailable
    }

    private companion object {
        fun rollbackAfterRuntimeFailure(
            preparedPlacement: PvpPreparedBattlePlacement,
            failure: Throwable,
        ): Nothing {
            try {
                preparedPlacement.rollback()
            } catch (rollbackFailure: Throwable) {
                if (failure !== rollbackFailure) failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }

        fun cleanupAfterActivationFailure(
            preparedPlacement: PvpPreparedBattlePlacement,
            activationFailure: Throwable?,
            abortBattle: () -> Unit,
        ) {
            var failure = activationFailure
            var cleanupFailed = false

            fun recordCleanupFailure(cleanupFailure: Throwable) {
                cleanupFailed = true
                val original = failure
                if (original == null) {
                    failure = cleanupFailure
                } else if (original !== cleanupFailure) {
                    original.addSuppressed(cleanupFailure)
                }
            }

            try {
                abortBattle()
            } catch (cleanupFailure: Throwable) {
                recordCleanupFailure(cleanupFailure)
            }
            try {
                preparedPlacement.rollback()
            } catch (cleanupFailure: Throwable) {
                recordCleanupFailure(cleanupFailure)
            }
            if (cleanupFailed) throw checkNotNull(failure)
        }

        fun reportDiagnosticsSafely(
            diagnostics: (String) -> Unit,
            message: String,
            primaryFailure: Throwable?,
        ) {
            try {
                diagnostics(message)
            } catch (_: RuntimeException) {
                // Diagnostics must never prevent or replace battle cleanup.
            } catch (_: LinkageError) {
                // Compatibility diagnostics are best-effort at this boundary.
            } catch (failure: Throwable) {
                if (primaryFailure == null) throw failure
                if (primaryFailure !== failure) primaryFailure.addSuppressed(failure)
            }
        }

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
