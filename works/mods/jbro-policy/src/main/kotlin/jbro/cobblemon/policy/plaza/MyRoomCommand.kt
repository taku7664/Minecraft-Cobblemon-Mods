package jbro.cobblemon.policy.plaza

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.mojang.brigadier.suggestion.SuggestionProvider
import jbro.cobblemon.policy.JbroPolicy
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component

/**
 * `/마이룸`, Simple MyRoom's commands in Korean. Each Korean subcommand runs the mod's own English one, so the rules,
 * permissions and messages stay the mod's; player names are suggested by asking the English command.
 */
object MyRoomCommand {
    /** Simple MyRoom's roots in this server's config, tried in order. */
    private val ROOTS = listOf("myroom", "room")
    private val UNAVAILABLE = SimpleCommandExceptionType(Component.translatable("message.${JbroPolicy.MOD_ID}.myroom.unavailable"))

    fun register() {
        if (!FabricLoader.getInstance().isModLoaded("simple_myroom")) return
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> dispatcher.register(build()) }
    }

    private fun build(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("마이룸")
        .requires { it.isPlayer }
        .executes(forward(""))
        .then(Commands.literal("들어가기").executes(forward("enter")).then(player("enter")))
        .then(Commands.literal("나가기").executes(forward("exit")))
        .then(Commands.literal("공개").executes(forward("public true")))
        .then(Commands.literal("비공개").executes(forward("public false")))
        .then(Commands.literal("권한허용").then(player("trust")))
        .then(Commands.literal("권한해제").then(player("untrust")))
        .then(Commands.literal("차단").then(player("ban")))
        .then(Commands.literal("차단해제").then(player("unban")))
        .then(Commands.literal("내보내기").then(player("kick")))
        .then(Commands.literal("상시활성").executes(forward("active"))
            .then(Commands.literal("켜기").executes(forward("active on")))
            .then(Commands.literal("끄기").executes(forward("active off")))
            .then(Commands.literal("상태").executes(forward("active status"))))
        .then(Commands.literal("스폰설정").executes(forward("setspawn"))
            .then(Commands.literal("초기화").executes(forward("setspawn reset"))))
        .then(Commands.literal("방문자").executes(forward("visitors")))
        .then(Commands.literal("알림").executes(forward("notify"))
            .then(Commands.literal("켜기").executes(forward("notify on")))
            .then(Commands.literal("끄기").executes(forward("notify off"))))
        .then(Commands.literal("리로드").requires { it.hasPermission(2) }.executes(forward("reload")))

    /** A `<플레이어>` argument that runs `[sub] <player>`. */
    private fun player(sub: String): RequiredArgumentBuilder<CommandSourceStack, String> =
        Commands.argument("player", StringArgumentType.word())
            .suggests(suggestLike("$sub "))
            .executes { context -> run(context.source, "$sub ${StringArgumentType.getString(context, "player")}") }

    private fun forward(sub: String) = Command<CommandSourceStack> { context -> run(context.source, sub) }

    private fun run(source: CommandSourceStack, sub: String): Int {
        val dispatcher = source.server.commands.dispatcher
        val root = ROOTS.firstOrNull { dispatcher.root.getChild(it) != null } ?: throw UNAVAILABLE.create()
        return dispatcher.execute(if (sub.isEmpty()) root else "$root $sub", source)
    }

    /** What Simple MyRoom would suggest after `[prefix]`, offered at this argument. */
    private fun suggestLike(prefix: String) = SuggestionProvider<CommandSourceStack> { context, builder ->
        val dispatcher = context.source.server.commands.dispatcher
        val root = ROOTS.firstOrNull { dispatcher.root.getChild(it) != null } ?: return@SuggestionProvider builder.buildFuture()
        val parse = dispatcher.parse("$root $prefix${builder.remaining}", context.source)
        dispatcher.getCompletionSuggestions(parse).thenApply { found ->
            found.list.forEach { builder.suggest(it.text) }
            builder.build()
        }
    }
}
