package jbro.cobblemon.mcc.internal.tower

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import jbro.cobblemon.mcc.api.access.BattleContentAccess
import jbro.cobblemon.mcc.api.access.ContentAccessAction
import jbro.cobblemon.mcc.api.access.ContentAccessDecision
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.mcc.internal.command.BattleProgressAdminAction
import jbro.cobblemon.mcc.internal.command.BattleProgressCommands
import jbro.cobblemon.mcc.internal.command.BattleProgressResetScope
import jbro.cobblemon.mcc.internal.command.BattleProgressSetResult
import jbro.cobblemon.mcc.internal.tower.network.TowerPlayNetworking
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

internal interface TowerProgressCommandBackend {
    fun getStreak(player: ServerPlayer, format: TowerTrack): BattleProgressSetResult

    fun setStreak(player: ServerPlayer, format: TowerTrack, value: Int): BattleProgressSetResult

    fun resetStreak(player: ServerPlayer, format: TowerTrack, scope: BattleProgressResetScope): BattleProgressSetResult
}

private object LiveTowerProgressCommandBackend : TowerProgressCommandBackend {
    override fun getStreak(player: ServerPlayer, format: TowerTrack): BattleProgressSetResult =
        TowerPlayNetworking.adminGetStreak(player, format)

    override fun setStreak(player: ServerPlayer, format: TowerTrack, value: Int): BattleProgressSetResult =
        TowerPlayNetworking.adminSetStreak(player, format, value)

    override fun resetStreak(
        player: ServerPlayer,
        format: TowerTrack,
        scope: BattleProgressResetScope,
    ): BattleProgressSetResult = TowerPlayNetworking.adminSetStreak(
        player,
        format,
        value = 0,
        resetBest = scope == BattleProgressResetScope.ALL,
    )
}

/**
 * `/mcc tower streak get|set|reset` and `/mcc tower access [player]` for operators. `access` answers 1 when the player
 * may enter the tower and 0 when not, so an NPC dialogue (`cmd:mcc tower access`) or a command block can ask the same
 * rule the terminal enforces.
 */
internal object TowerProgressCommands {
    fun build(
        backend: TowerProgressCommandBackend = LiveTowerProgressCommandBackend,
    ): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("tower")
        .requires(BattleProgressCommands::isAdmin)
        .then(
            Commands.literal("streak")
                .then(get(backend))
                .then(set(backend))
                .then(reset(backend)),
        )
        .then(TowerAdminCommands.session())
        .then(TowerAdminCommands.abandon())
        .then(access())

    private fun access() = Commands.literal("access")
        .requires(BattleProgressCommands::isAdmin)
        .executes { command -> access(command.source, command.source.playerOrException) }
        .then(
            Commands.argument("player", EntityArgument.player())
                .executes { command -> access(command.source, EntityArgument.getPlayer(command, "player")) },
        )

    private fun access(source: CommandSourceStack, player: ServerPlayer): Int =
        when (val decision = BattleContentAccess.check(player, ManagedBattleContentIds.BATTLE_TOWER, ContentAccessAction.OPEN)) {
            ContentAccessDecision.Allowed -> {
                source.sendSuccess({ Component.translatable("$ACCESS_KEY.allowed", player.name) }, false)
                1
            }
            is ContentAccessDecision.Denied -> {
                source.sendFailure(Component.translatable("$ACCESS_KEY.denied", player.name,
                    Component.translatable(decision.reasonKey, *decision.arguments.toTypedArray())))
                0
            }
        }

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

    private const val ACCESS_KEY = "command.more_cobblemon_contents.tower.access"

    private fun formatArgument() = Commands.argument("format", StringArgumentType.word())
        .suggests { _, builder -> SharedSuggestionProvider.suggest(TowerTrack.entries.map { it.recordId }, builder) }

    private fun format(source: CommandSourceStack, value: String): TowerTrack? =
        TowerTrack.entries.singleOrNull { it.recordId == value }
            ?: BattleProgressCommands.invalidValue(source, "format", value).let { null }
}
