package jbro.cobblemon.policy.admin

import com.mojang.brigadier.arguments.StringArgumentType
import jbro.cobblemon.policy.JbroPolicy
import jbro.cobblemon.policy.support.KoreanText
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSource
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/** Player-facing console execution; no shell access and no additional network listener. */
object ServerCommand {
    private const val KEY = "message.jbro_policy.servercmd."
    private val queues = mutableMapOf<MinecraftServer, ConsoleCommandQueue>()

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(Commands.literal("servercmd").requires(::authorized)
                .executes { it.source.sendFailure(KoreanText.message(KEY + "usage")); 0 }
                .then(Commands.argument("command", StringArgumentType.greedyString()).executes {
                    val source = it.source
                    val player = source.playerOrException
                    val queue = queues.getOrPut(source.server) { ConsoleCommandQueue() }
                    val rejection = queue.submit(player.uuid, source.server.getProfilePermissions(player.gameProfile),
                        StringArgumentType.getString(it, "command"))
                    if (rejection != null) {
                        source.sendFailure(KoreanText.message(KEY + rejection.name.lowercase()))
                        0
                    } else 1
                }))
        }
        ServerTickEvents.END_SERVER_TICK.register { server ->
            queues[server]?.drain(
                permission = { id -> server.playerList.getPlayer(id)?.let { server.getProfilePermissions(it.gameProfile) } },
                execute = { request -> execute(server, request) },
                denied = { request -> server.playerList.getPlayer(request.playerId)?.let {
                    failure(it, "permission")
                } },
            )
        }
        ServerLifecycleEvents.SERVER_STOPPED.register { server -> queues.remove(server)?.clear() }
    }

    private fun authorized(source: CommandSourceStack): Boolean {
        val player = source.player ?: return false
        return source.hasPermission(4) && source.server.getProfilePermissions(player.gameProfile) >= 4
    }

    private fun execute(server: MinecraftServer, request: ConsoleCommandQueue.Request) {
        val player = server.playerList.getPlayer(request.playerId) ?: return
        val output = mutableListOf<Component>()
        var characters = 0
        var truncated = false
        var successful = false
        var result = 0
        val capture = object : CommandSource {
            override fun sendSystemMessage(message: Component) {
                val remaining = 4096 - characters
                if (output.size >= 32 || remaining <= 0) {
                    truncated = true
                    return
                }
                val text = KoreanText.render(message)
                output += if (text.length <= remaining) message.copy() else Component.literal(text.take(remaining))
                characters += minOf(text.length, remaining)
                if (text.length > remaining) truncated = true
            }
            override fun acceptsSuccess() = true
            override fun acceptsFailure() = true
            override fun shouldInformAdmins() = false
        }
        // Starting here, outside a command context, drains execute/function chains before returning.
        val source = server.createCommandSourceStack().withSource(capture).withCallback { success, value ->
            successful = successful || success
            result = value
        }
        try {
            server.commands.performPrefixedCommand(source, request.command)
            player.sendSystemMessage(KoreanText.message(KEY + "output"))
            output.forEach(player::sendSystemMessage)
            if (truncated) player.sendSystemMessage(KoreanText.message(KEY + "truncated"))
            if (successful) player.sendSystemMessage(KoreanText.message(KEY + "completed", result))
            else failure(player, "failed")
            // Arguments can contain private data; record only the caller and root command.
            JbroPolicy.LOGGER.info("[servercmd] {} executed /{} (success={}, result={})",
                player.gameProfile.name, request.command.substringBefore(' '), successful, result)
        } catch (exception: RuntimeException) {
            JbroPolicy.LOGGER.warn("[servercmd] Console command failed for {}", player.gameProfile.name, exception)
            failure(player, "failed")
        }
    }

    private fun failure(player: ServerPlayer, key: String) {
        player.sendSystemMessage(KoreanText.message(KEY + key).withStyle(ChatFormatting.RED))
    }
}
