package jbro.cobblemon.mcc.internal.hub

import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.record.BattleRecordStats
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

/** One of the viewer's own record categories as the hub dashboard shows it. */
data class BattleHubRecordView(
    val contentId: String,
    val formatId: String,
    val wins: Long,
    val losses: Long,
    val currentStreak: Int,
    val bestStreak: Int,
    val bestMetrics: Map<String, Long> = emptyMap(),
) {
    init {
        require(wins >= 0 && losses >= 0 && currentStreak >= 0 && bestStreak >= currentStreak) { "Invalid record view" }
        require(bestMetrics.size <= MAX_METRICS && bestMetrics.values.all { it >= 0 }) { "Invalid record metrics" }
    }

    val battles: Long get() = wins + losses

    companion object {
        const val MAX_METRICS = 8

        fun from(stats: BattleRecordStats) = BattleHubRecordView(
            contentId = stats.key.category.contentId,
            formatId = stats.key.category.formatId,
            wins = stats.totalWins,
            losses = stats.totalLosses,
            currentStreak = stats.currentWinStreak,
            bestStreak = stats.bestWinStreak,
            bestMetrics = stats.bestMetrics.entries.take(MAX_METRICS).associate { (id, value) -> id.value to value },
        )
    }
}

/** The viewer's own records, sent whenever the hub opens. */
data class BattleHubDashboardPayload(val records: List<BattleHubRecordView>) : CustomPacketPayload {
    init {
        require(records.size <= MAX_RECORDS) { "Too many dashboard records" }
    }

    override fun type(): CustomPacketPayload.Type<BattleHubDashboardPayload> = TYPE

    companion object {
        const val MAX_RECORDS = 64
        private const val MAX_ID_LENGTH = 128

        val TYPE = CustomPacketPayload.Type<BattleHubDashboardPayload>(
            ResourceLocation.fromNamespaceAndPath(MoreCobblemonContents.MOD_ID, "battle_hub_dashboard"),
        )
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, BattleHubDashboardPayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeVarInt(payload.records.size)
                payload.records.forEach { record ->
                    buffer.writeUtf(record.contentId, MAX_ID_LENGTH)
                    buffer.writeUtf(record.formatId, MAX_ID_LENGTH)
                    buffer.writeVarLong(record.wins)
                    buffer.writeVarLong(record.losses)
                    buffer.writeVarInt(record.currentStreak)
                    buffer.writeVarInt(record.bestStreak)
                    buffer.writeVarInt(record.bestMetrics.size)
                    record.bestMetrics.forEach { (id, value) ->
                        buffer.writeUtf(id, MAX_ID_LENGTH)
                        buffer.writeVarLong(value)
                    }
                }
            },
            { buffer ->
                val size = buffer.readVarInt().also { require(it in 0..MAX_RECORDS) { "Invalid dashboard size" } }
                BattleHubDashboardPayload(
                    List(size) {
                        val contentId = buffer.readUtf(MAX_ID_LENGTH)
                        val formatId = buffer.readUtf(MAX_ID_LENGTH)
                        val wins = buffer.readVarLong()
                        val losses = buffer.readVarLong()
                        val current = buffer.readVarInt()
                        val best = buffer.readVarInt()
                        val metricCount = buffer.readVarInt()
                            .also { require(it in 0..BattleHubRecordView.MAX_METRICS) { "Invalid metric count" } }
                        val metrics = LinkedHashMap<String, Long>()
                        repeat(metricCount) { metrics[buffer.readUtf(MAX_ID_LENGTH)] = buffer.readVarLong() }
                        BattleHubRecordView(contentId, formatId, wins, losses, current, best, metrics)
                    },
                )
            },
        )
    }
}
