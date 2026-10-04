package jbro.cobblemon.policy.wiki

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import jbro.cobblemon.mcc.api.wiki.WikiApi
import jbro.cobblemon.policy.JbroPolicy
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.GameProfileArgument
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent

/**
 * `/wiki` (or `/위키`) gives a player their own link to the server wiki that More Cobblemon Contents serves,
 * `/wiki reset` a new one that ends the old, and `/wiki link <player>` an operator's copy of someone's link.
 * Shown only while the wiki is running. Loaded only when More Cobblemon Contents is installed.
 */
object WikiCommand {
    private const val KEY = "command.${JbroPolicy.MOD_ID}.wiki"

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(build("wiki"))
            dispatcher.register(build("위키"))
        }
    }

    internal fun build(name: String): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal(name)
        .requires { WikiApi.publicUrl() != null }
        .executes { context ->
            val player = context.source.playerOrException
            send(context.source, WikiApi.linkFor(player.uuid), "$KEY.link")
        }
        .then(Commands.literal("reset").executes { context ->
            val player = context.source.playerOrException
            send(context.source, WikiApi.resetLinkFor(player.uuid), "$KEY.reset")
        })
        .then(Commands.literal("link").requires { it.hasPermission(2) }
            .then(Commands.argument("player", GameProfileArgument.gameProfile()).executes { context ->
                val profiles = GameProfileArgument.getGameProfiles(context, "player")
                val profile = profiles.singleOrNull() ?: run {
                    context.source.sendFailure(Component.translatable("$KEY.one_player"))
                    return@executes 0
                }
                send(context.source, WikiApi.linkFor(profile.id), "$KEY.link_for", profile.name)
            }))

    private fun send(source: CommandSourceStack, url: String?, key: String, vararg arguments: Any): Int {
        if (url == null) {
            source.sendFailure(Component.translatable("$KEY.off"))
            return 0
        }
        val link = Component.literal(url).withStyle { style ->
            style.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(ClickEvent(ClickEvent.Action.OPEN_URL, url))
                .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("$KEY.hover")))
        }
        source.sendSuccess({ Component.translatable(key, *arguments, link) }, false)
        return 1
    }
}
