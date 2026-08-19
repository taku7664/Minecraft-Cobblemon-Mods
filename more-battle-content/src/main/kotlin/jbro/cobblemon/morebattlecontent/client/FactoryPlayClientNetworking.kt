package jbro.cobblemon.morebattlecontent.client

import jbro.cobblemon.morebattlecontent.internal.factory.FactoryPlayPhase
import jbro.cobblemon.morebattlecontent.internal.factory.network.FactoryPlayIntentPayload
import jbro.cobblemon.morebattlecontent.internal.factory.network.FactoryPlayRejectedPayload
import jbro.cobblemon.morebattlecontent.internal.factory.network.FactoryPlayStatePayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

internal object FactoryPlayClientNetworking {
    fun register() {
        ClientPlayNetworking.registerGlobalReceiver(FactoryPlayStatePayload.TYPE) { payload, context ->
            context.client().execute {
                val current = context.client().screen as? FactoryPlayScreen
                if (payload.state.phase == FactoryPlayPhase.IN_BATTLE) {
                    if (current != null) context.client().setScreen(null)
                } else if (payload.requestId != null && current != null) {
                    current.applyAccepted(payload.requestId, payload.state)
                } else {
                    context.client().setScreen(FactoryPlayScreen(payload.state))
                }
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(FactoryPlayRejectedPayload.TYPE) { payload, context ->
            context.client().execute {
                (context.client().screen as? FactoryPlayScreen)?.applyRejected(payload.requestId, payload.error)
            }
        }
    }

    fun send(payload: FactoryPlayIntentPayload) = ClientPlayNetworking.send(payload)
}
