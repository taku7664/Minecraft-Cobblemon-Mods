package jbro.cobblemon.mcc.league.server

import jbro.cobblemon.mcc.api.hub.MccDashboardCard
import jbro.cobblemon.mcc.api.hub.MccDashboardCards
import jbro.cobblemon.mcc.api.hub.MccDashboardRow
import jbro.cobblemon.mcc.api.hub.MccDashboardSections
import jbro.cobblemon.mcc.api.hub.MccDashboardStat
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.mcc.league.MoreCobblemonContentsLeagueChallenge as Mod
import jbro.cobblemon.mcc.league.system.LeagueCatalog
import jbro.cobblemon.mcc.league.system.LeagueEngine
import jbro.cobblemon.mcc.league.system.LeagueProgress
import jbro.cobblemon.mcc.league.system.LeagueWorldRules
import jbro.cobblemon.mcc.league.trainer.WildTrainers
import jbro.cobblemon.mcc.league.ui.LeagueRank
import net.minecraft.network.chat.Component

/**
 * The League's dashboard card: badges, level cap and rank, each region's normal and hard league, and the wild
 * trainer record.
 */
object LeagueDashboard {
    private const val KEY = "screen.${Mod.MOD_ID}.dashboard"
    private const val CONTENT = ManagedBattleContentIds.LEAGUE_CHALLENGE

    fun register() {
        MccDashboardSections.register(CONTENT) { context ->
            val catalog = LeagueCatalogResources.current ?: return@register MccDashboardCards.records(CONTENT, context.records(CONTENT))
            val state = LeagueSavedData.get(context.server).read(catalog.id, context.playerId)
            val engine = LeagueEngine(catalog)
            val badges = engine.badgeCount(state)
            val rank = LeagueRank.fromProgress(badges, state.champion && badges == catalog.gymStages.size)
            val rows = ArrayList<MccDashboardRow>()
            // One line per region, the primary first: its normal league, the hard league under it, or what opens it.
            val states = LeagueProgressQueries.states(context.server, context.playerId)
            val regions = (listOf(catalog) + LeagueCatalogResources.catalogs.values.filter { it.id != catalog.id })
                .sortedBy { region -> generateSequence(region.unlockLeague) { LeagueCatalogResources.find(it)?.unlockLeague }.count() }
            regions.forEach { region ->
                val progress = states[region.id] ?: LeagueSavedData.get(context.server).read(region.id, context.playerId)
                rows += regionRow(region, progress, LeagueWorldRules.unlocked(region, states))
            }
            val records = context.records(CONTENT)
            records.firstOrNull { it.formatId == WildTrainers.RECORD_FORMAT }?.let { wild ->
                rows += MccDashboardRow(Component.translatable("$KEY.wild_trainers"), MccDashboardCards.record(wild.wins, wild.losses),
                    Component.translatable("screen.more_cobblemon_contents.dashboard.row.streak", wild.currentStreak, wild.bestStreak))
            }
            rows += MccDashboardCards.recordRows(records.filter { it.formatId != WildTrainers.RECORD_FORMAT })
            MccDashboardCard(
                contentId = CONTENT,
                title = MccDashboardCards.contentName(CONTENT),
                stats = listOf(
                    MccDashboardStat(Component.translatable("$KEY.badges"), Component.literal("$badges/${catalog.gymStages.size}")),
                    MccDashboardStat(Component.translatable("$KEY.cap"), Component.literal((context.server.playerList.getPlayer(context.playerId)?.let { LeagueProgressQueries.cap(it) } ?: LeagueProgressQueries.earnedCap(context.server, context.playerId)).toString())),
                    MccDashboardStat(Component.translatable("$KEY.rank"),
                        Component.translatable("screen.${Mod.MOD_ID}.home.rank.${rank.name.lowercase()}")),
                ),
                rows = rows.take(MccDashboardCard.MAX_ROWS),
            )
        }
    }

    /** [region]'s normal league as the value, its hard league as the dim line under it. */
    private fun regionRow(region: LeagueCatalog, progress: LeagueProgress, unlocked: Boolean): MccDashboardRow {
        val name = Component.translatable(region.nameKey)
        if (!unlocked) {
            val prerequisite = region.unlockLeague?.let(LeagueCatalogResources::find)
            return MccDashboardRow(name, Component.translatable("$KEY.locked",
                prerequisite?.let { Component.translatable(it.nameKey) } ?: Component.empty()))
        }
        val engine = LeagueEngine(region)
        val normal = if (progress.champion) Component.translatable("$KEY.champion")
            else Component.translatable("$KEY.progress", engine.badgeCount(progress), region.gymStages.size)
        val hard = if (!region.hasHard) null else Component.translatable("$KEY.hard", when {
            !engine.hardUnlocked(progress) -> Component.translatable("$KEY.hard_locked")
            progress.hardChampion -> Component.translatable("$KEY.champion")
            else -> Component.translatable("$KEY.progress",
                region.hardGymStages.count { stage -> stage.any { it in progress.cleared } }, region.hardGymStages.size)
        })
        return MccDashboardRow(name, normal, hard)
    }
}
