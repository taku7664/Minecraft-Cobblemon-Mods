package jbro.cobblemon.policy.client

import jbro.cobblemon.mcc.client.hub.MccHubScreen
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader

/**
 * Opens the MCC hub on a chosen tab. `/mcc` always opens it on its first tab, so the wanted tab is remembered and
 * selected, as if its rail button was pressed, once the hub shows. Loaded only with More Cobblemon Contents.
 */
object MccHubTabRequest {
    private const val WAIT_TICKS = 100
    private var tab: String? = null
    private var ticksLeft = 0

    fun register() {
        if (!FabricLoader.getInstance().isModLoaded("more_cobblemon_contents")) return
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            val wanted = tab ?: return@register
            val hub = client.screen as? MccHubScreen
            if (hub != null) {
                if (hub.selectedTabId != wanted) hub.selectTab(wanted)
                tab = null
            } else if (--ticksLeft <= 0) tab = null
        }
    }

    fun request(wanted: String) {
        tab = wanted
        ticksLeft = WAIT_TICKS
    }
}
