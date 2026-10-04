package jbro.cobblemon.mcc.client

import jbro.cobblemon.mcc.internal.factory.network.FactoryPlayIntentPayload
import jbro.cobblemon.mcc.internal.factory.network.FactoryPlayRejectedPayload
import jbro.cobblemon.mcc.internal.factory.network.FactoryPlayStatePayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

internal object FactoryPlayClientNetworking {
    fun register() {
        ClientPlayNetworking.registerGlobalReceiver(FactoryPlayStatePayload.TYPE) { payload, context ->
            context.client().execute { FactoryHubClient.accept(payload.requestId, payload.state) }
        }
        ClientPlayNetworking.registerGlobalReceiver(FactoryPlayRejectedPayload.TYPE) { payload, context ->
            context.client().execute { FactoryHubClient.reject(payload.requestId, payload.error) }
        }
    }

    fun send(payload: FactoryPlayIntentPayload) = ClientPlayNetworking.send(payload)
}
