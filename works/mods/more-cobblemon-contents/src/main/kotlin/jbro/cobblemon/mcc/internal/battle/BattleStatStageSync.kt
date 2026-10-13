package jbro.cobblemon.mcc.internal.battle

import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import com.cobblemon.mod.common.battles.BattleRegistry
import java.util.UUID
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173ShowdownBoosts
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.UUIDUtil
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/** Stat stages of [battleId]'s active Pokemon as Showdown holds them, by Pokemon UUID and Showdown stat id. */
internal data class BattleStatStagesPayload(
    val battleId: UUID,
    val stages: Map<UUID, Map<String, Int>>,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<BattleStatStagesPayload> = TYPE

    companion object {
        private const val MAX_POKEMON = 12
        private const val MAX_STATS = 8

        val TYPE = CustomPacketPayload.Type<BattleStatStagesPayload>(
            ResourceLocation.fromNamespaceAndPath(MoreCobblemonContents.MOD_ID, "battle_stat_stages")
        )
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, BattleStatStagesPayload> = StreamCodec.of(
            { buffer, payload ->
                UUIDUtil.STREAM_CODEC.encode(buffer, payload.battleId)
                buffer.writeVarInt(payload.stages.size)
                payload.stages.forEach { (pokemon, stats) ->
                    UUIDUtil.STREAM_CODEC.encode(buffer, pokemon)
                    buffer.writeVarInt(stats.size)
                    stats.forEach { (stat, stage) ->
                        buffer.writeUtf(stat)
                        buffer.writeVarInt(stage)
                    }
                }
            },
            { buffer ->
                val battleId = UUIDUtil.STREAM_CODEC.decode(buffer)
                val stages = (0 until buffer.readVarInt().coerceIn(0, MAX_POKEMON)).associate {
                    UUIDUtil.STREAM_CODEC.decode(buffer) to
                        (0 until buffer.readVarInt().coerceIn(0, MAX_STATS)).associate { buffer.readUtf(16) to buffer.readVarInt() }
                }
                BattleStatStagesPayload(battleId, stages)
            },
        )
    }
}

/**
 * Sends each player battle's stat stages, read from Showdown, once the battle has finished playing out what Showdown
 * reported: Cobblemon's dispatch queue is empty and the log has grown since the last send. By then every message of
 * the turn is on its way to the clients, so the reading lands after them and corrects whatever they missed.
 */
internal object BattleStatStageSync {
    private val sentAtLogSize = HashMap<UUID, Int>()

    fun registerServer() {
        PayloadTypeRegistry.playS2C().register(BattleStatStagesPayload.TYPE, BattleStatStagesPayload.CODEC)
        ServerTickEvents.END_SERVER_TICK.register(::tick)
    }

    private fun tick(server: MinecraftServer) {
        val battles = server.playerList.players
            .mapNotNullTo(LinkedHashSet()) { BattleRegistry.getBattleByParticipatingPlayer(it) }
        sentAtLogSize.keys.retainAll(battles.mapTo(HashSet()) { it.battleId })
        battles.forEach { sync(server, it) }
    }

    private fun sync(server: MinecraftServer, battle: PokemonBattle) {
        if (!battle.started || battle.ended || battle.dispatches.isNotEmpty()) return
        val logSize = battle.showdownMessages.size
        if (sentAtLogSize[battle.battleId] == logSize) return
        val stages = Cobblemon173ShowdownBoosts.read(battle.battleId) ?: return
        sentAtLogSize[battle.battleId] = logSize
        val payload = BattleStatStagesPayload(battle.battleId, stages)
        recipients(server, battle).forEach { player ->
            if (ServerPlayNetworking.canSend(player, BattleStatStagesPayload.TYPE)) ServerPlayNetworking.send(player, payload)
        }
    }

    private fun recipients(server: MinecraftServer, battle: PokemonBattle): Set<ServerPlayer> =
        (battle.players + battle.spectators.mapNotNull(server.playerList::getPlayer)).toSet()
}
