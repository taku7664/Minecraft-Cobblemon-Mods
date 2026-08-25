package jbro.cobblemon.morebattlecontent.internal.command

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.compat.fabric.FactoryCommandRuntime
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryBattleFormat
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryLevelMode
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleFormat
import jbro.cobblemon.morebattlecontent.internal.tower.network.TowerPlayNetworking
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.EntityArgument
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

internal interface BattleProgressCommandBackend {
    fun getTowerStreak(player: ServerPlayer, format: TowerBattleFormat): BattleProgressSetResult

    fun setTowerStreak(player: ServerPlayer, format: TowerBattleFormat, value: Int): BattleProgressSetResult

    fun resetTowerStreak(
        player: ServerPlayer,
        format: TowerBattleFormat,
        scope: BattleProgressResetScope,
    ): BattleProgressSetResult

    fun getFactoryFloor(
        player: ServerPlayer,
        format: FactoryBattleFormat,
        levelMode: FactoryLevelMode,
    ): BattleProgressSetResult

    fun setFactoryFloor(
        player: ServerPlayer,
        format: FactoryBattleFormat,
        levelMode: FactoryLevelMode,
        value: Int,
    ): BattleProgressSetResult

    fun resetFactoryFloor(
        player: ServerPlayer,
        format: FactoryBattleFormat,
        levelMode: FactoryLevelMode,
        scope: BattleProgressResetScope,
    ): BattleProgressSetResult
}

private object LiveBattleProgressCommandBackend : BattleProgressCommandBackend {
    override fun getTowerStreak(player: ServerPlayer, format: TowerBattleFormat): BattleProgressSetResult =
        TowerPlayNetworking.adminGetStreak(player, format)

    override fun setTowerStreak(
        player: ServerPlayer,
        format: TowerBattleFormat,
        value: Int,
    ): BattleProgressSetResult = TowerPlayNetworking.adminSetStreak(player, format, value)

    override fun resetTowerStreak(
        player: ServerPlayer,
        format: TowerBattleFormat,
        scope: BattleProgressResetScope,
    ): BattleProgressSetResult = TowerPlayNetworking.adminSetStreak(
        player,
        format,
        value = 0,
        resetBest = scope == BattleProgressResetScope.ALL,
    )

    override fun getFactoryFloor(
        player: ServerPlayer,
        format: FactoryBattleFormat,
        levelMode: FactoryLevelMode,
    ): BattleProgressSetResult = FactoryCommandRuntime.adminGetFloor(player, format, levelMode)

    override fun setFactoryFloor(
        player: ServerPlayer,
        format: FactoryBattleFormat,
        levelMode: FactoryLevelMode,
        value: Int,
    ): BattleProgressSetResult = FactoryCommandRuntime.adminSetFloor(player, format, levelMode, value)

    override fun resetFactoryFloor(
        player: ServerPlayer,
        format: FactoryBattleFormat,
        levelMode: FactoryLevelMode,
        scope: BattleProgressResetScope,
    ): BattleProgressSetResult = FactoryCommandRuntime.adminSetFloor(
        player,
        format,
        levelMode,
        value = 0,
        resetBest = scope == BattleProgressResetScope.ALL,
    )
}

internal object BattleProgressCommands {
    internal const val ADMIN_PERMISSION_LEVEL = 2

    fun tower(
        backend: BattleProgressCommandBackend = LiveBattleProgressCommandBackend,
    ): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("tower")
        .then(
            Commands.literal("streak")
                .then(towerGet(backend))
                .then(towerSet(backend))
                .then(towerReset(backend)),
        )

    fun factory(
        backend: BattleProgressCommandBackend = LiveBattleProgressCommandBackend,
    ): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("factory")
        .then(
            Commands.literal("floor")
                .then(factoryGet(backend))
                .then(factorySet(backend))
                .then(factoryReset(backend)),
        )

    private fun towerGet(backend: BattleProgressCommandBackend) = Commands.literal("get")
        .requires(::isAdmin)
        .then(
            Commands.argument("player", EntityArgument.player()).then(
                towerFormatArgument().executes { command ->
                    val format = towerFormat(command.source, StringArgumentType.getString(command, "format"))
                        ?: return@executes 0
                    val player = EntityArgument.getPlayer(command, "player")
                    report(command.source, player, "tower.streak", format.recordId, null, AdminAction.GET, null,
                        backend.getTowerStreak(player, format))
                },
            ),
        )

