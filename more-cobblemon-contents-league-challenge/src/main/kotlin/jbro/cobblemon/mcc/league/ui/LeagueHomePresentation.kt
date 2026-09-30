package jbro.cobblemon.mcc.league.ui

import jbro.cobblemon.mcc.league.network.LeagueChallengeView
import jbro.cobblemon.mcc.league.network.LeagueView

/** Presentation grouping only; every status and unlock value stays server-authored. */
internal data class LeagueHomePresentation(
    val gyms: List<LeagueChallengeView>,
    val finals: List<LeagueChallengeView>,
    val focused: LeagueChallengeView?
) {
    companion object {
        fun from(view: LeagueView, selectedId: String?, hard: Boolean = false): LeagueHomePresentation {
            val route = if (hard) view.hardChallenges else view.challenges
            val focusedId = view.runChallenge ?: selectedId
            return LeagueHomePresentation(route.take(8), route.drop(8), route.firstOrNull { it.id == focusedId })
        }
    }
}
