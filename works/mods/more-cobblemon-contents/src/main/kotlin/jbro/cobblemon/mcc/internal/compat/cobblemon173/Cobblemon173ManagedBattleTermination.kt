package jbro.cobblemon.mcc.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.net.messages.client.battle.BattleEndPacket
import java.util.UUID
import jbro.cobblemon.mcc.MoreCobblemonContents

/** Ends an MCC-owned battle while its disconnecting player and temporary party are still resolvable. */
object Cobblemon173ManagedBattleTermination {
    fun endParticipatingPlayer(playerId: UUID) {
        val battleId = BattleRegistry.getBattleByParticipatingPlayerId(playerId)?.battleId ?: return
        end(battleId)
    }

    fun end(battleId: UUID) {
        val battle = BattleRegistry.getBattle(battleId) ?: return
        if (battle.ended) {
            closeWithClientEnd(battle)
            return
        }
        terminateManagedBattle(
            end = battle::end,
            remainsRegistered = { BattleRegistry.getBattle(battleId) === battle },
            forceClose = { closeWithClientEnd(battle) },
        )
    }

    /**
     * Closes [battle] without Cobblemon's end, which sends its clients the end packet only as its last step: a battle
     * whose end failed before that would stay open, with its music, on every player's and spectator's screen.
     */
    fun closeWithClientEnd(battle: PokemonBattle) {
        try {
            battle.sendUpdate(BattleEndPacket())
        } catch (failure: RuntimeException) {
            MoreCobblemonContents.LOGGER.warn("Battle {} could not tell its clients it ended", battle.battleId, failure)
        }
        BattleRegistry.closeBattle(battle)
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
