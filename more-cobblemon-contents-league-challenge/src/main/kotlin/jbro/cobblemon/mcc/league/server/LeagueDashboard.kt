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
            val state = LeagueSavedData.get(context.server).read(catalog.id, context.player.uuid)
            val engine = LeagueEngine(catalog)
            val badges = engine.badgeCount(state)
            val rank = LeagueRank.fromProgress(badges, state.champion && badges == catalog.gyms.size)
            val rows = ArrayList<MccDashboardRow>()
            val nextGym = catalog.gyms.firstOrNull { it !in state.cleared }?.let(catalog.challenges::get)
            rows += MccDashboardRow(Component.translatable("$KEY.next"), when {
                nextGym != null -> Component.translatable(nextGym.nameKey)
                !state.champion -> Component.translatable("$KEY.elite_four")
                else -> Component.translatable("$KEY.champion")
            })
            if (catalog.hasHard) {
                val hardBadges = catalog.hardGyms.count { it in state.cleared }
                rows += MccDashboardRow(Component.translatable("$KEY.hard"), when {
                    !engine.hardUnlocked(state) -> Component.translatable("$KEY.hard_locked")
                    state.hardChampion -> Component.translatable("$KEY.hard_champion")
                    else -> Component.translatable("$KEY.hard_progress", hardBadges, catalog.hardGyms.size)
                })
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
                    MccDashboardStat(Component.translatable("$KEY.badges"), Component.literal("$badges/${catalog.gyms.size}")),
                    MccDashboardStat(Component.translatable("$KEY.cap"), Component.literal(engine.cap(state).toString())),
                    MccDashboardStat(Component.translatable("$KEY.rank"),
                        Component.translatable("screen.${Mod.MOD_ID}.home.rank.${rank.name.lowercase()}")),
                ),
                rows = rows.take(MccDashboardCard.MAX_ROWS),
            )
        }
    }
}
