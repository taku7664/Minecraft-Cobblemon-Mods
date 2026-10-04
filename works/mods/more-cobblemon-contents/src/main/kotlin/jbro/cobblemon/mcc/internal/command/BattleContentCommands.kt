package jbro.cobblemon.mcc.internal.command

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import java.util.UUID
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.application.BattleApplicationRequestContext
import jbro.cobblemon.mcc.internal.application.BattleApplicationResult
import jbro.cobblemon.mcc.internal.application.BattleEntryPoint
import jbro.cobblemon.mcc.internal.application.BattleHubView
import jbro.cobblemon.mcc.internal.application.DefaultBattleContentApplicationService
import jbro.cobblemon.mcc.internal.hub.BattleHubTabConfigFile
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

internal object BattleContentCommands {
    fun register(
        service: DefaultBattleContentApplicationService,
        openScreen: (ServerPlayer) -> Boolean = { false },
    ) {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(build(service, openScreen))
        }
    }

    fun build(
        service: DefaultBattleContentApplicationService,
        openScreen: (ServerPlayer) -> Boolean = { false },
        contributors: List<MccCommandContributor> = MccCommandContributors.all(),
    ): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("mcc")
        // Opening the hub takes the level set in hub_tabs.json. Each command under it guards its own nodes: players
        // keep their own terminal opens, `/mcc bp` and `/mcc bp history`; admin nodes need operator level.
        .requires { source -> source.hasPermission(BattleHubTabConfigFile.current.commandPermission) }
        .executes { command ->
            val result = service.open(requestContext(command.source))
            if (result is BattleApplicationResult.Success && openScreen(command.source.playerOrException)) {
                1
            } else {
                respond(command.source, result)
            }
        }
        .then(BattlePointCommands.build())
        .then(TerminalCommands.build())
        .also { root -> contributors.forEach { contributor -> root.then(contributor.build()) } }

    private fun requestContext(source: CommandSourceStack): BattleApplicationRequestContext =
        BattleApplicationRequestContext(
            requestId = UUID.randomUUID(),
            playerId = source.playerOrException.uuid,
            entryPoint = BattleEntryPoint.COMMAND,
        )

    private fun respond(source: CommandSourceStack, result: BattleApplicationResult<BattleHubView>): Int = when (result) {
        is BattleApplicationResult.Success -> {
            source.sendSuccess(
                {
                    Component.translatable(
                        "command.${MoreCobblemonContents.MOD_ID}.open",
                        result.value.contents.joinToString(", ") { it.contentId.value }.ifEmpty { "-" },
                    )
                },
                false,
            )
            1
        }

        is BattleApplicationResult.Rejected -> {
            source.sendFailure(
                Component.translatable(
                    "command.${MoreCobblemonContents.MOD_ID}.error.${result.error.name.lowercase()}",
                ),
            )
            0
        }
    }
}
