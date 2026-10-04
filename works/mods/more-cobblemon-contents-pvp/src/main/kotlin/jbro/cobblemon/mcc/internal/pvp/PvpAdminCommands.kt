package jbro.cobblemon.mcc.internal.pvp

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.command.BattleProgressCommands
import jbro.cobblemon.mcc.internal.command.MccAdminArguments
import jbro.cobblemon.mcc.internal.command.PvpCommandStatus
import jbro.cobblemon.mcc.internal.pvp.network.PvpPlayNetworking
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.commands.arguments.GameProfileArgument
import net.minecraft.network.chat.Component

/**
 * `/mcc pvp` for operators: rooms, closing and kicking, a pending challenge, arena slots and the lounge rescue.
 * A match in battle is ended with `/mcc battle end`, where a forfeit gives the other player the win.
 */
internal object PvpAdminCommands {
    private const val KEY = "command.${MoreCobblemonContents.MOD_ID}.pvp.admin"

    fun build(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("pvp")
        .requires(BattleProgressCommands::isAdmin)
        .then(Commands.literal("rooms").executes { context ->
            val server = context.source.server
            val rooms = PvpPlayNetworking.adminRooms()
            context.source.sendSuccess({ Component.translatable("$KEY.rooms.header", rooms.size) }, false)
            rooms.forEach { (room, battleId) ->
                fun name(id: java.util.UUID?) = id?.let { MccAdminArguments.name(server, it) } ?: "-"
                context.source.sendSuccess({
                    Component.translatable("$KEY.rooms.entry", name(room.hostId), room.phase.name.lowercase(),
                        room.settings.format.name.lowercase(), name(room.leftPlayerId), name(room.rightPlayerId),
                        room.spectatorIds.size, room.settings.visibility.name.lowercase(), battleId?.toString()?.take(8) ?: "-")
                }, false)
            }
            1
        })
        .then(Commands.literal("room")
            .then(Commands.literal("close").then(Commands.argument("player", GameProfileArgument.gameProfile()).executes { context ->
                val player = MccAdminArguments.profile(context) ?: return@executes 0
                val room = PvpPlayNetworking.adminRoomFor(player.id)
                if (room == null || !PvpPlayNetworking.adminCloseRoom(context.source.server, room.roomId)) {
                    context.source.sendFailure(Component.translatable("$KEY.room.none", player.name))
                    return@executes 0
                }
                context.source.sendSuccess({ Component.translatable("$KEY.room.closed", player.name) }, true)
                MoreCobblemonContents.LOGGER.info("MCC admin pvp room close actor={} room={}", context.source.textName, room.roomId)
                1
            }))
            .then(Commands.literal("kick").then(Commands.argument("player", GameProfileArgument.gameProfile()).executes { context ->
                val player = MccAdminArguments.profile(context) ?: return@executes 0
                when (PvpPlayNetworking.adminKick(player.id)) {
                    PvpPlayNetworking.AdminKick.NO_ROOM -> fail(context.source, "$KEY.room.none", player.name)
                    PvpPlayNetworking.AdminKick.COMPETITOR -> fail(context.source, "$KEY.room.competitor", player.name)
                    PvpPlayNetworking.AdminKick.FAILED -> fail(context.source, "$KEY.room.kick_failed", player.name)
                    PvpPlayNetworking.AdminKick.KICKED -> {
                        context.source.sendSuccess({ Component.translatable("$KEY.room.kicked", player.name) }, true)
                        context.source.server.playerList.getPlayer(player.id)
                            ?.sendSystemMessage(Component.translatable("$KEY.room.kicked_notice"))
                        1
                    }
                }
            })))
        .then(Commands.literal("challenge").then(Commands.literal("cancel")
            .then(Commands.argument("player", EntityArgument.player()).executes { context ->
                val player = EntityArgument.getPlayer(context, "player")
                val outcome = PvpPlayNetworking.cancel(player)
                if (outcome.status != PvpCommandStatus.APPLIED) {
                    return@executes fail(context.source, "$KEY.challenge.none", player.name.string)
                }
                context.source.sendSuccess({ Component.translatable("$KEY.challenge.cancelled", player.name.string) }, true)
                1
            })))
        .then(Commands.literal("arena")
            .then(Commands.literal("list").executes { context ->
                val arenas = PvpPlayNetworking.adminArenas()
                context.source.sendSuccess({ Component.translatable("$KEY.arena.header", arenas.size) }, false)
                arenas.forEach { (matchId, lease, held) ->
                    context.source.sendSuccess({
                        Component.translatable(if (held) "$KEY.arena.entry" else "$KEY.arena.orphan", lease.index,
                            lease.centerX, lease.centerY, lease.centerZ, matchId.toString().take(8))
                    }, false)
                }
                1
            })
            .then(Commands.literal("release").then(Commands.argument("index", IntegerArgumentType.integer(0)).executes { context ->
                val index = IntegerArgumentType.getInteger(context, "index")
                if (!PvpPlayNetworking.adminReleaseArena(index)) return@executes fail(context.source, "$KEY.arena.in_use", index)
                context.source.sendSuccess({ Component.translatable("$KEY.arena.released", index) }, true)
                1
            })))
        .then(Commands.literal("lounge").then(Commands.literal("rescue")
            .then(Commands.argument("player", EntityArgument.player()).executes { context ->
                val player = EntityArgument.getPlayer(context, "player")
                val result = PvpPlayNetworking.adminRescue(player)
                val key = "$KEY.lounge.${result.name.lowercase()}"
                if (result != PvpPlayNetworking.AdminRescue.RETURNED && result != PvpPlayNetworking.AdminRescue.SPAWN) {
                    return@executes fail(context.source, key, player.name.string)
                }
                context.source.sendSuccess({ Component.translatable(key, player.name.string) }, true)
                1
            })))

    private fun fail(source: CommandSourceStack, key: String, vararg args: Any): Int {
        source.sendFailure(Component.translatable(key, *args))
        return 0
    }
}