    private fun towerSet(backend: BattleProgressCommandBackend) = Commands.literal("set")
        .requires(::isAdmin)
        .then(
            Commands.argument("player", EntityArgument.player()).then(
                towerFormatArgument().then(
                    Commands.argument("value", IntegerArgumentType.integer(0, MAX_PROGRESS_VALUE)).executes { command ->
                        val format = towerFormat(command.source, StringArgumentType.getString(command, "format"))
                            ?: return@executes 0
                        val player = EntityArgument.getPlayer(command, "player")
                        report(command.source, player, "tower.streak", format.recordId, null, AdminAction.SET, null,
                            backend.setTowerStreak(player, format, IntegerArgumentType.getInteger(command, "value")))
                    },
                ),
            ),
        )

    private fun towerReset(backend: BattleProgressCommandBackend) = Commands.literal("reset")
        .requires(::isAdmin)
        .then(
            Commands.argument("player", EntityArgument.player()).then(
                towerFormatArgument().then(
                    resetScopeArgument().executes { command ->
                        val format = towerFormat(command.source, StringArgumentType.getString(command, "format"))
                            ?: return@executes 0
                        val scope = resetScope(command.source, StringArgumentType.getString(command, "scope"))
                            ?: return@executes 0
                        val player = EntityArgument.getPlayer(command, "player")
                        report(command.source, player, "tower.streak", format.recordId, null, AdminAction.RESET, scope,
                            backend.resetTowerStreak(player, format, scope))
                    },
                ),
            ),
        )

    private fun factoryGet(backend: BattleProgressCommandBackend) = Commands.literal("get")
        .requires(::isAdmin)
        .then(
            Commands.argument("player", EntityArgument.player()).then(
                factoryFormatArgument().then(
                    factoryLevelModeArgument().executes { command ->
                        val format = factoryFormat(command.source, StringArgumentType.getString(command, "format"))
                            ?: return@executes 0
                        val levelMode = factoryLevelMode(
                            command.source,
                            StringArgumentType.getString(command, "level_mode"),
                        ) ?: return@executes 0
                        val player = EntityArgument.getPlayer(command, "player")
                        report(command.source, player, "factory.floor", format.name.lowercase(), levelMode.id,
                            AdminAction.GET, null, backend.getFactoryFloor(player, format, levelMode))
                    },
                ),
            ),
        )

    private fun factorySet(backend: BattleProgressCommandBackend) = Commands.literal("set")
        .requires(::isAdmin)
        .then(
            Commands.argument("player", EntityArgument.player()).then(
                factoryFormatArgument().then(
                    factoryLevelModeArgument().then(
                        Commands.argument("value", IntegerArgumentType.integer(0, MAX_PROGRESS_VALUE)).executes { command ->
                            val format = factoryFormat(command.source, StringArgumentType.getString(command, "format"))
                                ?: return@executes 0
                            val levelMode = factoryLevelMode(
                                command.source,
                                StringArgumentType.getString(command, "level_mode"),
                            ) ?: return@executes 0
                            val player = EntityArgument.getPlayer(command, "player")
                            report(command.source, player, "factory.floor", format.name.lowercase(), levelMode.id,
                                AdminAction.SET, null, backend.setFactoryFloor(
                                    player,
                                    format,
                                    levelMode,
                                    IntegerArgumentType.getInteger(command, "value"),
                                ))
                        },
                    ),
                ),
            ),
        )

    private fun factoryReset(backend: BattleProgressCommandBackend) = Commands.literal("reset")
        .requires(::isAdmin)
        .then(
            Commands.argument("player", EntityArgument.player()).then(
                factoryFormatArgument().then(
                    factoryLevelModeArgument().then(
                        resetScopeArgument().executes { command ->
                            val format = factoryFormat(command.source, StringArgumentType.getString(command, "format"))
                                ?: return@executes 0
                            val levelMode = factoryLevelMode(
                                command.source,
                                StringArgumentType.getString(command, "level_mode"),
                            ) ?: return@executes 0
                            val scope = resetScope(command.source, StringArgumentType.getString(command, "scope"))
                                ?: return@executes 0
                            val player = EntityArgument.getPlayer(command, "player")
                            report(command.source, player, "factory.floor", format.name.lowercase(), levelMode.id,
                                AdminAction.RESET, scope, backend.resetFactoryFloor(player, format, levelMode, scope))
                        },
                    ),
                ),
            ),
        )

