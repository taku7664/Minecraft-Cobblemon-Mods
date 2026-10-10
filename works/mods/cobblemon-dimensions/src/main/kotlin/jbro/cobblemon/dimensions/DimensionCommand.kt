package jbro.cobblemon.dimensions

import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import jbro.cobblemon.dimensions.wormhole.Wormholes
import net.minecraft.network.chat.Component

/**
 * Operator commands for trying the dimensions before their wormholes and portals exist:
 * `/cdim enter <dimension>` enters one near 0, 0 and `/cdim exit` goes back to the entry point, like `/plaza` and `/myroom`.
 * `/cdim wormhole [small|great|return]` opens a wormhole in the sky near the player right away.
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
                })
                .then(Commands.literal("wormhole")
                    .executes { openWormhole(it.source, "small") }
                    .then(Commands.argument("kind", StringArgumentType.word())
                        .suggests { _, builder -> SharedSuggestionProvider.suggest(listOf("small", "great", "return"), builder) }
                        .executes { openWormhole(it.source, StringArgumentType.getString(it, "kind")) })))
        }
    }

    private fun openWormhole(source: net.minecraft.commands.CommandSourceStack, kind: String): Int {
        val player = source.playerOrException
        val settings = Wormholes.settings
        val (radius, seconds) = when (kind) {
            "great" -> settings.greatRadius to settings.greatSeconds
            "return" -> settings.returnRadius to settings.returnSeconds
            else -> settings.personalRadius to settings.personalSeconds
        }
        val hole = Wormholes.open(player.serverLevel(), player.blockPosition(), 6, 16, radius, seconds, returning = kind == "return")
        if (hole == null) {
            source.sendFailure(Component.translatable("command.cobblemon_dimensions.no_sky"))
            return 0
        }
        if (kind == "great") Wormholes.announceGreat(player.server) else Wormholes.notifyNearby(player, hole)
        return 1
    }
}
