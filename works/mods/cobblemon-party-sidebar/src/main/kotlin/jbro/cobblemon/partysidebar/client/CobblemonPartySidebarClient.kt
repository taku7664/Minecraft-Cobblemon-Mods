package jbro.cobblemon.partysidebar.client

import net.fabricmc.api.ClientModInitializer

/** The sidebar itself is drawn and driven from the inventory screen; see [PartySidebar]. */
object CobblemonPartySidebarClient : ClientModInitializer {
    override fun onInitializeClient() {
        SidebarConfig.load()
    }
}
