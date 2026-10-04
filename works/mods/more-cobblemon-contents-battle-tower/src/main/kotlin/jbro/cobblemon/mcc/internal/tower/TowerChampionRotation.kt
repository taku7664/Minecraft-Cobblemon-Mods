package jbro.cobblemon.mcc.internal.tower

import java.util.UUID

/**
 * The Champions each challenger has met in the current round of the rotation: every Champion comes once before any
 * comes back, and the last of one round never opens the next, so no two boss battles in a row bring the same one.
 * The rotation carries on across runs; it lives in memory like the other recent-opponent histories.
 */
internal class TowerChampionRotation {
    private val rounds = HashMap<UUID, Round>()

    private class Round {
        val met = LinkedHashSet<String>()
        var last: String? = null
    }

    /** The Champions among [champions] to leave out of [playerId]'s next boss battle. */
    @Synchronized
    fun excluded(playerId: UUID, champions: Set<String>): Set<String> {
        val round = rounds[playerId] ?: return emptySet()
        val met = round.met.intersect(champions)
        return if (met.size >= champions.size) setOfNotNull(round.last) else met
    }

    @Synchronized
    fun record(playerId: UUID, champion: String, champions: Set<String>) {
        val round = rounds.getOrPut(playerId, ::Round)
        if (round.met.intersect(champions).size >= champions.size) round.met.clear()
        round.met += champion
        round.last = champion
    }

    @Synchronized
    fun clear() {
        rounds.clear()
    }
}
