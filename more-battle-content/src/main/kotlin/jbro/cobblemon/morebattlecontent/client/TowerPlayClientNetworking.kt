package jbro.cobblemon.morebattlecontent.client

import jbro.cobblemon.morebattlecontent.internal.tower.network.TowerPlayIntentPayload
import jbro.cobblemon.morebattlecontent.internal.tower.network.TowerPlayRejectedPayload
import jbro.cobblemon.morebattlecontent.internal.tower.network.TowerPlayStatePayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

internal object TowerPlayClientNetworking {
    fun register() {
        ClientPlayNetworking.registerGlobalReceiver(TowerPlayStatePayload.TYPE) { payload, context ->
            context.client().execute {
                val current = context.client().screen
                if (payload.requestId == null) {
                    context.client().setScreen(TowerPlayScreen(payload.state))
                } else if (current is TowerPlayScreen) {
                    current.applyAccepted(payload.requestId, payload.state)
                }
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(TowerPlayRejectedPayload.TYPE) { payload, context ->
            context.client().execute {
                (context.client().screen as? TowerPlayScreen)?.applyRejected(payload.result)
            }
        }
    }

    fun send(payload: TowerPlayIntentPayload) {
        ClientPlayNetworking.send(payload)
    }
}
