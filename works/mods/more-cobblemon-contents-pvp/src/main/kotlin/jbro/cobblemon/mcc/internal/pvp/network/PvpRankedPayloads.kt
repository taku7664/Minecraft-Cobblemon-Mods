package jbro.cobblemon.mcc.internal.pvp.network

import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.pvp.PvpBattleFormat
import jbro.cobblemon.mcc.internal.pvp.PvpRankedStanding
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

internal sealed interface PvpRankedIntent {
    data object Refresh : PvpRankedIntent
    data class Join(val format: PvpBattleFormat) : PvpRankedIntent
    data object Leave : PvpRankedIntent
}

/** One name and rating on a format's leaderboard. */
internal data class PvpRankedLeader(val rank: Int, val name: String, val rating: Int, val wins: Int, val losses: Int)

/**
 * What the ranked tab shows for the receiving player: their standing in each format, the queue they wait in and how
 * many wait in each format, and the leaders. [queuedForMillis] is how long they have waited when this was sent, so
 * the client counts on from its own clock.
 */
internal data class PvpRankedStatePayload(
    val standings: List<PvpRankedStanding>,
    val queuedFormat: PvpBattleFormat?,
    val queuedForMillis: Long,
    val queueSizes: Map<PvpBattleFormat, Int>,
    val leaders: Map<PvpBattleFormat, List<PvpRankedLeader>>,
    val messageKey: String? = null,
    val messageArgs: List<String> = emptyList(),
    /** Opens the hub on the ranked tab, as a finished ranked match does. */
    val reopen: Boolean = false,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<PvpRankedStatePayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<PvpRankedStatePayload>(rankedId("pvp_ranked_state"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, PvpRankedStatePayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeVarInt(payload.standings.size)
                payload.standings.forEach { standing ->
                    buffer.writeFormat(standing.format)
                    buffer.writeVarInt(standing.rating)
                    buffer.writeVarInt(standing.peak)
                    buffer.writeVarInt(standing.wins)
                    buffer.writeVarInt(standing.losses)
                    buffer.writeVarInt(standing.rank)
                    buffer.writeVarInt(standing.ladderSize)
                }
                buffer.writeBoolean(payload.queuedFormat != null)
                payload.queuedFormat?.let(buffer::writeFormat)
                buffer.writeVarLong(payload.queuedForMillis.coerceAtLeast(0))
                buffer.writeVarInt(payload.queueSizes.size)
                payload.queueSizes.forEach { (format, size) ->
                    buffer.writeFormat(format)
                    buffer.writeVarInt(size)
                }
                buffer.writeVarInt(payload.leaders.size)
                payload.leaders.forEach { (format, leaders) ->
                    buffer.writeFormat(format)
                    val shown = leaders.take(PVP_RANKED_MAX_LEADERS)
                    buffer.writeVarInt(shown.size)
                    shown.forEach { leader ->
                        buffer.writeVarInt(leader.rank)
                        buffer.writeUtf(leader.name.take(MAX_STRING_LENGTH), MAX_STRING_LENGTH)
                        buffer.writeVarInt(leader.rating)
                        buffer.writeVarInt(leader.wins)
                        buffer.writeVarInt(leader.losses)
                    }
                }
                buffer.writeBoolean(payload.messageKey != null)
                payload.messageKey?.let { buffer.writeUtf(it.take(MAX_STRING_LENGTH), MAX_STRING_LENGTH) }
                // Anything longer than the reader takes is cut here rather than failing the whole state.
                val args = payload.messageArgs.take(MAX_MESSAGE_ARGS)
                buffer.writeVarInt(args.size)
                args.forEach { buffer.writeUtf(it.take(MAX_STRING_LENGTH), MAX_STRING_LENGTH) }
                buffer.writeBoolean(payload.reopen)
            },
            { buffer ->
                val standings = List(buffer.readCount(FORMATS)) {
                    PvpRankedStanding(buffer.readFormat(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                        buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt())
                }
                val queued = if (buffer.readBoolean()) buffer.readFormat() else null
                val queuedFor = buffer.readVarLong()
                val sizes = buildMap { repeat(buffer.readCount(FORMATS)) { put(buffer.readFormat(), buffer.readVarInt()) } }
                val leaders = buildMap {
                    repeat(buffer.readCount(FORMATS)) {
                        val format = buffer.readFormat()
                        put(format, List(buffer.readCount(PVP_RANKED_MAX_LEADERS)) {
                            PvpRankedLeader(buffer.readVarInt(), buffer.readUtf(MAX_STRING_LENGTH), buffer.readVarInt(),
                                buffer.readVarInt(), buffer.readVarInt())
                        })
                    }
                }
                val messageKey = if (buffer.readBoolean()) buffer.readUtf(MAX_STRING_LENGTH) else null
                val messageArgs = List(buffer.readCount(MAX_MESSAGE_ARGS)) { buffer.readUtf(MAX_STRING_LENGTH) }
                PvpRankedStatePayload(standings, queued, queuedFor, sizes, leaders, messageKey, messageArgs, buffer.readBoolean())
            },
        )
    }
}

internal data class PvpRankedIntentPayload(val intent: PvpRankedIntent) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<PvpRankedIntentPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<PvpRankedIntentPayload>(rankedId("pvp_ranked_intent"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, PvpRankedIntentPayload> = StreamCodec.of(
            { buffer, payload ->
                when (val intent = payload.intent) {
                    PvpRankedIntent.Refresh -> buffer.writeVarInt(0)
                    is PvpRankedIntent.Join -> {
                        buffer.writeVarInt(1)
                        buffer.writeFormat(intent.format)
                    }
                    PvpRankedIntent.Leave -> buffer.writeVarInt(2)
                }
            },
            { buffer ->
                PvpRankedIntentPayload(when (val tag = buffer.readVarInt()) {
                    0 -> PvpRankedIntent.Refresh
                    1 -> PvpRankedIntent.Join(buffer.readFormat())
                    2 -> PvpRankedIntent.Leave
                    else -> throw IllegalArgumentException("Unknown PvP ranked intent: $tag")
                })
            },
        )
    }
}

/** Leaders listed per format. */
internal const val PVP_RANKED_MAX_LEADERS = 10

private val FORMATS = PvpBattleFormat.entries.size
private const val MAX_STRING_LENGTH = 256
private const val MAX_MESSAGE_ARGS = 4

private fun RegistryFriendlyByteBuf.writeFormat(format: PvpBattleFormat) = writeVarInt(format.ordinal)

private fun RegistryFriendlyByteBuf.readFormat(): PvpBattleFormat {
    val ordinal = readVarInt()
    return PvpBattleFormat.entries.getOrNull(ordinal) ?: throw IllegalArgumentException("Unknown PvP format: $ordinal")
}

private fun RegistryFriendlyByteBuf.readCount(maximum: Int): Int =
    readVarInt().also { require(it in 0..maximum) { "Invalid PvP ranked count: $it" } }

private fun rankedId(path: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(MoreCobblemonContents.MOD_ID, path)