    private fun towerFormatArgument() = Commands.argument("format", StringArgumentType.word())
        .suggests { _, builder -> SharedSuggestionProvider.suggest(TowerBattleFormat.entries.map { it.recordId }, builder) }

    private fun factoryFormatArgument() = Commands.argument("format", StringArgumentType.word())
        .suggests { _, builder -> SharedSuggestionProvider.suggest(FactoryBattleFormat.entries.map { it.name.lowercase() }, builder) }

    private fun factoryLevelModeArgument() = Commands.argument("level_mode", StringArgumentType.word())
        .suggests { _, builder -> SharedSuggestionProvider.suggest(FactoryLevelMode.entries.map { it.id }, builder) }

    private fun resetScopeArgument() = Commands.argument("scope", StringArgumentType.word())
        .suggests { _, builder -> SharedSuggestionProvider.suggest(BattleProgressResetScope.entries.map { it.id }, builder) }

    private fun towerFormat(source: CommandSourceStack, value: String): TowerBattleFormat? =
        TowerBattleFormat.entries.singleOrNull { it.recordId == value }
            ?: invalidValue(source, "format", value).let { null }

    private fun factoryFormat(source: CommandSourceStack, value: String): FactoryBattleFormat? =
        FactoryBattleFormat.entries.singleOrNull { it.name.equals(value, ignoreCase = true) }
            ?: invalidValue(source, "format", value).let { null }

    private fun factoryLevelMode(source: CommandSourceStack, value: String): FactoryLevelMode? =
        FactoryLevelMode.entries.singleOrNull { it.id == value }
            ?: invalidValue(source, "level_mode", value).let { null }

    private fun resetScope(source: CommandSourceStack, value: String): BattleProgressResetScope? =
        BattleProgressResetScope.entries.singleOrNull { it.id == value }
            ?: invalidValue(source, "reset_scope", value).let { null }

    private fun isAdmin(source: CommandSourceStack): Boolean = source.hasPermission(ADMIN_PERMISSION_LEVEL)

    private fun invalidValue(source: CommandSourceStack, field: String, value: String): Int {
        source.sendFailure(Component.translatable("command.${MoreBattleContent.MOD_ID}.progress.error.invalid_$field", value))
        return 0
    }

    private fun report(
        source: CommandSourceStack,
        player: ServerPlayer,
        kind: String,
        format: String,
        levelMode: String?,
        action: AdminAction,
        resetScope: BattleProgressResetScope?,
        result: BattleProgressSetResult,
    ): Int = when (result) {
        is BattleProgressSetResult.Applied -> {
            source.sendSuccess(
                {
                    progressMessage(player, kind, format, levelMode, action, resetScope, result)
                },
                action != AdminAction.GET,
            )
            if (action != AdminAction.GET) {
                MoreBattleContent.LOGGER.info(
                    "MBC progress admin action={} actor={} target={} target_uuid={} kind={} format={} level_mode={} before_current={} before_best={} after_current={} after_best={}",
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
                "command.${MoreBattleContent.MOD_ID}.progress.error.active_battle",
                player.name.string,
            ))
            0
        }

        BattleProgressSetResult.StorageUnavailable -> {
            source.sendFailure(Component.translatable(
                "command.${MoreBattleContent.MOD_ID}.progress.error.unavailable",
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
        action: AdminAction,
        resetScope: BattleProgressResetScope?,
        result: BattleProgressSetResult.Applied,
    ): Component {
        val key = "command.${MoreBattleContent.MOD_ID}.$kind.${action.messageId}"
        val scope = resetScope?.let {
            Component.translatable("command.${MoreBattleContent.MOD_ID}.progress.reset_scope.${it.id}")
        }
        val shared = mutableListOf<Any>(player.name.string, format)
        levelMode?.let(shared::add)
        scope?.let(shared::add)
        shared += result.current
        shared += result.best
        return Component.translatable(key, *shared.toTypedArray())
    }

    private enum class AdminAction(val id: String, val messageId: String) {
        GET("get", "queried"),
        SET("set", "changed"),
        RESET("reset", "reset"),
    }

    private const val MAX_PROGRESS_VALUE = Int.MAX_VALUE - 1
}
