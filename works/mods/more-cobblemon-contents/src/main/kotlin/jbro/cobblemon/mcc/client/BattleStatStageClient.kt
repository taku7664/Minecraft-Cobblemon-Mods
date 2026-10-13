package jbro.cobblemon.mcc.client

import com.cobblemon.mod.common.client.CobblemonClient
import jbro.cobblemon.mcc.internal.battle.BattleStatStagesPayload
import jbro.cobblemon.ui.extended.BattleStateTracker
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

/** Hands the server's Showdown stat stages to Cobblemon UI, which shows them in place of its own count. */
internal object BattleStatStageClient {
    fun register() {
        ClientPlayNetworking.registerGlobalReceiver(BattleStatStagesPayload.TYPE) { payload, context ->
            context.client().execute {
                if (CobblemonClient.battle?.battleId == payload.battleId) BattleStateTracker.applyServerStatStages(payload.stages)
            }
        }
    }
}
