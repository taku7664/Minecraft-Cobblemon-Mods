package jbro.cobblemon.mcc.client

import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.mcc.client.hub.MccDashboardPresentation
import jbro.cobblemon.mcc.client.hub.MccHubTab
import jbro.cobblemon.mcc.client.hub.MccHubTabKind
import jbro.cobblemon.mcc.client.hub.MccHubTabs
import net.minecraft.network.chat.Component
import net.fabricmc.api.ClientModInitializer

object PvpContentClient : ClientModInitializer {
    override fun onInitializeClient() {
        PvpLoungeSpectatorControls.register()
        PvpRoomHudOverlay.register()
        PvpPlayClientNetworking.register()
        MccHubTabs.register(
            MccHubTab(
                ManagedBattleContentIds.PVP,
                Component.translatable(MccDashboardPresentation.contentNameKey(ManagedBattleContentIds.PVP)),
                order = 120,
                kind = MccHubTabKind.Embedded(::PvpHubTab),
                icon = MccHubTabs.itemIcon("cobblemon:link_cable"),
            ),
        )
    }
}
