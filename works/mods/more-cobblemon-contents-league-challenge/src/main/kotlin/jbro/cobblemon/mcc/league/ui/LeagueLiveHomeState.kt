package jbro.cobblemon.mcc.league.ui

import jbro.cobblemon.mcc.league.network.*

/** Local selection and in-flight intent only. Progress always comes from the server. */
class LeagueLiveHomeState {
    var view: LeagueView? = null
        private set
    var selectedId: String? = null
        private set
    var pending = false
        private set

    /** Whether the hard route is shown; only a view that unlocked it can show it, and a hard run always does. */
    var hard = false
        private set
    var finalChoiceId: String? = null
        private set
    val finalBranches: List<LeagueFinalBranchView> get() = view?.let { if (hard) it.hardFinalBranches else it.finalBranches }.orEmpty()

    /** The route shown: the normal one, or the hard one once the normal Champion opened it. */
    val challenges: List<LeagueChallengeView> get() = view?.let { if (hard) it.hardChallenges else it.challenges }.orEmpty()

    fun accept(next: LeagueView?) {
        val newSession = next?.nonce != view?.nonce || next?.leagueId != view?.leagueId
        view = next
        pending = false
        hard = when {
            next == null || !next.hardUnlocked -> false
            next.runChallenge != null -> next.runHard
            else -> hard
        }
        if (newSession || challenges.none { it.id == selectedId }) selectFirst()
        if (finalBranches.none { it.id == finalChoiceId }) finalChoiceId = finalBranches.firstOrNull()?.id
    }

    /** Shows the other route and focuses its next challenge; a run keeps its own route. */
    fun toggleHard(): Boolean {
        val current = view ?: return false
        if (pending || !current.hardUnlocked || current.runChallenge != null) return false
        hard = !hard
        selectFirst()
        finalChoiceId = finalBranches.firstOrNull()?.id
        return true
    }

    fun cycleFinalBranch(): Boolean {
        if (pending || view?.runChallenge != null || finalBranches.size < 2) return false
        val index = finalBranches.indexOfFirst { it.id == finalChoiceId }
        finalChoiceId = finalBranches[(index + 1) % finalBranches.size].id
        return true
    }

    private fun selectFirst() {
        selectedId = challenges.firstOrNull { it.status == "AVAILABLE" }?.id
            ?: challenges.lastOrNull { it.status == "CLEARED" }?.id
            ?: challenges.firstOrNull()?.id
    }

    fun select(id: String) {
        if (!pending && challenges.any { it.id == id }) selectedId = id
    }

    val canStart: Boolean get() = view?.let { !pending && it.leagueUnlocked && it.runChallenge == null && !it.pendingRewards &&
        challenges.any { entry -> entry.id == selectedId && entry.status in setOf("AVAILABLE", "CLEARED") } } == true
    val canNext: Boolean get() = view?.let { !pending && it.runChallenge != null && it.awaitingNext && !it.pendingRewards } == true
    val canCancel: Boolean get() = !pending && view?.runChallenge != null

    fun begin(action: LeagueAction): Boolean {
        val allowed = when (action) {
            LeagueAction.START -> canStart
            LeagueAction.NEXT -> canNext
            LeagueAction.CANCEL -> canCancel
            LeagueAction.REFRESH -> view != null && !pending
            LeagueAction.SELECT_LEAGUE -> view != null && !pending && view?.runChallenge == null
        }
        if (allowed) pending = true
        return allowed
    }
}
