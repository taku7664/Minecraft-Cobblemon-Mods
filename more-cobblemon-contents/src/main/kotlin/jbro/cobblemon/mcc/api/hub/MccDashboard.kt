package jbro.cobblemon.mcc.api.hub

import java.util.concurrent.ConcurrentHashMap
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.hub.BattleHubRecordView
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/** One headline number on a dashboard card, such as "배지 · 5/8". */
data class MccDashboardStat(val label: Component, val value: Component)

/** One line on a dashboard card: [title] on the left, [value] on the right, an optional dim [detail] below. */
data class MccDashboardRow(val title: Component, val value: Component, val detail: Component? = null)

/**
 * A content's card on the hub dashboard. [contentId] gives the card its icon and its place, both taken from that
 * content's hub tab on the client. [note] is a dim line at the bottom, for example why the content is locked.
 */
data class MccDashboardCard(
    val contentId: String,
    val title: Component,
    val stats: List<MccDashboardStat> = emptyList(),
    val rows: List<MccDashboardRow> = emptyList(),
    val note: Component? = null,
) {
    init {
        require(stats.size <= MAX_STATS) { "A dashboard card shows at most $MAX_STATS stats" }
        require(rows.size <= MAX_ROWS) { "A dashboard card shows at most $MAX_ROWS rows" }
    }

    companion object {
        const val MAX_STATS = 4
        const val MAX_ROWS = 16
    }
}

/**
 * What a dashboard section may read about the player whose cards are built: the one opening the hub, or anyone
 * asked about by name (the Discord bot's /전적), who may be offline. Built on the server thread.
 */
interface MccDashboardContext {
    val server: MinecraftServer
    val playerId: java.util.UUID

    /** The player when online; null for an offline player's cards, so read stored data by [playerId]. */
    val player: ServerPlayer?

    /** The player's records of [contentId], by format. */
    fun records(contentId: String): List<BattleHubRecordView>

    companion object {
        fun of(server: MinecraftServer, player: ServerPlayer, records: List<BattleHubRecordView>): MccDashboardContext =
            of(server, player.uuid, player, records)

        fun of(server: MinecraftServer, playerId: java.util.UUID, player: ServerPlayer?, records: List<BattleHubRecordView>): MccDashboardContext =
            object : MccDashboardContext {
                override val server = server
                override val playerId = playerId
                override val player = player
                override fun records(contentId: String) = records.filter { it.contentId == contentId }
            }
    }
}

/** The dashboard as data, for places other than the hub. */
object MccDashboard {
    /** Every card of [playerId], online or not, as the hub would build it. Call on the server thread. */
    fun cards(server: MinecraftServer, playerId: java.util.UUID): List<MccDashboardCard> {
        val records = jbro.cobblemon.mcc.internal.record.BattleRecordService.forPlayer(server, playerId)
            .sortedWith(compareBy({ it.key.category.contentId }, { it.key.category.formatId }))
            .map(BattleHubRecordView::from)
        return MccDashboardSections.build(MccDashboardContext.of(server, playerId, server.playerList.getPlayer(playerId), records), records)
    }
}

/** Builds one content's card for a player, or null to show none. */
fun interface MccDashboardSection {
    fun build(context: MccDashboardContext): MccDashboardCard?
}

/**
 * The hub dashboard's cards, one per content. A content registers a section for its own content ID and decides
 * what its card says; the core draws every card the same way. Any content with records but no section still gets
 * a card made by [MccDashboardCards.records], so nothing a player played goes missing.
 */
object MccDashboardSections {
    private val sections = ConcurrentHashMap<String, MccDashboardSection>()

    fun register(contentId: String, section: MccDashboardSection): AutoCloseable {
        sections[contentId] = section
        return AutoCloseable { sections.remove(contentId, section) }
    }

    /** Every card for this player: registered sections, then records of contents without one. */
    internal fun build(context: MccDashboardContext, records: List<BattleHubRecordView>): List<MccDashboardCard> {
        val registered = HashMap(sections)
        val cards = registered.entries.sortedBy { it.key }.mapNotNull { (contentId, section) ->
            try {
                section.build(context)
            } catch (failure: RuntimeException) {
                MoreCobblemonContents.LOGGER.warn("Dashboard section {} failed; showing its records instead", contentId, failure)
                MccDashboardCards.records(contentId, context.records(contentId))
            }
        }
        val covered = registered.keys
        val rest = records.map { it.contentId }.distinct().filter { it !in covered }.mapNotNull { contentId ->
            MccDashboardCards.records(contentId, context.records(contentId))
        }
        return cards + rest
    }
}

/** Ready-made card pieces, so a section only adds what is special about its content. */
object MccDashboardCards {
    private const val PREFIX = "screen.${MoreCobblemonContents.MOD_ID}.dashboard"

    /** The content's name as its hub tab shows it. */
    fun contentName(contentId: String): Component =
        Component.translatableWithFallback("screen.${MoreCobblemonContents.MOD_ID}.hub.tab.${contentId.substringAfter(':')}", contentId)

    /** A format's name, such as 싱글 or 더블 · Lv.50. */
    fun formatName(formatId: String): Component = Component.translatableWithFallback("$PREFIX.format.$formatId", formatId)

    fun record(wins: Long, losses: Long): Component = Component.translatable("$PREFIX.row.record", wins, losses)

    fun winRate(wins: Long, battles: Long): Component =
        if (battles == 0L) Component.literal("-") else Component.literal("${(wins * 100 + battles / 2) / battles}%")

    /** One row per format: its record on the right, streaks and best metrics below. */
    fun recordRows(records: List<BattleHubRecordView>): List<MccDashboardRow> = records.sortedBy { it.formatId }.map { record ->
        val detail = Component.translatable("$PREFIX.row.streak", record.currentStreak, record.bestStreak)
        record.bestMetrics.forEach { (id, value) ->
            detail.append(" · ").append(Component.translatableWithFallback("$PREFIX.metric.$id", "$id $value", value))
        }
        MccDashboardRow(formatName(record.formatId), record(record.wins, record.losses), detail)
    }

    /** The note of a card whose content the player has not played yet. */
    fun noRecords(): Component = Component.translatable("$PREFIX.no_records")

    /** Battles, wins and win rate over [records]. */
    fun totals(records: List<BattleHubRecordView>): List<MccDashboardStat> {
        val wins = records.sumOf { it.wins }
        val battles = records.sumOf { it.battles }
        return listOf(
            MccDashboardStat(Component.translatable("$PREFIX.summary.battles"), Component.literal(battles.toString())),
            MccDashboardStat(Component.translatable("$PREFIX.summary.wins"), Component.literal(wins.toString())),
            MccDashboardStat(Component.translatable("$PREFIX.summary.win_rate"), winRate(wins, battles)),
        )
    }

    /** The plain card a content without its own section gets: its totals and a row per format. */
    fun records(contentId: String, records: List<BattleHubRecordView>): MccDashboardCard? =
        if (records.isEmpty()) null else MccDashboardCard(contentId, contentName(contentId), totals(records), recordRows(records))
}
