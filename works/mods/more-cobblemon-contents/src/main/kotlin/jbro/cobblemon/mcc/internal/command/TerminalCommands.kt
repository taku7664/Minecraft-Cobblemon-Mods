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

/**
 * Opens the same configured tabs as a terminal, without requiring a physical block. Operators only: players open these
 * at the plaza terminals, and an NPC opens one for a player through a dialogue command run as the server. `shop` opens
 * the `/mcc` tabs on the shop tab, for a shopkeeper NPC. A terminal whose contents are all switched off does not open.
 */
internal object TerminalCommands {
    private const val KEY = "command.${MoreCobblemonContents.MOD_ID}.terminal"
    private const val PWT_TERMINAL = "more_cobblemon_contents_pwt:pwt_terminal"

    /** Terminals a command opens on their own content instead of the dashboard, for an NPC that hosts that content. */
    private val homes = mapOf(PWT_TERMINAL to "more_cobblemon_contents:pwt")
    private val terminals = linkedMapOf(
        "all" to "more_cobblemon_contents:holo_battle_terminal",
        "league" to "more_cobblemon_contents_league_challenge:league_terminal",
        "tower" to "more_cobblemon_contents_battle_tower:battle_tower_terminal",
        "factory" to "more_cobblemon_contents_battle_factory:battle_factory_terminal",
        "pwt" to PWT_TERMINAL,
    )

    fun build(
        open: (CommandContext<CommandSourceStack>, ResourceLocation, Boolean) -> Int = ::open,
        openShop: (CommandContext<CommandSourceStack>, Boolean) -> Int = ::openShop,
    ) = Commands.literal("terminal").requires(BattleProgressCommands::isAdmin).also { root ->
        terminals.forEach { (name, identifier) ->
            val id = ResourceLocation.parse(identifier)
            root.then(Commands.literal(name)
                .executes { open(it, id, false) }
                .then(Commands.argument("player", EntityArgument.player())
                    .executes { open(it, id, true) }))
        }
        root.then(Commands.literal("shop")
            .executes { openShop(it, false) }
            .then(Commands.argument("player", EntityArgument.player())
                .executes { openShop(it, true) }))
    }

    private fun openShop(context: CommandContext<CommandSourceStack>, targeted: Boolean): Int {
        val player = if (targeted) EntityArgument.getPlayer(context, "player") else context.source.playerOrException
        if (!BattleHubNetworking.openShopCommand(player)) {
            throw SimpleCommandExceptionType(Component.translatable("$KEY.open_failed", player.name)).create()
        }
        return 1
    }

    private fun open(context: CommandContext<CommandSourceStack>, id: ResourceLocation, targeted: Boolean): Int {
        val terminal = HoloTerminals.all().firstOrNull { it.id == id }
            ?: throw SimpleCommandExceptionType(Component.translatable("$KEY.unavailable", id.toString())).create()
        val player = if (targeted) EntityArgument.getPlayer(context, "player") else context.source.playerOrException
        // An NPC runs this as the server, so the player hears why the content is closed, not only the command source.
        BattleHubNetworking.pausedTerminal(player, terminal)?.let { message ->
            if (context.source.player != player) player.sendSystemMessage(message)
            throw SimpleCommandExceptionType(message).create()
        }
        val opened = homes[id.toString()]?.let { BattleHubNetworking.openTerminalCommand(player, terminal, it) }
            ?: BattleHubNetworking.openTerminalCommand(player, terminal)
        if (!opened) {
            throw SimpleCommandExceptionType(Component.translatable("$KEY.open_failed", player.name)).create()
        }
        return 1
    }
}
