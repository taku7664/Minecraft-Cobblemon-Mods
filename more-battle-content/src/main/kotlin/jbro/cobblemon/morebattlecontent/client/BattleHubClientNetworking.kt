package jbro.cobblemon.morebattlecontent.client

import jbro.cobblemon.morebattlecontent.internal.hub.BattleHubOpenContentPayload
import jbro.cobblemon.morebattlecontent.internal.hub.BattleHubContent
import jbro.cobblemon.morebattlecontent.internal.hub.BattleHubHeaderStatePayload
import jbro.cobblemon.morebattlecontent.internal.hub.BattleHubStatePayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft

internal object BattleHubClientNetworking {
    fun register() {
        MbcClientSessionReset.onReset("battle hub header") { MbcBattleHubClientState.clear() }
        ClientPlayNetworking.registerGlobalReceiver(BattleHubStatePayload.TYPE) { _, context ->
            context.client().execute {
                MbcContentNavigation.open(MbcContentTabContract.DEFAULT_CONTENT)
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(BattleHubHeaderStatePayload.TYPE) { payload, context ->
            context.client().execute { MbcBattleHubClientState.update(payload.bpBalance) }
        }
    }

    fun open(payload: BattleHubOpenContentPayload) = ClientPlayNetworking.send(payload)
}

internal object MbcContentNavigation {
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

internal object MbcBattleHubClientState {
    var bpBalance: Long = 0L
        private set

    fun update(value: Long) {
        bpBalance = value.coerceAtLeast(0L)
    }

    fun clear() {
        bpBalance = 0L
    }
}
