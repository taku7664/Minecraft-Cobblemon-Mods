package jbro.cobblemon.mcc.client

import jbro.cobblemon.mcc.internal.hub.BattleHubOpenContentPayload
import jbro.cobblemon.mcc.internal.hub.BattleHubContent
import jbro.cobblemon.mcc.internal.hub.BattleHubHeaderStatePayload
import jbro.cobblemon.mcc.internal.hub.BattleHubStatePayload
import jbro.cobblemon.mcc.internal.hub.BattleHubAccessPayload
import jbro.cobblemon.mcc.internal.hub.BattleHubDashboardPayload
import jbro.cobblemon.mcc.internal.hub.BattleHubRecordView
import jbro.cobblemon.mcc.client.hub.MccHubScreen
import jbro.cobblemon.mcc.api.access.ContentAccessDecision
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

internal object BattleHubClientNetworking {
    fun register() {
        ClientPlayNetworking.registerGlobalReceiver(BattleHubAccessPayload.TYPE) { payload, context ->
            context.client().execute {
                MccBattleHubClientState.deniedById = payload.denied
                MccBattleHubClientState.denied = payload.denied.mapNotNull { (contentId, denial) ->
                    BattleHubContent.fromId(contentId)?.let { content -> content to denial }
                }.toMap()
            }
        }
        MccClientSessionReset.onReset("battle hub header") { MccBattleHubClientState.clear() }
        ClientPlayNetworking.registerGlobalReceiver(BattleHubDashboardPayload.TYPE) { payload, context ->
            context.client().execute {
                MccBattleHubClientState.dashboard = payload.records
                MccHubScreen.current?.rebuild()
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(BattleHubStatePayload.TYPE) { _, context ->
            context.client().execute {
                val open = MccHubScreen.current
                if (open != null && context.client().screen === open) open.rebuild() else context.client().setScreen(MccHubScreen())
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
                BattleHubClientNetworking.open(BattleHubOpenContentPayload(content.id))
            }
        } else {
            BattleHubClientNetworking.open(BattleHubOpenContentPayload(content.id))
        }
    }
}

object MccBattleHubClientState {
    var denied: Map<BattleHubContent, ContentAccessDecision.Denied> = emptyMap()
    var deniedById: Map<String, ContentAccessDecision.Denied> = emptyMap()

    /** The viewer's own records from the last hub open; null until the server sent them. */
    var dashboard: List<BattleHubRecordView>? = null
    var bpBalance: Long = 0L
        private set

    fun update(value: Long) {
        bpBalance = value.coerceAtLeast(0L)
    }

    fun clear() {
        denied = emptyMap()
        deniedById = emptyMap()
        dashboard = null
        bpBalance = 0L
    }
}
