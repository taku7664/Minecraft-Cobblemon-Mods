package jbro.cobblemon.mcc.internal.pvp

import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Ranked battles' rating: Pokemon Showdown's ladder Elo (`server/ladders-local.ts`). Everyone starts at [START],
 * nobody falls below [FLOOR], and K shrinks as the rating climbs so the low end moves fast and the top settles.
 * Below 1200 a loss costs little and a win gains a lot, so new players climb off the floor quickly.
 */
internal object PvpElo {
    const val START = 1000.0
    const val FLOOR = 1000.0

    /** [own] after a game against [foe]; [score] is 1 for a win and 0 for a loss. */
    fun next(own: Double, foe: Double, score: Double): Double {
        var k = 50.0
        if (own < 1200) {
            if (score < 0.5) k = 10 + (own - 1000) * 40 / 200
            else if (score > 0.5) k = 90 - (own - 1000) * 40 / 200
        } else if (own > 1350 && own <= 1600) {
            k = 40.0
        } else {
            k = 32.0
        }
        val expected = 1 / (1 + Math.pow(10.0, (foe - own) / 400))
        return max(own + k * (score - expected), FLOOR)
    }
}

/** The battle rules a ranked match uses: the room defaults, gimmicks as a new room has them and both clauses on. */
internal object PvpRankedRules {
    val ENABLED_MECHANICS: Set<PvpBattleMechanic> = PvpRoomDefaults.ENABLED_MECHANICS
    const val SPECIES_CLAUSE = true
    const val ITEM_CLAUSE = true

    fun settings(format: PvpBattleFormat) = PvpRoomSettings(
        PvpRoomVisibility.PRIVATE, format, ENABLED_MECHANICS, speciesClause = SPECIES_CLAUSE, itemClause = ITEM_CLAUSE,
    )
}

/** One player's ladder entry in one format. */
internal data class PvpRankedRecord(
    val playerId: UUID,
    val format: PvpBattleFormat,
    val name: String,
    val rating: Double = PvpElo.START,
    val peak: Double = PvpElo.START,
    val wins: Int = 0,
    val losses: Int = 0,
) {
    val games: Int get() = wins + losses
}

/** Where a player stands in a format: their record and their place among everyone who has played it. */
internal data class PvpRankedStanding(
    val format: PvpBattleFormat,
    val rating: Int,
    val peak: Int,
    val wins: Int,
    val losses: Int,
    /** 1 for the top; 0 before the first game. */
    val rank: Int,
    /** Players with at least one game in this format. */
    val ladderSize: Int,
)

/** A settled ranked game: both records before and after. */
internal data class PvpRankedResult(
    val battleId: UUID,
    val winnerBefore: PvpRankedRecord,
    val winnerAfter: PvpRankedRecord,
    val loserBefore: PvpRankedRecord,
    val loserAfter: PvpRankedRecord,
)

/**
 * Every ranked record of a world, in memory. A battle is settled at most once, so a retried completion cannot move
 * a rating twice.
 */
internal class PvpRankedLadder {
    private val records = HashMap<Pair<UUID, PvpBattleFormat>, PvpRankedRecord>()
    private val settled = HashSet<UUID>()

    @Synchronized
    fun load(loaded: Iterable<PvpRankedRecord>, settledBattles: Iterable<UUID>) {
        records.clear()
        settled.clear()
        loaded.forEach { records[it.playerId to it.format] = it }
        settled += settledBattles
    }

    @Synchronized
    fun clear() {
        records.clear()
        settled.clear()
    }

    @Synchronized
    fun record(playerId: UUID, format: PvpBattleFormat, name: String = playerId.toString()): PvpRankedRecord =
        records[playerId to format] ?: PvpRankedRecord(playerId, format, name)

    @Synchronized
    fun rating(playerId: UUID, format: PvpBattleFormat): Double = records[playerId to format]?.rating ?: PvpElo.START

    @Synchronized
    fun standing(playerId: UUID, format: PvpBattleFormat): PvpRankedStanding {
        val own = record(playerId, format)
        val ladder = records.values.filter { it.format == format && it.games > 0 }
        // Equal shown ratings share a place.
        val shown = own.rating.roundToInt()
        val rank = if (own.games == 0) 0 else 1 + ladder.count { it.rating.roundToInt() > shown }
        return PvpRankedStanding(format, shown, own.peak.roundToInt(), own.wins, own.losses, rank, ladder.size)
    }

    /** The best [limit] players of [format] who have played it, highest first. */
    @Synchronized
    fun top(format: PvpBattleFormat, limit: Int): List<PvpRankedRecord> = records.values
        .filter { it.format == format && it.games > 0 }
        .sortedWith(compareByDescending<PvpRankedRecord> { it.rating }.thenByDescending { it.wins })
        .take(limit)

