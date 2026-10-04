package jbro.cobblemon.policy.legend

import com.mojang.authlib.GameProfile
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import jbro.cobblemon.policy.JbroPolicy
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.GameProfileArgument
import net.minecraft.network.chat.Component

/** `/legends <legend> [player]` tells whether the player caught it; `/legends reset <legend> <player>` clears it (OP). */
object LegendCommand {
    private const val KEY = "message.${JbroPolicy.MOD_ID}.legends."
    private val unknownLegend = SimpleCommandExceptionType(Component.translatable(KEY + "unknown"))
    private val onePlayer = SimpleCommandExceptionType(Component.translatable(KEY + "one_player"))

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            val species = Commands.argument("legend", StringArgumentType.word())
                .suggests { _, builder -> SharedSuggestionProvider.suggest(LegendCatalog.byId.keys.sorted(), builder) }
            dispatcher.register(Commands.literal("legends")
                .then(Commands.literal("reset").requires { it.hasPermission(2) }
                    .then(Commands.argument("legend", StringArgumentType.word())
                        .suggests { _, builder -> SharedSuggestionProvider.suggest(LegendCatalog.byId.keys.sorted(), builder) }
                        .then(Commands.argument("player", GameProfileArgument.gameProfile()).executes { reset(it) })))
                .then(species
                    .executes { show(it, it.source.playerOrException.gameProfile) }
                    .then(Commands.argument("player", GameProfileArgument.gameProfile()).executes { show(it, profile(it)) })))
        }
    }

    private fun show(context: CommandContext<CommandSourceStack>, target: GameProfile): Int {
        val legend = legend(context)
        val caught = LegendRecords.get(context.source.server).has(target.id, legend.id)
        val name = Component.translatable(legend.nameKey)
        context.source.sendSystemMessage(Component.translatable(KEY + if (caught) "caught" else "not_caught", target.name, name)
            .withStyle(if (caught) ChatFormatting.GREEN else ChatFormatting.GRAY))
        context.source.sendSystemMessage(Component.translatable(KEY + "rank", LegendRanks.name(legend.rank)).withStyle(ChatFormatting.GRAY))
        if (legend.entry.isNotEmpty()) {
            val names = Component.empty()
            legend.entry.forEachIndexed { index, entry -> if (index > 0) names.append("·"); names.append(speciesName(entry)) }
            val form = when {
                legend.entry.size == 1 -> "entry_one"
                legend.entryAll -> "entry_all"
                else -> "entry_any"
            }
            val entry = Component.translatable(KEY + form, names)
            context.source.sendSystemMessage(Component.translatable(KEY + "entry", entry).withStyle(ChatFormatting.GRAY))
        }
        return if (caught) 1 else 0
    }

    private fun reset(context: CommandContext<CommandSourceStack>): Int {
        val legend = legend(context)
        val target = profile(context)
        val removed = LegendRecords.get(context.source.server).remove(target.id, legend.id)
        val name = Component.translatable(legend.nameKey)
        if (removed) context.source.sendSuccess({ Component.translatable(KEY + "reset_done", target.name, name) }, true)
        else context.source.sendFailure(Component.translatable(KEY + "reset_none", target.name, name))
        return if (removed) 1 else 0
    }

    private fun legend(context: CommandContext<CommandSourceStack>): Legend =
        LegendCatalog[StringArgumentType.getString(context, "legend")] ?: throw unknownLegend.create()

    private fun profile(context: CommandContext<CommandSourceStack>): GameProfile =
        GameProfileArgument.getGameProfiles(context, "player").singleOrNull() ?: throw onePlayer.create()

    private fun speciesName(species: String): Component = Component.translatable("cobblemon.species.$species.name")
}
