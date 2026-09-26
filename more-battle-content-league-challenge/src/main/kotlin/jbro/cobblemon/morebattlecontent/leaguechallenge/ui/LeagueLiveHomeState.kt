package jbro.cobblemon.morebattlecontent.leaguechallenge.ui

import jbro.cobblemon.morebattlecontent.leaguechallenge.network.*

/** Local selection and in-flight intent only. Progress always comes from the server. */
class LeagueLiveHomeState {
    var view: LeagueView? = null
        private set
    var selectedId: String? = null
        private set
    var pending = false
        private set

    fun accept(next: LeagueView?) {
        val newSession = next?.nonce != view?.nonce
        view = next
        pending = false
        if (newSession || next?.challenges?.none { it.id == selectedId } != false) {
            selectedId = next?.challenges?.firstOrNull { it.status == "AVAILABLE" }?.id
                ?: next?.challenges?.lastOrNull { it.status == "CLEARED" }?.id
                ?: next?.challenges?.firstOrNull()?.id
        }
    }

    fun select(id: String) {
        if (!pending && view?.challenges?.any { it.id == id } == true) selectedId = id
    }

    val canStart: Boolean get() = view?.let { !pending && it.runChallenge == null && !it.pendingRewards &&
        it.challenges.any { entry -> entry.id == selectedId && entry.status in setOf("AVAILABLE", "CLEARED") } } == true
    val canNext: Boolean get() = view?.let { !pending && it.runChallenge != null && it.awaitingNext && !it.pendingRewards } == true
    val canCancel: Boolean get() = !pending && view?.runChallenge != null

    fun begin(action: LeagueAction): Boolean {
        val allowed = when (action) {
            LeagueAction.START -> canStart
            LeagueAction.NEXT -> canNext
            LeagueAction.CANCEL -> canCancel
            LeagueAction.REFRESH -> view != null && !pending
        }
        if (allowed) pending = true
        return allowed
    }
}
