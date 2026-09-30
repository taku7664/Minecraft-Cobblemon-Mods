package jbro.cobblemon.mcc.client

import jbro.cobblemon.mcc.internal.hub.BattleHubOpenContentPayload
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
            }
        }
        MccClientSessionReset.onReset("battle hub header") { MccBattleHubClientState.clear() }
        ClientPlayNetworking.registerGlobalReceiver(BattleHubDashboardPayload.TYPE) { payload, context ->
            context.client().execute {
                MccBattleHubClientState.dashboard = payload.records
                MccHubScreen.current?.rebuild()
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(BattleHubStatePayload.TYPE) { payload, context ->
            context.client().execute {
                MccBattleHubClientState.visibleTabs = payload.tabs.toSet()
                val open = MccHubScreen.current
                if (open != null && context.client().screen === open) {
                    open.rebuild()
                    open.selectTab(payload.initialTab)
                } else {
                    context.client().setScreen(MccHubScreen(payload.initialTab))
                }
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(BattleHubHeaderStatePayload.TYPE) { payload, context ->
            context.client().execute { MccBattleHubClientState.update(payload.bpBalance) }
        }
    }

    fun open(payload: BattleHubOpenContentPayload) = ClientPlayNetworking.send(payload)
}

object MccBattleHubClientState {
    var deniedById: Map<String, ContentAccessDecision.Denied> = emptyMap()

    /** The tabs the server opened the hub with; null shows every tab, as before the server said. */
    var visibleTabs: Set<String>? = null

    /** Adds [tabId] to the shown tabs, for a content the server opened on its own. */
    fun reveal(tabId: String) {
        visibleTabs = visibleTabs?.plus(tabId)
    }

    /** The viewer's own records from the last hub open; null until the server sent them. */
    var dashboard: List<BattleHubRecordView>? = null
    var bpBalance: Long = 0L
        private set

    fun update(value: Long) {
        bpBalance = value.coerceAtLeast(0L)
    }

    fun clear() {
        deniedById = emptyMap()
        visibleTabs = null
        dashboard = null
        bpBalance = 0L
    }
}
