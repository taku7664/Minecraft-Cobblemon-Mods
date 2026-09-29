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
        fun from(view: LeagueView, selectedId: String?): LeagueHomePresentation {
            val gyms = view.challenges.take(8)
            val finals = view.challenges.drop(8)
            val focusedId = view.runChallenge ?: selectedId
            return LeagueHomePresentation(gyms, finals, view.challenges.firstOrNull { it.id == focusedId })
        }
    }
}
