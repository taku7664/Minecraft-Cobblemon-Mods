package jbro.cobblemon.mcc.internal.factory

import jbro.cobblemon.mcc.api.hub.MccDashboardCard
import jbro.cobblemon.mcc.api.hub.MccDashboardCards
import jbro.cobblemon.mcc.api.hub.MccDashboardRow
import jbro.cobblemon.mcc.api.hub.MccDashboardSections
import jbro.cobblemon.mcc.api.hub.MccDashboardStat
import net.minecraft.network.chat.Component

/** The Battle Factory's dashboard card: the best floor overall, and each format's current and best floor. */
internal object FactoryDashboard {
    private const val KEY = "screen.more_cobblemon_contents_battle_factory.dashboard"
    private const val CONTENT = FactoryRecordContract.CONTENT_ID
    private const val HIGHEST_FLOOR = "highest_floor"

    fun register() {
        MccDashboardSections.register(CONTENT) { context ->
            val records = context.records(CONTENT).sortedBy { it.formatId }
            fun best(record: jbro.cobblemon.mcc.internal.hub.BattleHubRecordView) = record.bestMetrics[HIGHEST_FLOOR] ?: record.bestStreak.toLong()
            MccDashboardCard(
                contentId = CONTENT,
                title = MccDashboardCards.contentName(CONTENT),
                stats = if (records.isEmpty()) emptyList() else listOf(
                    MccDashboardStat(Component.translatable("$KEY.best_floor"), Component.literal(records.maxOf(::best).toString())),
                    MccDashboardStat(Component.translatable("$KEY.wins"), Component.literal(records.sumOf { it.wins }.toString())),
                ),
                rows = records.map { record ->
                    MccDashboardRow(
                        MccDashboardCards.formatName(record.formatId),
                        Component.translatable("$KEY.floor", record.currentStreak),
                        Component.translatable("$KEY.detail", best(record), record.wins, record.losses),
                    )
                },
                note = if (records.isEmpty()) MccDashboardCards.noRecords() else null,
            )
        }
    }
}
