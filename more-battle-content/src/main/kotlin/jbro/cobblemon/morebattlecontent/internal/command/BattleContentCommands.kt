package jbro.cobblemon.morebattlecontent.internal.command

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import java.util.UUID
import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.application.BattleApplicationRequestContext
import jbro.cobblemon.morebattlecontent.internal.application.BattleApplicationResult
import jbro.cobblemon.morebattlecontent.internal.application.BattleEntryPoint
import jbro.cobblemon.morebattlecontent.internal.application.BattleHubView
import jbro.cobblemon.morebattlecontent.internal.application.DefaultBattleContentApplicationService
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
    ): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("mbc")
        .executes { command ->
            val result = service.open(requestContext(command.source))
            if (result is BattleApplicationResult.Success && openScreen(command.source.playerOrException)) {
                1
            } else {
                respond(command.source, result)
            }
        }
        .then(BattlePointCommands.build())

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
                        "command.${MoreBattleContent.MOD_ID}.open",
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
                    "command.${MoreBattleContent.MOD_ID}.error.${result.error.name.lowercase()}",
                ),
            )
            0
        }
    }
}
