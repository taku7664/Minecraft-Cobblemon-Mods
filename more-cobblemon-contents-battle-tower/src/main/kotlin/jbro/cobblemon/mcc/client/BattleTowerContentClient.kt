package jbro.cobblemon.mcc.client

import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.mcc.client.hub.MccDashboardPresentation
import jbro.cobblemon.mcc.client.hub.MccHubTab
import jbro.cobblemon.mcc.client.hub.MccHubTabKind
import jbro.cobblemon.mcc.client.hub.MccHubTabs
import net.minecraft.network.chat.Component
import net.fabricmc.api.ClientModInitializer

object BattleTowerContentClient : ClientModInitializer {
    override fun onInitializeClient() {
        TowerPlayClientNetworking.register()
        MccHubTabs.register(
            MccHubTab(
                ManagedBattleContentIds.BATTLE_TOWER,
                Component.translatable(MccDashboardPresentation.contentNameKey(ManagedBattleContentIds.BATTLE_TOWER)),
                order = 100,
                kind = MccHubTabKind.Embedded(::TowerHubTab),
            ),
        )
    }
}
