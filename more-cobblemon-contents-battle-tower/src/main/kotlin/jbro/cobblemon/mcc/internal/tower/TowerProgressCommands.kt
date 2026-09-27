package jbro.cobblemon.mcc.internal.tower

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import jbro.cobblemon.mcc.internal.command.BattleProgressAdminAction
import jbro.cobblemon.mcc.internal.command.BattleProgressCommands
import jbro.cobblemon.mcc.internal.command.BattleProgressResetScope
import jbro.cobblemon.mcc.internal.command.BattleProgressSetResult
import jbro.cobblemon.mcc.internal.tower.network.TowerPlayNetworking
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.server.level.ServerPlayer

internal interface TowerProgressCommandBackend {
    fun getStreak(player: ServerPlayer, format: TowerBattleFormat): BattleProgressSetResult

    fun setStreak(player: ServerPlayer, format: TowerBattleFormat, value: Int): BattleProgressSetResult

    fun resetStreak(player: ServerPlayer, format: TowerBattleFormat, scope: BattleProgressResetScope): BattleProgressSetResult
}

private object LiveTowerProgressCommandBackend : TowerProgressCommandBackend {
    override fun getStreak(player: ServerPlayer, format: TowerBattleFormat): BattleProgressSetResult =
        TowerPlayNetworking.adminGetStreak(player, format)

    override fun setStreak(player: ServerPlayer, format: TowerBattleFormat, value: Int): BattleProgressSetResult =
        TowerPlayNetworking.adminSetStreak(player, format, value)

    override fun resetStreak(
        player: ServerPlayer,
        format: TowerBattleFormat,
        scope: BattleProgressResetScope,
    ): BattleProgressSetResult = TowerPlayNetworking.adminSetStreak(
        player,
        format,
        value = 0,
        resetBest = scope == BattleProgressResetScope.ALL,
    )
}

/** `/mcc tower streak get|set|reset` for operators. */
internal object TowerProgressCommands {
    fun build(
        backend: TowerProgressCommandBackend = LiveTowerProgressCommandBackend,
    ): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("tower")
        .then(
            Commands.literal("streak")
                .then(get(backend))
                .then(set(backend))
                .then(reset(backend)),
        )

    private fun get(backend: TowerProgressCommandBackend) = Commands.literal("get")
        .requires(BattleProgressCommands::isAdmin)
        .then(
            Commands.argument("player", EntityArgument.player()).then(
                formatArgument().executes { command ->
                    val format = format(command.source, StringArgumentType.getString(command, "format"))
                        ?: return@executes 0
                    val player = EntityArgument.getPlayer(command, "player")
                    BattleProgressCommands.report(command.source, player, "tower.streak", format.recordId, null,
                        BattleProgressAdminAction.GET, null, backend.getStreak(player, format))
                },
            ),
        )

    private fun set(backend: TowerProgressCommandBackend) = Commands.literal("set")
        .requires(BattleProgressCommands::isAdmin)
        .then(
            Commands.argument("player", EntityArgument.player()).then(
                formatArgument().then(
                    Commands.argument("value", IntegerArgumentType.integer(0, BattleProgressCommands.MAX_PROGRESS_VALUE))
                        .executes { command ->
                            val format = format(command.source, StringArgumentType.getString(command, "format"))
                                ?: return@executes 0
                            val player = EntityArgument.getPlayer(command, "player")
                            BattleProgressCommands.report(command.source, player, "tower.streak", format.recordId, null,
                                BattleProgressAdminAction.SET, null,
                                backend.setStreak(player, format, IntegerArgumentType.getInteger(command, "value")))
                        },
                ),
            ),
        )

    private fun reset(backend: TowerProgressCommandBackend) = Commands.literal("reset")
        .requires(BattleProgressCommands::isAdmin)
        .then(
            Commands.argument("player", EntityArgument.player()).then(
                formatArgument().then(
                    BattleProgressCommands.resetScopeArgument().executes { command ->
                        val format = format(command.source, StringArgumentType.getString(command, "format"))
                            ?: return@executes 0
                        val scope = BattleProgressCommands.resetScope(command.source, StringArgumentType.getString(command, "scope"))
                            ?: return@executes 0
                        val player = EntityArgument.getPlayer(command, "player")
                        BattleProgressCommands.report(command.source, player, "tower.streak", format.recordId, null,
                            BattleProgressAdminAction.RESET, scope, backend.resetStreak(player, format, scope))
                    },
                ),
            ),
        )

    private fun formatArgument() = Commands.argument("format", StringArgumentType.word())
        .suggests { _, builder -> SharedSuggestionProvider.suggest(TowerBattleFormat.entries.map { it.recordId }, builder) }

    private fun format(source: CommandSourceStack, value: String): TowerBattleFormat? =
        TowerBattleFormat.entries.singleOrNull { it.recordId == value }
            ?: BattleProgressCommands.invalidValue(source, "format", value).let { null }
}
