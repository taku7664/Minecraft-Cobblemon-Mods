package jbro.cobblemon.npc.server

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import jbro.cobblemon.npc.CobblemonNpc
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

/**
 * `/npc wand`, `/npc reload`, `/npc talk <players> <dialogue> [node]` and `/npc end <players>`. `talk` is how a
 * command block, another mod's script or a dialogue's own command opens a talk without an NPC.
 */
object NpcCommands {
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(Commands.literal("npc")
            .requires { it.hasPermission(CobblemonNpc.EDIT_PERMISSION) }
            .then(Commands.literal("wand").executes { context ->
                val player = context.source.playerOrException
                if (!player.inventory.add(ItemStack(CobblemonNpc.WAND))) player.drop(ItemStack(CobblemonNpc.WAND), false)
                1
            })
            .then(Commands.literal("reload").executes { context ->
                val failed = DialogueStore.load()
                context.source.sendSuccess({ Component.translatable("command.cobblemon_npc.reloaded", DialogueStore.ids().size) }, true)
                failed.forEach { (id, problems) ->
                    context.source.sendFailure(Component.translatable("command.cobblemon_npc.load_failed", id,
                        Component.translatable("cobblemon_npc.problem.${problems.first().key}", *problems.first().args.toTypedArray())))
                }
                DialogueStore.ids().size
            })
            .then(Commands.literal("talk").then(Commands.argument("players", EntityArgument.players())
                .then(Commands.argument("dialogue", StringArgumentType.word())
                    .suggests { _, builder -> SharedSuggestionProvider.suggest(DialogueStore.ids(), builder) }
                    .executes { talk(it, null) }
                    .then(Commands.argument("node", StringArgumentType.string()).executes { talk(it, StringArgumentType.getString(it, "node")) }))))
            .then(Commands.literal("end").then(Commands.argument("players", EntityArgument.players()).executes { context ->
                val players = EntityArgument.getPlayers(context, "players")
                players.forEach(DialogueSessions::end)
                players.size
            })))
    }

    private fun talk(context: CommandContext<CommandSourceStack>, node: String?): Int {
        val id = StringArgumentType.getString(context, "dialogue")
        val players = EntityArgument.getPlayers(context, "players")
        val opened = players.count { DialogueSessions.start(it, id, node) }
        if (opened == 0) context.source.sendFailure(Component.translatable("command.cobblemon_npc.unknown_dialogue", id, node ?: ""))
        return opened
    }
}
