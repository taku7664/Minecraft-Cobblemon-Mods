package jbro.cobblemon.mcc.league.client

import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.mcc.client.hub.MccDashboardPresentation
import jbro.cobblemon.mcc.client.hub.MccHubHeaderBadge
import jbro.cobblemon.mcc.client.hub.MccHubHeaderBadges
import jbro.cobblemon.mcc.client.hub.MccHubTab
import jbro.cobblemon.mcc.client.hub.MccHubTabKind
import jbro.cobblemon.mcc.client.hub.MccHubTabs
import jbro.cobblemon.mcc.league.DevelopmentEnvironmentGate
import jbro.cobblemon.mcc.league.ui.LeagueHomeFixtureCatalog
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
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

        LeagueUiCaptureHarness.installFromEnvironment()
        LeagueLiveCaptureHarness.installFromEnvironment()

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            val command = literal("mcc-league-ui").executes { context ->
                openFixture(context.source, "badges_3")
            }
            LeagueHomeFixtureCatalog.all.forEach { fixture ->
                command.then(literal(fixture.id).executes { context ->
                    openFixture(context.source, fixture.id)
                })
            }
            dispatcher.register(command)

            val owoCommand = literal("mcc-league-ui-owo").executes { context ->
                openOwoFixture(context.source, "badges_3")
            }
            LeagueHomeFixtureCatalog.all.forEach { fixture ->
                owoCommand.then(literal(fixture.id).executes { context ->
                    openOwoFixture(context.source, fixture.id)
                })
            }
            dispatcher.register(owoCommand)
        }
    }

    private fun openFixture(source: FabricClientCommandSource, fixtureId: String): Int {
        source.client.setScreen(
            LeagueChallengeDevelopmentScreen(LeagueHomeFixtureCatalog.require(fixtureId))
        )
        source.sendFeedback(
            Component.translatable(
                "command.more_cobblemon_contents_league_challenge.dev.opened",
                fixtureId
            )
        )
        return 1
    }

    private fun openOwoFixture(source: FabricClientCommandSource, fixtureId: String): Int {
        source.client.setScreen(
            LeagueChallengeOwoSpikeScreen(LeagueHomeFixtureCatalog.require(fixtureId))
        )
        source.sendFeedback(
            Component.translatable(
                "command.more_cobblemon_contents_league_challenge.dev.opened",
                "owo:$fixtureId"
            )
        )
        return 1
    }
}
