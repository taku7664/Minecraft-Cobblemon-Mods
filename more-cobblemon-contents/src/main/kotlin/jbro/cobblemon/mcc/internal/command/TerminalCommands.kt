package jbro.cobblemon.mcc.internal.command

import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.api.terminal.HoloTerminals
import jbro.cobblemon.mcc.internal.hub.BattleHubNetworking
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation

/** Opens the same configured tabs as a terminal, without requiring a physical block. */
internal object TerminalCommands {
    private const val KEY = "command.${MoreCobblemonContents.MOD_ID}.terminal"
    private val terminals = linkedMapOf(
        "all" to "more_cobblemon_contents:holo_battle_terminal",
        "league" to "more_cobblemon_contents_league_challenge:league_terminal",
        "tower" to "more_cobblemon_contents_battle_tower:battle_tower_terminal",
        "factory" to "more_cobblemon_contents_battle_factory:battle_factory_terminal",
    )

    fun build(
        open: (CommandContext<CommandSourceStack>, ResourceLocation, Boolean) -> Int = ::open,
    ) = Commands.literal("terminal").also { root ->
        terminals.forEach { (name, identifier) ->
            val id = ResourceLocation.parse(identifier)
            root.then(Commands.literal(name)
                .executes { open(it, id, false) }
                .then(Commands.argument("player", EntityArgument.player())
                    .requires(BattleProgressCommands::isAdmin)
                    .executes { open(it, id, true) }))
        }
    }

    private fun open(context: CommandContext<CommandSourceStack>, id: ResourceLocation, targeted: Boolean): Int {
        val terminal = HoloTerminals.all().firstOrNull { it.id == id }
            ?: throw SimpleCommandExceptionType(Component.translatable("$KEY.unavailable", id.toString())).create()
        val player = if (targeted) EntityArgument.getPlayer(context, "player") else context.source.playerOrException
        if (!BattleHubNetworking.openTerminalCommand(player, terminal)) {
            throw SimpleCommandExceptionType(Component.translatable("$KEY.open_failed", player.name)).create()
        }
        return 1
    }
}
