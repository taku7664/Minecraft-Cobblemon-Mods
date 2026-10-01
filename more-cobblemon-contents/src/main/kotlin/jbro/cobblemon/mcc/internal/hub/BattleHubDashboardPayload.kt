package jbro.cobblemon.mcc.internal.hub

import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.api.hub.MccDashboardCard
import jbro.cobblemon.mcc.api.hub.MccDashboardRow
import jbro.cobblemon.mcc.api.hub.MccDashboardStat
import jbro.cobblemon.mcc.internal.record.BattleRecordStats
import net.minecraft.network.chat.ComponentSerialization
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

/**
 * The viewer's dashboard, sent whenever the hub opens: totals over every record, and one card per content as
 * [jbro.cobblemon.mcc.api.hub.MccDashboardSections] built them.
 */
data class BattleHubDashboardPayload(val battles: Long, val wins: Long, val cards: List<MccDashboardCard>) : CustomPacketPayload {
    init {
        require(battles >= 0 && wins in 0..battles) { "Invalid dashboard totals" }
        require(cards.size <= MAX_CARDS) { "Too many dashboard cards" }
    }

    override fun type(): CustomPacketPayload.Type<BattleHubDashboardPayload> = TYPE

    companion object {
        const val MAX_RECORDS = 64
        const val MAX_CARDS = 16
        private const val MAX_ID_LENGTH = 128

        val TYPE = CustomPacketPayload.Type<BattleHubDashboardPayload>(
            ResourceLocation.fromNamespaceAndPath(MoreCobblemonContents.MOD_ID, "battle_hub_dashboard"),
        )
        private val TEXT = ComponentSerialization.TRUSTED_STREAM_CODEC

        val CODEC: StreamCodec<RegistryFriendlyByteBuf, BattleHubDashboardPayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeVarLong(payload.battles)
                buffer.writeVarLong(payload.wins)
                buffer.writeVarInt(payload.cards.size)
                payload.cards.forEach { card ->
                    buffer.writeUtf(card.contentId, MAX_ID_LENGTH)
                    TEXT.encode(buffer, card.title)
                    buffer.writeVarInt(card.stats.size)
                    card.stats.forEach { TEXT.encode(buffer, it.label); TEXT.encode(buffer, it.value) }
                    buffer.writeVarInt(card.rows.size)
                    card.rows.forEach { row ->
                        TEXT.encode(buffer, row.title)
                        TEXT.encode(buffer, row.value)
                        buffer.writeBoolean(row.detail != null)
                        row.detail?.let { TEXT.encode(buffer, it) }
                    }
                    buffer.writeBoolean(card.note != null)
                    card.note?.let { TEXT.encode(buffer, it) }
                }
            },
            { buffer ->
                val battles = buffer.readVarLong()
                val wins = buffer.readVarLong()
                val size = buffer.readVarInt().also { require(it in 0..MAX_CARDS) { "Invalid dashboard size" } }
                BattleHubDashboardPayload(battles, wins, List(size) {
                    val contentId = buffer.readUtf(MAX_ID_LENGTH)
                    val title = TEXT.decode(buffer)
                    val stats = List(buffer.readVarInt().also { require(it in 0..MccDashboardCard.MAX_STATS) { "Invalid stats" } }) {
                        MccDashboardStat(TEXT.decode(buffer), TEXT.decode(buffer))
                    }
                    val rows = List(buffer.readVarInt().also { require(it in 0..MccDashboardCard.MAX_ROWS) { "Invalid rows" } }) {
                        MccDashboardRow(TEXT.decode(buffer), TEXT.decode(buffer), if (buffer.readBoolean()) TEXT.decode(buffer) else null)
                    }
                    MccDashboardCard(contentId, title, stats, rows, if (buffer.readBoolean()) TEXT.decode(buffer) else null)
                })
            },
        )
    }
}
