package jbro.cobblemon.mcc.internal.tower

import com.mojang.brigadier.context.CommandContext
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.command.BattleProgressCommands
import jbro.cobblemon.mcc.internal.command.MccAdminArguments
import jbro.cobblemon.mcc.internal.tower.network.TowerPlayNetworking
import jbro.cobblemon.mcc.internal.tower.ui.TowerSessionAbandonResult
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.GameProfileArgument
import net.minecraft.network.chat.Component

/** `/mcc tower session <player>` and `/mcc tower abandon <player> [force]` for operators. */
internal object TowerAdminCommands {
    private const val KEY = "command.${MoreCobblemonContents.MOD_ID}.tower.admin"

    fun session() = Commands.literal("session")
        .requires(BattleProgressCommands::isAdmin)
        .then(Commands.argument("player", GameProfileArgument.gameProfile()).executes { command ->
            val player = MccAdminArguments.profile(command) ?: return@executes 0
            val lines = TowerPlayNetworking.adminDescribe(player.id)
            if (lines == null) {
                command.source.sendFailure(Component.translatable("$KEY.no_session", player.name))
                return@executes 0
            }
            command.source.sendSuccess({ Component.translatable("$KEY.header", player.name) }, false)
            lines.forEach { line -> command.source.sendSuccess({ line }, false) }
            1
        })

    fun abandon() = Commands.literal("abandon")
        .requires(BattleProgressCommands::isAdmin)
        .then(Commands.argument("player", GameProfileArgument.gameProfile())
            .executes { abandon(it, force = false) }
            .then(Commands.literal("force").executes { abandon(it, force = true) }))

    private fun abandon(command: CommandContext<CommandSourceStack>, force: Boolean): Int {
        val source = command.source
        val player = MccAdminArguments.profile(command) ?: return 0
        val result = TowerPlayNetworking.adminAbandon(player.id, force)
        val outcome = when (result) {
            TowerSessionAbandonResult.NoSession -> {
                source.sendFailure(Component.translatable("$KEY.no_session", player.name))
                return 0
            }
            is TowerSessionAbandonResult.ForfeitUnavailable -> {
                source.sendFailure(Component.translatable("$KEY.forfeit_unavailable", player.name))
                return 0
            }
            TowerSessionAbandonResult.SessionClosed -> "closed"
            is TowerSessionAbandonResult.ForfeitRequested -> "forfeited"
        }
        source.sendSuccess({ Component.translatable("$KEY.abandon.$outcome", player.name) }, true)
        source.server.playerList.getPlayer(player.id)?.sendSystemMessage(Component.translatable("$KEY.abandon.notice"))
        MoreCobblemonContents.LOGGER.info("MCC admin tower abandon actor={} target={} force={} result={}", source.textName, player.id, force, outcome)
        return 1
    }
}
