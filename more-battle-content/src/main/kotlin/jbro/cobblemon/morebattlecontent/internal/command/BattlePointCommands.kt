package jbro.cobblemon.morebattlecontent.internal.command

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import java.util.UUID
import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.bp.BattlePointApplyResult
import jbro.cobblemon.morebattlecontent.internal.bp.BattlePointApplyStatus
import jbro.cobblemon.morebattlecontent.internal.bp.BattlePointOperation
import jbro.cobblemon.morebattlecontent.internal.bp.BattlePointRequest
import jbro.cobblemon.morebattlecontent.internal.bp.BattlePointService
import jbro.cobblemon.morebattlecontent.internal.bp.BattlePointSourceId
import jbro.cobblemon.morebattlecontent.internal.bp.MAX_BATTLE_POINT_HISTORY_QUERY
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

internal object BattlePointCommands {
    internal const val ADMIN_PERMISSION_LEVEL = 2
    private const val DEFAULT_HISTORY_COUNT = 10
    private const val DEFAULT_ADMIN_REASON = "operator_adjustment"
    private val ADMIN_SOURCE = BattlePointSourceId("${MoreBattleContent.MOD_ID}:admin_command")

    fun build(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("bp")
        .executes { command -> showBalance(command.source, command.source.playerOrException) }
        .then(
            Commands.literal("get")
                .requires { source -> source.hasPermission(ADMIN_PERMISSION_LEVEL) }
                .then(
                    Commands.argument("player", EntityArgument.player()).executes { command ->
                        showBalance(command.source, EntityArgument.getPlayer(command, "player"))
                    },
                ),
        )
        .then(history())
        .then(adminMutation("add", minimum = 1L) { BattlePointOperation.AdminAdd(it) })
        .then(adminMutation("remove", minimum = 1L) { BattlePointOperation.AdminRemove(it) })
        .then(adminMutation("set", minimum = 0L) { BattlePointOperation.AdminSet(it) })

    private fun history() = Commands.literal("history")
        .executes { command -> showHistory(command.source, command.source.playerOrException, DEFAULT_HISTORY_COUNT) }
        .then(
            Commands.argument("count", IntegerArgumentType.integer(1, MAX_BATTLE_POINT_HISTORY_QUERY)).executes { command ->
                showHistory(
                    command.source,
                    command.source.playerOrException,
                    IntegerArgumentType.getInteger(command, "count"),
                )
            },
        )
        .then(
            Commands.argument("player", EntityArgument.player())
                .requires { source -> source.hasPermission(ADMIN_PERMISSION_LEVEL) }
                .executes { command ->
                    showHistory(command.source, EntityArgument.getPlayer(command, "player"), DEFAULT_HISTORY_COUNT)
                }
                .then(
                    Commands.argument("count", IntegerArgumentType.integer(1, MAX_BATTLE_POINT_HISTORY_QUERY))
                        .executes { command ->
                            showHistory(
                                command.source,
                                EntityArgument.getPlayer(command, "player"),
                                IntegerArgumentType.getInteger(command, "count"),
                            )
                        },
                ),
        )

    private fun adminMutation(
        name: String,
        minimum: Long,
        operation: (Long) -> BattlePointOperation,
    ) = Commands.literal(name)
        .requires { source -> source.hasPermission(ADMIN_PERMISSION_LEVEL) }
        .then(
            Commands.argument("player", EntityArgument.player()).then(
                Commands.argument("amount", LongArgumentType.longArg(minimum))
                    .executes { command ->
                        mutate(
                            command.source,
                            EntityArgument.getPlayer(command, "player"),
                            operation(LongArgumentType.getLong(command, "amount")),
                            DEFAULT_ADMIN_REASON,
                        )
                    }
                    .then(
                        Commands.argument("reason", StringArgumentType.greedyString()).executes { command ->
                            mutate(
                                command.source,
                                EntityArgument.getPlayer(command, "player"),
                                operation(LongArgumentType.getLong(command, "amount")),
                                StringArgumentType.getString(command, "reason"),
                            )
                        },
                    ),
            ),
        )

    private fun showBalance(source: CommandSourceStack, player: ServerPlayer): Int {
        if (!BattlePointService.isAvailable(source.server)) return reportUnavailable(source, player)
        val balance = BattlePointService.balance(source.server, player.uuid)
        source.sendSuccess(
            {
                Component.translatable(
                    "command.${MoreBattleContent.MOD_ID}.bp.balance",
                    player.name.string,
                    balance,
                )
            },
            false,
        )
        return 1
    }

    private fun showHistory(source: CommandSourceStack, player: ServerPlayer, count: Int): Int {
        if (!BattlePointService.isAvailable(source.server)) return reportUnavailable(source, player)
        val history = BattlePointService.history(source.server, player.uuid, count)
        source.sendSuccess(
            {
                Component.translatable(
                    "command.${MoreBattleContent.MOD_ID}.bp.history.header",
                    player.name.string,
                    history.size,
                )
            },
            false,
        )
        history.forEach { transaction ->
            source.sendSuccess(
                {
                    Component.translatable(
                        "command.${MoreBattleContent.MOD_ID}.bp.history.entry",
                        transaction.recordedAtEpochMillis,
                        transaction.kind.name.lowercase(),
                        transaction.requestedValue,
                        transaction.balanceBefore,
                        transaction.balanceAfter,
                        transaction.sourceId.value,
                        transaction.reason,
                    )
                },
                false,
            )
        }
        return 1
    }

    private fun reportUnavailable(source: CommandSourceStack, player: ServerPlayer): Int {
        source.sendFailure(
            Component.translatable("command.${MoreBattleContent.MOD_ID}.bp.error.unavailable", player.name.string),
        )
        return 0
    }

    private fun mutate(
        source: CommandSourceStack,
        player: ServerPlayer,
        operation: BattlePointOperation,
        reason: String,
    ): Int {
        val result = try {
            BattlePointService.apply(
                source.server,
                BattlePointRequest(UUID.randomUUID(), player.uuid, operation, ADMIN_SOURCE, reason),
            )
        } catch (_: IllegalArgumentException) {
            source.sendFailure(Component.translatable("command.${MoreBattleContent.MOD_ID}.bp.error.invalid_request"))
            return 0
        }
        return reportMutation(source, player, result)
    }

    private fun reportMutation(
        source: CommandSourceStack,
        player: ServerPlayer,
        result: BattlePointApplyResult,
    ): Int = when (result.status) {
        BattlePointApplyStatus.APPLIED,
        BattlePointApplyStatus.ALREADY_APPLIED,
        -> {
            source.sendSuccess(
                {
                    Component.translatable(
                        "command.${MoreBattleContent.MOD_ID}.bp.changed",
                        player.name.string,
                        result.balance,
                        result.transaction?.transactionId.toString(),
                    )
                },
                true,
            )
            1
        }

        else -> {
            source.sendFailure(
                Component.translatable(
                    "command.${MoreBattleContent.MOD_ID}.bp.error.${result.status.name.lowercase()}",
                    player.name.string,
                    result.balance,
                ),
            )
            0
        }
    }
}
