package jbro.cobblemon.mcc.league.client

import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.mcc.client.hub.MccDashboardPresentation
import jbro.cobblemon.mcc.client.hub.MccHubHeaderBadge
import jbro.cobblemon.mcc.client.hub.MccHubHeaderBadges
import jbro.cobblemon.mcc.client.hub.MccHubTab
import jbro.cobblemon.mcc.client.hub.MccHubTabKind
import jbro.cobblemon.mcc.client.hub.MccHubTabs
import jbro.cobblemon.mcc.league.DevelopmentEnvironmentGate
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.network.chat.Component
import jbro.cobblemon.uikit.client.CobblemonUiRenderContent

object MoreCobblemonContentsLeagueChallengeClient : ClientModInitializer {
    override fun onInitializeClient() {
        LeagueClientSession.register()
        LeagueHomeController.register()
        // League gates Tower and Factory behind the champion title, so it leads the content tabs.
        MccHubTabs.register(
            MccHubTab(
                ManagedBattleContentIds.LEAGUE_CHALLENGE,
                Component.translatable(MccDashboardPresentation.contentNameKey(ManagedBattleContentIds.LEAGUE_CHALLENGE)),
                order = 90,
                kind = MccHubTabKind.Embedded(::LeagueHubTab),
                icon = MccHubTabs.itemIcon("cobblemon:poke_ball"),
            ),
        )
        MccHubHeaderBadges.register(order = 90) {
            LeagueClientSession.rank?.let { rank ->
                MccHubHeaderBadge(CobblemonUiRenderContent.Item(rankStack(rank)), rankName(rank),
                    Component.translatable("screen.more_cobblemon_contents_league_challenge.live.header_rank", rankName(rank)))
            }
        }
        if (!DevelopmentEnvironmentGate.shouldRegister(FabricLoader.getInstance().isDevelopmentEnvironment)) {
            return
        }

        LeagueLiveCaptureHarness.installFromEnvironment()
    }
}
