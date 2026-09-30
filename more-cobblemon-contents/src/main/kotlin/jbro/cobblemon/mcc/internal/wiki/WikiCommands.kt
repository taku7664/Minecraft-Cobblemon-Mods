package jbro.cobblemon.mcc.internal.wiki

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.command.BattleProgressCommands
import jbro.cobblemon.mcc.internal.command.MccAdminArguments
import jbro.cobblemon.mcc.internal.command.MccCommandContributors
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.GameProfileArgument
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent

/**
 * `/mcc wiki` gives a player their own wiki link, `/mcc wiki reset` a new one that ends the old, and
 * `/mcc wiki link <player>` an operator's copy of someone's link. Shown only while the wiki is running.
 */
internal object WikiCommands {
    private const val KEY = "command.${MoreCobblemonContents.MOD_ID}.wiki"

    fun register() {
        MccCommandContributors.register { build() }
    }

    fun build(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("wiki")
        .requires { WikiServer.running }
        .executes { context ->
            val player = context.source.playerOrException
            send(context.source, WikiServer.linkFor(player.uuid), "$KEY.link")
        }
        .then(Commands.literal("reset").executes { context ->
            val player = context.source.playerOrException
            send(context.source, WikiServer.resetLinkFor(player.uuid), "$KEY.reset")
        })
        .then(Commands.literal("link").requires(BattleProgressCommands::isAdmin)
            .then(Commands.argument("player", GameProfileArgument.gameProfile()).executes { context ->
                val profile = MccAdminArguments.profile(context) ?: return@executes 0
                send(context.source, WikiServer.linkFor(profile.id), "$KEY.link_for", profile.name)
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
