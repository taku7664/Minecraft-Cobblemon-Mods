package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import com.cobblemon.mod.common.api.scheduling.afterOnServer
import jbro.cobblemon.morebattlecontent.MoreBattleContent

/** Emits one bounded diagnostic only when an MBC-managed battle never reaches its first turn. */
internal object Cobblemon173InitialTurnDiagnostics {
    fun watch(content: String, battle: PokemonBattle) {
        afterOnServer(DIAGNOSTIC_DELAY_SECONDS) {
            if (!battle.ended && battle.turn == 0) {
                val actors = battle.actors.joinToString(separator = ",") { actor ->
                    "${actor.uuid}[sending=${actor.stillSendingOutCount},request=${actor.request != null}]"
                }
                MoreBattleContent.LOGGER.warn(
                    "{} battle {} is still before turn 1: started={}, actors={}",
                    content,
                    battle.battleId,
                    battle.started,
                    actors,
                )
            }
        }
    }

    private const val DIAGNOSTIC_DELAY_SECONDS = 5.0F
}
