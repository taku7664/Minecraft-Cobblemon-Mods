package jbro.cobblemon.mcc.client

import jbro.cobblemon.mcc.internal.tower.network.TowerPlayIntentPayload
import jbro.cobblemon.mcc.internal.tower.network.TowerPlayRejectedPayload
import jbro.cobblemon.mcc.internal.tower.network.TowerPlayStatePayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

internal object TowerPlayClientNetworking {
    fun register() {
        ClientPlayNetworking.registerGlobalReceiver(TowerPlayStatePayload.TYPE) { payload, context ->
            context.client().execute {
                val requestId = payload.requestId
                if (requestId == null) {
                    TowerHubClient.acceptOpened(payload.state)
                } else {
                    TowerHubClient.acceptResult(jbro.cobblemon.mcc.internal.tower.ui.TowerPlayMutationResult.Accepted(requestId, payload.state))
                }
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(TowerPlayRejectedPayload.TYPE) { payload, context ->
            context.client().execute { TowerHubClient.acceptResult(payload.result) }
        }
    }

    fun send(payload: TowerPlayIntentPayload) {
        ClientPlayNetworking.send(payload)
    }
}
