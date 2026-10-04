package jbro.cobblemon.policy.api

import com.mojang.brigadier.arguments.StringArgumentType
import jbro.cobblemon.policy.JbroPolicy
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.ChatFormatting
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer

/** Server-wide notices that read `[공지] message`. Other mods call [broadcast]; operators use `/announce` or `/공지`. */
object Announcements {
    /** Server management level, the same as /kick and /ban. */
    const val PERMISSION_LEVEL = 3

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(MinecraftServer, Component) -> Unit>()

    /** Hears every notice after it is broadcast, such as the Discord bot passing it on. */
    internal fun onBroadcast(listener: (MinecraftServer, Component) -> Unit) {
        listeners += listener
    }

    @JvmStatic
    fun broadcast(server: MinecraftServer, message: Component) {
        val notice = Component.empty()
            .append(Component.translatable("message.${JbroPolicy.MOD_ID}.announcement").withStyle(ChatFormatting.BLUE, ChatFormatting.BOLD))
            .append(" ")
            .append(message)
        server.playerList.broadcastSystemMessage(notice, false)
        listeners.forEach { listener ->
            try {
                listener(server, message)
            } catch (failure: RuntimeException) {
                JbroPolicy.LOGGER.warn("Announcement listener failed", failure)
            }
        }
    }

    internal fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            for (name in listOf("announce", "공지")) {
                dispatcher.register(Commands.literal(name).requires { it.hasPermission(PERMISSION_LEVEL) }
                    .then(Commands.argument("message", StringArgumentType.greedyString()).executes { context ->
                        broadcast(context.source.server, Component.literal(StringArgumentType.getString(context, "message")))
                        1
                    }))
            }
        }
    }
}
