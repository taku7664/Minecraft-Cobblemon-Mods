package jbro.cobblemon.mcc.pwt.client

import jbro.cobblemon.mcc.client.hub.MccDashboardPresentation
import jbro.cobblemon.mcc.client.hub.MccHubTab
import jbro.cobblemon.mcc.client.hub.MccHubTabKind
import jbro.cobblemon.mcc.client.hub.MccHubTabs
import jbro.cobblemon.mcc.pwt.MoreCobblemonContentsPwt as Mod
import net.fabricmc.api.ClientModInitializer
import net.minecraft.network.chat.Component

object MoreCobblemonContentsPwtClient : ClientModInitializer {
    override fun onInitializeClient() {
        PwtClientState.register()
        // After League, Tower and Factory: PWT opens to Champions.
        MccHubTabs.register(
            MccHubTab(
                Mod.CONTENT,
                Component.translatable(MccDashboardPresentation.contentNameKey(Mod.CONTENT)),
                order = 130,
                kind = MccHubTabKind.Embedded(::PwtHubTab),
                icon = MccHubTabs.itemIcon("minecraft:nether_star"),
            ),
        )
    }
}
