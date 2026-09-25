package jbro.cobblemon.morebattlecontent.leaguechallenge.client

import jbro.cobblemon.morebattlecontent.leaguechallenge.DevelopmentEnvironmentGate
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
            dispatcher.register(
                literal("mbc-league-ui").executes { context ->
                    context.source.client.setScreen(LeagueChallengeDevelopmentScreen())
                    context.source.sendFeedback(
                        Component.translatable(
                            "command.cobblemon_more_battle_content_league_challenge.dev.opened"
                        )
                    )
                    1
                }
            )
        }
    }
}
