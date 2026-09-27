package jbro.cobblemon.mcc.internal.command

import com.mojang.brigadier.arguments.StringArgumentType
import jbro.cobblemon.mcc.MoreCobblemonContents
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

internal sealed interface BattleProgressSetResult {
    data class Applied(
        val previousCurrent: Long,
        val previousBest: Long,
        val current: Long,
        val best: Long,
    ) : BattleProgressSetResult

    data object ActiveBattle : BattleProgressSetResult
    data object StorageUnavailable : BattleProgressSetResult
}

internal enum class BattleProgressResetScope(val id: String) {
    CURRENT("current"),
    ALL("all"),
}

internal enum class BattleProgressAdminAction(val id: String, val messageId: String) {
    GET("get", "queried"),
    SET("set", "changed"),
    RESET("reset", "reset"),
}

/** Shared pieces of the admin progress subcommands that each content contributes under `/mcc`. */
internal object BattleProgressCommands {
    internal const val ADMIN_PERMISSION_LEVEL = 2
    internal const val MAX_PROGRESS_VALUE = Int.MAX_VALUE - 1

    fun resetScopeArgument() = Commands.argument("scope", StringArgumentType.word())
        .suggests { _, builder -> SharedSuggestionProvider.suggest(BattleProgressResetScope.entries.map { it.id }, builder) }

    fun resetScope(source: CommandSourceStack, value: String): BattleProgressResetScope? =
        BattleProgressResetScope.entries.singleOrNull { it.id == value }
            ?: invalidValue(source, "reset_scope", value).let { null }

    fun isAdmin(source: CommandSourceStack): Boolean = source.hasPermission(ADMIN_PERMISSION_LEVEL)

    fun invalidValue(source: CommandSourceStack, field: String, value: String): Int {
        source.sendFailure(Component.translatable("command.${MoreCobblemonContents.MOD_ID}.progress.error.invalid_$field", value))
        return 0
    }

    fun report(
        source: CommandSourceStack,
        player: ServerPlayer,
        kind: String,
        format: String,
        levelMode: String?,
        action: BattleProgressAdminAction,
        resetScope: BattleProgressResetScope?,
        result: BattleProgressSetResult,
    ): Int = when (result) {
        is BattleProgressSetResult.Applied -> {
            source.sendSuccess(
                {
                    progressMessage(player, kind, format, levelMode, action, resetScope, result)
                },
                action != BattleProgressAdminAction.GET,
            )
            if (action != BattleProgressAdminAction.GET) {
                MoreCobblemonContents.LOGGER.info(
                    "MCC progress admin action={} actor={} target={} target_uuid={} kind={} format={} level_mode={} before_current={} before_best={} after_current={} after_best={}",
                    action.id,
                    source.textName,
                    player.name.string,
                    player.uuid,
                    kind,
                    format,
                    levelMode ?: "-",
                    result.previousCurrent,
                    result.previousBest,
                    result.current,
                    result.best,
                )
            }
            1
        }

        BattleProgressSetResult.ActiveBattle -> {
            source.sendFailure(Component.translatable(
                "command.${MoreCobblemonContents.MOD_ID}.progress.error.active_battle",
                player.name.string,
            ))
            0
        }

        BattleProgressSetResult.StorageUnavailable -> {
            source.sendFailure(Component.translatable(
                "command.${MoreCobblemonContents.MOD_ID}.progress.error.unavailable",
                player.name.string,
            ))
            0
        }
    }

    private fun progressMessage(
        player: ServerPlayer,
        kind: String,
        format: String,
        levelMode: String?,
        action: BattleProgressAdminAction,
        resetScope: BattleProgressResetScope?,
        result: BattleProgressSetResult.Applied,
    ): Component {
        val key = "command.${MoreCobblemonContents.MOD_ID}.$kind.${action.messageId}"
        val scope = resetScope?.let {
            Component.translatable("command.${MoreCobblemonContents.MOD_ID}.progress.reset_scope.${it.id}")
        }
        val shared = mutableListOf<Any>(player.name.string, format)
        levelMode?.let(shared::add)
        scope?.let(shared::add)
        shared += result.current
        shared += result.best
        return Component.translatable(key, *shared.toTypedArray())
    }
}
