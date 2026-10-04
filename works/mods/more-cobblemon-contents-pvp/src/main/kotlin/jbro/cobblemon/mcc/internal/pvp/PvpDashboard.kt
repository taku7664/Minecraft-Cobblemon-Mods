package jbro.cobblemon.mcc.internal.pvp

import jbro.cobblemon.mcc.api.hub.MccDashboardCard
import jbro.cobblemon.mcc.api.hub.MccDashboardCards
import jbro.cobblemon.mcc.api.hub.MccDashboardRow
import jbro.cobblemon.mcc.api.hub.MccDashboardSections
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import net.minecraft.network.chat.Component

/** PvP's dashboard card: battles, wins and win rate, each format's record, and the last few battles. */
internal object PvpDashboard {
    private const val CONTENT = ManagedBattleContentIds.PVP
    private const val KEY = "screen.more_cobblemon_contents_pvp.dashboard"
    private const val RECENT = 3

    fun register() {
        MccDashboardSections.register(CONTENT) { context ->
            val records = context.records(CONTENT)
            val card = MccDashboardCards.records(CONTENT, records)
                ?: return@register MccDashboardCard(CONTENT, MccDashboardCards.contentName(CONTENT), note = MccDashboardCards.noRecords())
            val me = context.playerId
            val recent = runCatching { PvpMatchHistory.store?.matches(me, RECENT) }.getOrNull().orEmpty().map { match ->
                val won = match.winnerId == me
                MccDashboardRow(
                    Component.translatable(if (won) "$KEY.recent_win" else "$KEY.recent_loss", if (won) match.loserName else match.winnerName),
                    MccDashboardCards.formatName(match.format),
                )
            }
            card.copy(rows = (card.rows + recent).take(MccDashboardCard.MAX_ROWS))
        }
    }
}