    /** Moves both ratings for [battleId]; null when that battle was settled already. */
    @Synchronized
    fun settle(
        battleId: UUID,
        format: PvpBattleFormat,
        winnerId: UUID,
        winnerName: String,
        loserId: UUID,
        loserName: String,
    ): PvpRankedResult? {
        if (!settled.add(battleId)) return null
        val winner = record(winnerId, format).copy(name = winnerName)
        val loser = record(loserId, format).copy(name = loserName)
        val winnerRating = PvpElo.next(winner.rating, loser.rating, 1.0)
        val loserRating = PvpElo.next(loser.rating, winner.rating, 0.0)
        val winnerAfter = winner.copy(rating = winnerRating, peak = max(winner.peak, winnerRating), wins = winner.wins + 1)
        val loserAfter = loser.copy(rating = loserRating, losses = loser.losses + 1)
        records[winnerId to format] = winnerAfter
        records[loserId to format] = loserAfter
        return PvpRankedResult(battleId, winner, winnerAfter, loser, loserAfter)
    }
}

/** A player waiting for a ranked opponent. */
internal data class PvpRankedEntry(
    val playerId: UUID,
    val format: PvpBattleFormat,
    val rating: Double,
    val joinedAtMillis: Long,
)

internal data class PvpRankedPairing(val first: PvpRankedEntry, val second: PvpRankedEntry)

/**
 * The ranked queue. Two waiting players of a format meet once their ratings are close enough: within
 * [BASE_WINDOW] at first, the window widening by [WINDOW_STEP] every [WINDOW_STEP_MILLIS] of the longer wait, and
 * gone after [OPEN_AFTER_MILLIS] so a quiet server still finds someone. The last opponent is skipped for a while,
 * so two players who just met do not meet again at once while someone else waits.
 */
internal class PvpRankedQueue {
    private val entries = LinkedHashMap<UUID, PvpRankedEntry>()
    private val lastOpponent = HashMap<UUID, UUID>()

    @Synchronized
    fun join(entry: PvpRankedEntry) {
        entries[entry.playerId] = entry
    }

    @Synchronized
    fun leave(playerId: UUID): PvpRankedEntry? = entries.remove(playerId)

    @Synchronized
    fun entryFor(playerId: UUID): PvpRankedEntry? = entries[playerId]

    @Synchronized
    fun entries(): List<PvpRankedEntry> = entries.values.toList()

    @Synchronized
    fun size(format: PvpBattleFormat): Int = entries.values.count { it.format == format }

    @Synchronized
    fun clear() {
        entries.clear()
        lastOpponent.clear()
    }

    /**
     * Takes every pair that may meet now out of the queue, longest waiters first. Players not [eligible] right now
     * (still in some other battle) keep their place and are passed over.
     */
    @Synchronized
    fun pair(nowMillis: Long, eligible: (UUID) -> Boolean = { true }): List<PvpRankedPairing> {
        val pairings = ArrayList<PvpRankedPairing>()
        PvpBattleFormat.entries.forEach { format ->
            val waiting = entries.values.filter { it.format == format && eligible(it.playerId) }
                .sortedBy { it.joinedAtMillis }.toMutableList()
            while (waiting.size >= 2) {
                val first = waiting.removeAt(0)
                val second = waiting
                    .filter { canMeet(first, it, nowMillis) }
                    .minByOrNull { abs(it.rating - first.rating) }
                    ?: continue
                waiting.remove(second)
                entries.remove(first.playerId)
                entries.remove(second.playerId)
                lastOpponent[first.playerId] = second.playerId
                lastOpponent[second.playerId] = first.playerId
                pairings += PvpRankedPairing(first, second)
            }
        }
        return pairings
    }

    private fun canMeet(first: PvpRankedEntry, second: PvpRankedEntry, nowMillis: Long): Boolean {
        val longerWait = nowMillis - minOf(first.joinedAtMillis, second.joinedAtMillis)
        val rematch = lastOpponent[first.playerId] == second.playerId
        if (rematch && longerWait < REMATCH_HOLD_MILLIS) return false
        return abs(first.rating - second.rating) <= window(longerWait)
    }

    companion object {
        const val BASE_WINDOW = 100.0
        const val WINDOW_STEP = 50.0
        const val WINDOW_STEP_MILLIS = 10_000L
        const val OPEN_AFTER_MILLIS = 60_000L
        const val REMATCH_HOLD_MILLIS = 30_000L

        fun window(waitMillis: Long): Double =
            if (waitMillis >= OPEN_AFTER_MILLIS) Double.MAX_VALUE
            else BASE_WINDOW + WINDOW_STEP * (waitMillis.coerceAtLeast(0) / WINDOW_STEP_MILLIS)
    }
}
