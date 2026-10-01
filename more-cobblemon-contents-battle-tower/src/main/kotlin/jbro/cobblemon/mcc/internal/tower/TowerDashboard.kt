package jbro.cobblemon.mcc.internal.tower

import jbro.cobblemon.mcc.api.hub.MccDashboardCard
import jbro.cobblemon.mcc.api.hub.MccDashboardCards
import jbro.cobblemon.mcc.api.hub.MccDashboardRow
import jbro.cobblemon.mcc.api.hub.MccDashboardSections
import jbro.cobblemon.mcc.api.hub.MccDashboardStat
import net.minecraft.network.chat.Component

/** The Battle Tower's dashboard card: the best streak overall, and each format's current and best streak. */
internal object TowerDashboard {
    private const val KEY = "screen.more_cobblemon_contents_battle_tower.dashboard"
    private const val CONTENT = TowerRecordContract.CONTENT_ID

    fun register() {
        MccDashboardSections.register(CONTENT) { context ->
            val records = context.records(CONTENT).sortedBy { it.formatId }
            MccDashboardCard(
                contentId = CONTENT,
                title = MccDashboardCards.contentName(CONTENT),
                stats = if (records.isEmpty()) emptyList() else listOf(
                    MccDashboardStat(Component.translatable("$KEY.best_streak"), Component.literal(records.maxOf { it.bestStreak }.toString())),
                    MccDashboardStat(Component.translatable("$KEY.wins"), Component.literal(records.sumOf { it.wins }.toString())),
                ),
                rows = records.map { record ->
                    MccDashboardRow(
                        MccDashboardCards.formatName(record.formatId),
                        Component.translatable("$KEY.streak", record.currentStreak),
                        Component.translatable("$KEY.detail", record.bestStreak, record.wins, record.losses),
                    )
                },
                note = if (records.isEmpty()) MccDashboardCards.noRecords() else null,
            )
        }
    }
}
