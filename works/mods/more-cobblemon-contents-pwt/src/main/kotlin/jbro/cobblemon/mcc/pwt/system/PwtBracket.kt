package jbro.cobblemon.mcc.pwt.system

import kotlin.random.Random

/**
 * One eight-entrant single-elimination tournament. [entrants] holds trainer IDs by bracket slot, with [PLAYER] where
 * the challenger stands; slots 0-3 are the left half and 4-7 the right. [winners] holds, per finished round, the slot
 * of each match's winner: four for the quarterfinals, two for the semifinals, one for the final.
 */
data class PwtBracket(val entrants: List<String>, val winners: List<List<Int>> = emptyList()) {
    init {
        require(entrants.size == SIZE && entrants.count { it == PLAYER } == 1) { "A bracket has eight entrants, one of them the player" }
        require(entrants.distinct().size == SIZE) { "Bracket entrants must be distinct" }
        require(winners.size <= ROUNDS) { "A bracket has three rounds" }
        winners.forEachIndexed { round, slots ->
            require(slots.size == SIZE shr (round + 1)) { "Round $round must have ${SIZE shr (round + 1)} winners" }
            val alive = alive(round)
            slots.forEachIndexed { match, slot -> require(slot == alive[match * 2] || slot == alive[match * 2 + 1]) { "Winner $slot did not play match $match" } }
        }
    }

    val playerSlot: Int get() = entrants.indexOf(PLAYER)

    /** The round being played: 0 quarterfinals, 1 semifinals, 2 final, 3 once the final is decided. */
    val round: Int get() = winners.size

    val playerAlive: Boolean get() = playerSlot in alive(round)

    val finished: Boolean get() = round == ROUNDS || !playerAlive

    val playerWon: Boolean get() = round == ROUNDS && winners.last().single() == playerSlot

    /** The slots still in the tournament at the start of [round], in bracket order. */
    fun alive(round: Int): List<Int> = if (round == 0) (0 until SIZE).toList() else winners[round - 1]

    /** The trainer the player meets in the current round, or null once the run is over. */
    fun opponentSlot(): Int? {
        if (finished) return null
        val alive = alive(round)
        val index = alive.indexOf(playerSlot)
        return alive[index xor 1]
    }

    /**
     * The current round decided: the player's match as [playerWon] says, every other match by [random]. A lost match
     * still settles its round, so the bracket shows who went on.
     */
    fun settle(playerWon: Boolean, random: Random): PwtBracket {
        check(!finished) { "The tournament is already over" }
        val alive = alive(round)
        val next = alive.chunked(2).map { (left, right) ->
            when (playerSlot) {
                left -> if (playerWon) left else right
                right -> if (playerWon) right else left
                else -> if (random.nextBoolean()) left else right
            }
        }
        return copy(winners = winners + listOf(next))
    }

    companion object {
        const val PLAYER = "player"
        const val SIZE = 8
        const val ROUNDS = 3

        /** Seven distinct trainers drawn from [roster], the player at a random slot among them. */
        fun draw(roster: Collection<String>, random: Random): PwtBracket {
            val pool = roster.distinct()
            require(PLAYER !in pool) { "\"$PLAYER\" is reserved for the challenger" }
            require(pool.size >= SIZE - 1) { "PWT needs at least ${SIZE - 1} trainers, found ${pool.size}" }
            val trainers = pool.shuffled(random).take(SIZE - 1).toMutableList()
            trainers.add(random.nextInt(SIZE), PLAYER)
            return PwtBracket(trainers)
        }
    }
}
