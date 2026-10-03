package jbro.cobblemon.mcc.client

import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.client.hub.MccDashboardTab
import jbro.cobblemon.mcc.client.hub.MccHubCaptureHarness
import jbro.cobblemon.mcc.client.hub.MccHubTab
import jbro.cobblemon.mcc.client.hub.MccHubTabKind
import jbro.cobblemon.mcc.client.hub.MccHubTabs
import jbro.cobblemon.mcc.internal.hub.BattleHubIds
import net.fabricmc.api.ClientModInitializer
import net.minecraft.network.chat.Component

object MoreCobblemonContentsClient : ClientModInitializer {
    override fun onInitializeClient() {
        MccClientSessionReset.registerEvents()
        ShadowHologramShader.register()
        ShadowTerrainHologramShader.register()
        ShadowTerrainHologramRenderer.register()
        HoloTerminalClientContent.register()
        BattleHubClientNetworking.register()
        MccHubTabs.register(MccHubTab(MccHubTabs.DASHBOARD, Component.translatable("screen.${MoreCobblemonContents.MOD_ID}.hub.tab.dashboard"),
            order = 0, kind = MccHubTabKind.Embedded(::MccDashboardTab), accessContentId = null,
            icon = MccHubTabs.itemIcon("cobblemon:pokedex_red")))
        MccHubTabs.register(MccHubTab(BattleHubIds.SHOP, Component.translatable("screen.${MoreCobblemonContents.MOD_ID}.hub.tab.shop"),
            order = 10, kind = MccHubTabKind.Embedded(::MccShopTab), accessContentId = null,
            icon = MccHubTabs.itemIcon("cobblemon:relic_coin")))
        MccHubCaptureHarness.installFromEnvironment()
        ShopPlayClientNetworking.register()
        ShadowTrainerProjectionRenderer.register()
        ManagedBattleMechanicVisibilityClient.register()
        ManagedBattleContentClientNetworking.register()
        BattleEntryClientNetworking.register()
        MccClientContextTracker.register()
        jbro.cobblemon.mcc.client.wiki.LocalWikiServer.register()
    }
}
