package jbro.cobblemon.mcc.league.server

import jbro.cobblemon.mcc.api.hub.MccDashboardCard
import jbro.cobblemon.mcc.api.hub.MccDashboardCards
import jbro.cobblemon.mcc.api.hub.MccDashboardRow
import jbro.cobblemon.mcc.api.hub.MccDashboardSections
import jbro.cobblemon.mcc.api.hub.MccDashboardStat
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.mcc.league.MoreCobblemonContentsLeagueChallenge as Mod
import jbro.cobblemon.mcc.league.system.LeagueEngine
import jbro.cobblemon.mcc.league.trainer.WildTrainers
import jbro.cobblemon.mcc.league.ui.LeagueRank
import net.minecraft.network.chat.Component

/**
 * The League's dashboard card: badges, level cap and rank, who is next, how far the hard route is, and the
 * wild trainer record.
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
            // The first stage not yet passed; a stage of two gyms to pick from names both.
            val nextStage = catalog.gymStages.firstOrNull { stage -> stage.none { it in state.cleared } }
            rows += MccDashboardRow(Component.translatable("$KEY.next"), when {
                nextStage != null -> nextStage.map { Component.translatable(catalog.challenges.getValue(it).nameKey) }
                    .reduce { names, name -> names.copy().append(" / ").append(name) }
                !state.champion -> Component.translatable("$KEY.elite_four")
                else -> Component.translatable("$KEY.champion")
            })
            if (catalog.hasHard) {
                val hardBadges = catalog.hardGymStages.count { stage -> stage.any { it in state.cleared } }
                rows += MccDashboardRow(Component.translatable("$KEY.hard"), when {
                    !engine.hardUnlocked(state) -> Component.translatable("$KEY.hard_locked")
                    state.hardChampion -> Component.translatable("$KEY.hard_champion")
                    else -> Component.translatable("$KEY.hard_progress", hardBadges, catalog.hardGymStages.size)
                })
            }
            // The other regions the player has opened, by their own progress.
            val states = LeagueProgressQueries.states(context.server, context.playerId)
            LeagueCatalogResources.catalogs.values.filter { it.id != catalog.id && jbro.cobblemon.mcc.league.system.LeagueWorldRules.unlocked(it, states) }
                .forEach { region ->
                    val progress = states[region.id] ?: LeagueSavedData.get(context.server).read(region.id, context.playerId)
                    val regionBadges = LeagueEngine(region).badgeCount(progress)
                    rows += MccDashboardRow(Component.translatable(region.nameKey),
                        if (progress.champion) Component.translatable("$KEY.champion")
                        else Component.translatable("$KEY.hard_progress", regionBadges, region.gymStages.size))
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
}
