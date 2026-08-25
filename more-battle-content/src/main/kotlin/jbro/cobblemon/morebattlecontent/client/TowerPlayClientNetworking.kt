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
                } else {
                    current.towerPlayScreen()?.applyAccepted(payload.requestId, payload.state)
                }
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(TowerPlayRejectedPayload.TYPE) { payload, context ->
            context.client().execute {
                context.client().screen.towerPlayScreen()?.applyRejected(payload.result)
            }
        }
    }

    fun send(payload: TowerPlayIntentPayload) {
        ClientPlayNetworking.send(payload)
    }
}

private fun net.minecraft.client.gui.screens.Screen?.towerPlayScreen(): TowerPlayScreen? = when (this) {
    is TowerPlayScreen -> this
    is TowerGuideScreen -> towerPlayScreen
    else -> null
}
