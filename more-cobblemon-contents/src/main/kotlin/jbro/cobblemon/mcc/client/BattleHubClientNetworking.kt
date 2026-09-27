package jbro.cobblemon.mcc.client

import jbro.cobblemon.mcc.internal.hub.BattleHubOpenContentPayload
import jbro.cobblemon.mcc.internal.hub.BattleHubContent
import jbro.cobblemon.mcc.internal.hub.BattleHubHeaderStatePayload
import jbro.cobblemon.mcc.internal.hub.BattleHubStatePayload
import jbro.cobblemon.mcc.internal.hub.BattleHubAccessPayload
import jbro.cobblemon.mcc.api.access.ContentAccessDecision
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft

internal object BattleHubClientNetworking {
    fun register() {
        ClientPlayNetworking.registerGlobalReceiver(BattleHubAccessPayload.TYPE) { payload, context ->
            context.client().execute { MccBattleHubClientState.denied = payload.denied }
        }
        MccClientSessionReset.onReset("battle hub header") { MccBattleHubClientState.clear() }
        ClientPlayNetworking.registerGlobalReceiver(BattleHubStatePayload.TYPE) { _, context ->
            context.client().execute {
                MccContentNavigation.open(MccContentTabContract.DEFAULT_CONTENT)
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(BattleHubHeaderStatePayload.TYPE) { payload, context ->
            context.client().execute { MccBattleHubClientState.update(payload.bpBalance) }
        }
    }

    fun open(payload: BattleHubOpenContentPayload) = ClientPlayNetworking.send(payload)
}

internal object MccContentNavigation {
    fun open(content: BattleHubContent) {
        if (content == BattleHubContent.SHOP) {
            if (ShopPlayClientNetworking.canOpen()) {
                ShopPlayClientNetworking.open()
            } else {
                Minecraft.getInstance().setScreen(PvpRoomListScreen(emptyList()))
                BattleHubClientNetworking.open(BattleHubOpenContentPayload(BattleHubContent.PVP))
            }
        } else {
            BattleHubClientNetworking.open(BattleHubOpenContentPayload(content))
        }
    }
}

internal object MccBattleHubClientState {
    var denied: Map<BattleHubContent, ContentAccessDecision.Denied> = emptyMap()
    var bpBalance: Long = 0L
        private set

    fun update(value: Long) {
        bpBalance = value.coerceAtLeast(0L)
    }

    fun clear() {
        denied = emptyMap()
        bpBalance = 0L
    }
}
