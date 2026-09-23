package jbro.cobblemon.morebattlecontent.internal.spectate

import java.util.UUID

internal enum class RemoteSpectateResult {
    STARTED,
    ALREADY_SPECTATING,
    SELF_SPECTATE,
    SPECTATING_DISABLED,
    TARGET_NOT_IN_BATTLE,
    TARGET_NOT_IN_MBC_BATTLE,
    VIEWER_IN_BATTLE,
    VIEWER_SPECTATING_OTHER,
    BATTLE_UNAVAILABLE,
}

internal interface RemoteSpectateGateway {
    fun isSpectatingEnabled(): Boolean

    fun participatingBattleId(playerId: UUID): UUID?

    fun isManagedBattle(battleId: UUID): Boolean

    fun spectatedManagedBattleId(playerId: UUID): UUID?

    fun beginSpectating(battleId: UUID, targetId: UUID, viewerId: UUID): Boolean
}

internal fun beginSpectatingAtomically(
    start: () -> Unit,
    isStarted: () -> Boolean,
    complete: () -> Unit = {},
    rollback: () -> Unit,
): Boolean {
    try {
        start()
        if (isStarted()) {
            complete()
            return true
        }
    } catch (failure: RuntimeException) {
        rollbackSpectatingAndRethrow(failure, rollback)
    } catch (failure: LinkageError) {
        rollbackSpectatingAndRethrow(failure, rollback)
    }

    rollback()
    return false
}

private fun rollbackSpectatingAndRethrow(failure: Throwable, rollback: () -> Unit): Nothing {
    try {
        rollback()
    } catch (cleanupFailure: RuntimeException) {
        if (failure !== cleanupFailure) failure.addSuppressed(cleanupFailure)
    } catch (cleanupFailure: LinkageError) {
        if (failure !== cleanupFailure) failure.addSuppressed(cleanupFailure)
    }
    throw failure
}

internal class RemoteSpectateService(
    private val gateway: RemoteSpectateGateway,
) {
    fun spectate(viewerId: UUID, targetId: UUID): RemoteSpectateResult {
        if (viewerId == targetId) return RemoteSpectateResult.SELF_SPECTATE
        if (!gateway.isSpectatingEnabled()) return RemoteSpectateResult.SPECTATING_DISABLED

        val targetBattleId = gateway.participatingBattleId(targetId)
            ?: return RemoteSpectateResult.TARGET_NOT_IN_BATTLE
        if (!gateway.isManagedBattle(targetBattleId)) return RemoteSpectateResult.TARGET_NOT_IN_MBC_BATTLE
        if (gateway.participatingBattleId(viewerId) != null) return RemoteSpectateResult.VIEWER_IN_BATTLE

        when (gateway.spectatedManagedBattleId(viewerId)) {
            targetBattleId -> return RemoteSpectateResult.ALREADY_SPECTATING
            null -> Unit
            else -> return RemoteSpectateResult.VIEWER_SPECTATING_OTHER
        }

        return if (gateway.beginSpectating(targetBattleId, targetId, viewerId)) {
            RemoteSpectateResult.STARTED
        } else {
            RemoteSpectateResult.BATTLE_UNAVAILABLE
        }
    }
}
