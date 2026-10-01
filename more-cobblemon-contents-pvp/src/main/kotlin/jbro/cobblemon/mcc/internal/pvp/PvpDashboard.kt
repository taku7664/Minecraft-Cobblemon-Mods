package jbro.cobblemon.mcc.internal.pvp

import jbro.cobblemon.mcc.api.hub.MccDashboardCard
import jbro.cobblemon.mcc.api.hub.MccDashboardCards
import jbro.cobblemon.mcc.api.hub.MccDashboardSections
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds

/** PvP's dashboard card: battles, wins and win rate, and each format's record. */
internal object PvpDashboard {
    private const val CONTENT = ManagedBattleContentIds.PVP

    fun register() {
        MccDashboardSections.register(CONTENT) { context ->
            val records = context.records(CONTENT)
            MccDashboardCards.records(CONTENT, records)
                ?: MccDashboardCard(CONTENT, MccDashboardCards.contentName(CONTENT), note = MccDashboardCards.noRecords())
        }
    }
}
