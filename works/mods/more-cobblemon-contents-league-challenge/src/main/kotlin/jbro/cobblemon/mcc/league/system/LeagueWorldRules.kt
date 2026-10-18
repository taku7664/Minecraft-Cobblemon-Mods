package jbro.cobblemon.mcc.league.system

/** Cross-league entitlements; the primary league remains the owner of the hub rank and global gimmick unlock. */
object LeagueWorldRules {
    fun unlocked(catalog: LeagueCatalog, states: Map<String, LeagueProgress>): Boolean =
        catalog.unlockLeague?.let { states[it]?.champion == true } ?: true

    fun earnedCap(catalogs: Collection<LeagueCatalog>, states: Map<String, LeagueProgress>): Int =
        catalogs.filter { it.active && unlocked(it, states) }.maxOfOrNull { LeagueEngine(it).cap(states[it.id] ?: LeagueProgress()) } ?: 16

    fun requireCanChallenge(catalog: LeagueCatalog, states: Map<String, LeagueProgress>) {
        check(catalog.active) { "catalog_unavailable" }
        check(unlocked(catalog, states)) { "league_locked" }
        check(states.none { (id, progress) -> id != catalog.id && progress.run != null }) { "run_active" }
        check(states.values.none { progress -> progress.rewards.any { !it.badgeDone || !it.bpDone } }) { "rewards_pending" }
    }
}
