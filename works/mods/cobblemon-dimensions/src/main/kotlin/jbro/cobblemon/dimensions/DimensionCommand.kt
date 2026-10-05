package jbro.cobblemon.dimensions

import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component

/**
 * Operator commands for trying the dimensions before their wormholes and portals exist:
 * `/cdim enter <dimension>` enters one near 0, 0 and `/cdim exit` goes back to the entry point, like `/plaza` and `/myroom`.
 */
object DimensionCommand {
    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(Commands.literal("cdim").requires { it.hasPermission(2) }
                .then(Commands.literal("enter")
                    .then(Commands.argument("dimension", StringArgumentType.word())
                        .suggests { _, builder -> SharedSuggestionProvider.suggest(ModDimension.entries.map { it.path }, builder) }
                        .executes { context ->
                            val player = context.source.playerOrException
                            val dimension = ModDimension.byPath(StringArgumentType.getString(context, "dimension"))
                            when {
                                dimension == null -> {
                                    context.source.sendFailure(Component.translatable("command.cobblemon_dimensions.unknown"))
                                    0
                                }
                                DimensionTravel.enter(player, dimension, 0, 0) -> 1
                                else -> {
                                    context.source.sendFailure(Component.translatable("command.cobblemon_dimensions.no_landing"))
                                    0
                                }
                            }
                        }))
                .then(Commands.literal("exit").executes { context ->
                    DimensionTravel.returnHome(context.source.playerOrException)
                    1
                }))
        }
    }
}
