package jbro.cobblemon.mcc.client

import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.mcc.client.hub.MccDashboardPresentation
import jbro.cobblemon.mcc.client.hub.MccHubTab
import jbro.cobblemon.mcc.client.hub.MccHubTabKind
import jbro.cobblemon.mcc.client.hub.MccHubTabs
import net.minecraft.network.chat.Component
import net.fabricmc.api.ClientModInitializer

object BattleFactoryContentClient : ClientModInitializer {
    override fun onInitializeClient() {
        FactoryPlayClientNetworking.register()
        MccHubTabs.register(
            MccHubTab(
                ManagedBattleContentIds.BATTLE_FACTORY,
                Component.translatable(MccDashboardPresentation.contentNameKey(ManagedBattleContentIds.BATTLE_FACTORY)),
                order = 110,
                kind = MccHubTabKind.Embedded(::FactoryHubTab),
            ),
        )
    }
}
