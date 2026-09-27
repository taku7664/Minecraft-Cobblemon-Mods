package jbro.cobblemon.mcc.internal.factory

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import jbro.cobblemon.mcc.internal.command.BattleProgressAdminAction
import jbro.cobblemon.mcc.internal.command.BattleProgressCommands
import jbro.cobblemon.mcc.internal.command.BattleProgressResetScope
import jbro.cobblemon.mcc.internal.command.BattleProgressSetResult
import jbro.cobblemon.mcc.internal.compat.fabric.FactoryCommandRuntime
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.server.level.ServerPlayer

internal interface FactoryProgressCommandBackend {
    fun getFloor(player: ServerPlayer, format: FactoryBattleFormat, levelMode: FactoryLevelMode): BattleProgressSetResult

    fun setFloor(
        player: ServerPlayer,
        format: FactoryBattleFormat,
        levelMode: FactoryLevelMode,
        value: Int,
    ): BattleProgressSetResult

    fun resetFloor(
        player: ServerPlayer,
        format: FactoryBattleFormat,
        levelMode: FactoryLevelMode,
        scope: BattleProgressResetScope,
    ): BattleProgressSetResult
}

private object LiveFactoryProgressCommandBackend : FactoryProgressCommandBackend {
    override fun getFloor(
        player: ServerPlayer,
        format: FactoryBattleFormat,
        levelMode: FactoryLevelMode,
    ): BattleProgressSetResult = FactoryCommandRuntime.adminGetFloor(player, format, levelMode)

    override fun setFloor(
        player: ServerPlayer,
        format: FactoryBattleFormat,
        levelMode: FactoryLevelMode,
        value: Int,
    ): BattleProgressSetResult = FactoryCommandRuntime.adminSetFloor(player, format, levelMode, value)

    override fun resetFloor(
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

/** `/mcc factory floor get|set|reset` for operators. */
internal object FactoryProgressCommands {
    fun build(
        backend: FactoryProgressCommandBackend = LiveFactoryProgressCommandBackend,
    ): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("factory")
        .then(
            Commands.literal("floor")
                .then(get(backend))
                .then(set(backend))
                .then(reset(backend)),
        )

    private fun get(backend: FactoryProgressCommandBackend) = Commands.literal("get")
        .requires(BattleProgressCommands::isAdmin)
        .then(
            Commands.argument("player", EntityArgument.player()).then(
                formatArgument().then(
                    levelModeArgument().executes { command ->
                        val format = format(command.source, StringArgumentType.getString(command, "format"))
                            ?: return@executes 0
                        val levelMode = levelMode(command.source, StringArgumentType.getString(command, "level_mode"))
                            ?: return@executes 0
                        val player = EntityArgument.getPlayer(command, "player")
                        BattleProgressCommands.report(command.source, player, "factory.floor", format.name.lowercase(),
                            levelMode.id, BattleProgressAdminAction.GET, null, backend.getFloor(player, format, levelMode))
                    },
                ),
            ),
        )

    private fun set(backend: FactoryProgressCommandBackend) = Commands.literal("set")
        .requires(BattleProgressCommands::isAdmin)
        .then(
            Commands.argument("player", EntityArgument.player()).then(
                formatArgument().then(
                    levelModeArgument().then(
                        Commands.argument("value", IntegerArgumentType.integer(0, BattleProgressCommands.MAX_PROGRESS_VALUE))
                            .executes { command ->
                                val format = format(command.source, StringArgumentType.getString(command, "format"))
                                    ?: return@executes 0
                                val levelMode = levelMode(command.source, StringArgumentType.getString(command, "level_mode"))
                                    ?: return@executes 0
                                val player = EntityArgument.getPlayer(command, "player")
                                BattleProgressCommands.report(command.source, player, "factory.floor",
                                    format.name.lowercase(), levelMode.id, BattleProgressAdminAction.SET, null,
                                    backend.setFloor(player, format, levelMode, IntegerArgumentType.getInteger(command, "value")))
                            },
                    ),
                ),
            ),
        )

    private fun reset(backend: FactoryProgressCommandBackend) = Commands.literal("reset")
        .requires(BattleProgressCommands::isAdmin)
        .then(
            Commands.argument("player", EntityArgument.player()).then(
                formatArgument().then(
                    levelModeArgument().then(
                        BattleProgressCommands.resetScopeArgument().executes { command ->
                            val format = format(command.source, StringArgumentType.getString(command, "format"))
                                ?: return@executes 0
                            val levelMode = levelMode(command.source, StringArgumentType.getString(command, "level_mode"))
                                ?: return@executes 0
                            val scope = BattleProgressCommands.resetScope(command.source, StringArgumentType.getString(command, "scope"))
                                ?: return@executes 0
                            val player = EntityArgument.getPlayer(command, "player")
                            BattleProgressCommands.report(command.source, player, "factory.floor",
                                format.name.lowercase(), levelMode.id, BattleProgressAdminAction.RESET, scope,
                                backend.resetFloor(player, format, levelMode, scope))
                        },
                    ),
                ),
            ),
        )

    private fun formatArgument() = Commands.argument("format", StringArgumentType.word())
        .suggests { _, builder -> SharedSuggestionProvider.suggest(FactoryBattleFormat.entries.map { it.name.lowercase() }, builder) }

    private fun levelModeArgument() = Commands.argument("level_mode", StringArgumentType.word())
        .suggests { _, builder -> SharedSuggestionProvider.suggest(FactoryLevelMode.entries.map { it.id }, builder) }

    private fun format(source: CommandSourceStack, value: String): FactoryBattleFormat? =
        FactoryBattleFormat.entries.singleOrNull { it.name.equals(value, ignoreCase = true) }
            ?: BattleProgressCommands.invalidValue(source, "format", value).let { null }

    private fun levelMode(source: CommandSourceStack, value: String): FactoryLevelMode? =
        FactoryLevelMode.entries.singleOrNull { it.id == value }
            ?: BattleProgressCommands.invalidValue(source, "level_mode", value).let { null }
}
