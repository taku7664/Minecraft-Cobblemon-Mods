package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.battles.BattleRegistry
import java.util.UUID

/** Ends an MBC-owned battle while its disconnecting player and temporary party are still resolvable. */
internal object Cobblemon173ManagedBattleTermination {
    fun endParticipatingPlayer(playerId: UUID) {
        val battleId = BattleRegistry.getBattleByParticipatingPlayerId(playerId)?.battleId ?: return
        end(battleId)
    }

    fun end(battleId: UUID) {
        val battle = BattleRegistry.getBattle(battleId) ?: return
        if (battle.ended) {
            BattleRegistry.closeBattle(battle)
            return
        }
        terminateManagedBattle(
            end = battle::end,
            remainsRegistered = { BattleRegistry.getBattle(battleId) === battle },
            forceClose = { BattleRegistry.closeBattle(battle) },
        )
    }
}

internal fun terminateManagedBattle(
    end: () -> Unit,
    remainsRegistered: () -> Boolean,
    forceClose: () -> Unit,
) {
    val failure = try {
        end()
        return
    } catch (failure: RuntimeException) {
        failure
    } catch (failure: LinkageError) {
        failure
    }

    try {
        if (remainsRegistered()) forceClose()
    } catch (cleanupFailure: RuntimeException) {
        failure.suppressDistinct(cleanupFailure)
    } catch (cleanupFailure: LinkageError) {
        failure.suppressDistinct(cleanupFailure)
    }
    throw failure
}
