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
            val structured = route.any { it.stage > 0 }
            return LeagueHomePresentation(if (structured) route.filter { it.stage < 9 } else route.take(8),
                if (structured) route.filter { it.stage == 9 } else route.drop(8),
                view.runEntry ?: route.firstOrNull { it.id == focusedId })
        }
    }
}
