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
        net.fabricmc.fabric.api.resource.ResourceManagerHelper.get(net.minecraft.server.packs.PackType.CLIENT_RESOURCES)
            .registerReloadListener(WildTrainerTextures)
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
        // `hook league <catalog id|primary> <fresh|gyms <n>|champion>` rewrites this player's progress in the dev world.
        jbro.cobblemon.mcc.client.hub.MccHubDriveHarness.registerHook("league") { client, args ->
            val server = checkNotNull(client.singleplayerServer)
            val id = checkNotNull(client.player).uuid
            val catalog = checkNotNull(if (args[0] == "primary") jbro.cobblemon.mcc.league.server.LeagueCatalogResources.current
                else jbro.cobblemon.mcc.league.server.LeagueCatalogResources.find(args[0])) { "unknown league ${args[0]}" }
            val progress = when (args[1]) {
                "fresh" -> jbro.cobblemon.mcc.league.system.LeagueProgress()
                "gyms" -> jbro.cobblemon.mcc.league.system.LeagueProgress(cleared = catalog.gyms.take(args[2].toInt()).toSet())
                "champion" -> jbro.cobblemon.mcc.league.system.LeagueProgress(cleared = (catalog.gyms + catalog.finals).toSet(),
                    champion = true, championAt = System.currentTimeMillis())
                else -> error("unknown progress ${args[1]}")
            }
            server.execute { jbro.cobblemon.mcc.league.server.LeagueSavedData.get(server).write(catalog.id, id, progress) }
            "${catalog.id} gyms=${catalog.gyms.size}"
        }
        TrainerSceneCaptureHarness.installFromEnvironment()
        WildNpcRoleCheckHarness.installFromEnvironment()
    }
}
