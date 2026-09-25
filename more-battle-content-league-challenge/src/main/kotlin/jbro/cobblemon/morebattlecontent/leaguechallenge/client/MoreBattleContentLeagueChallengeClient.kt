package jbro.cobblemon.morebattlecontent.leaguechallenge.client

import jbro.cobblemon.morebattlecontent.leaguechallenge.DevelopmentEnvironmentGate
import jbro.cobblemon.morebattlecontent.leaguechallenge.ui.LeagueHomeFixtureCatalog
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.network.chat.Component

object MoreBattleContentLeagueChallengeClient : ClientModInitializer {
    override fun onInitializeClient() {
        if (!DevelopmentEnvironmentGate.shouldRegister(FabricLoader.getInstance().isDevelopmentEnvironment)) {
            return
        }

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            val command = literal("mbc-league-ui").executes { context ->
                openFixture(context.source, "badges_3")
            }
            LeagueHomeFixtureCatalog.all.forEach { fixture ->
                command.then(literal(fixture.id).executes { context ->
                    openFixture(context.source, fixture.id)
                })
            }
            dispatcher.register(command)
        }
    }

    private fun openFixture(source: FabricClientCommandSource, fixtureId: String): Int {
        source.client.setScreen(
            LeagueChallengeDevelopmentScreen(LeagueHomeFixtureCatalog.require(fixtureId))
        )
        source.sendFeedback(
            Component.translatable(
                "command.cobblemon_more_battle_content_league_challenge.dev.opened",
                fixtureId
            )
        )
        return 1
    }
}
