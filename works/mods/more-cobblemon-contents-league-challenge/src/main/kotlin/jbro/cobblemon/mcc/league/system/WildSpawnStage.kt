package jbro.cobblemon.mcc.league.system

/**
 * Which stage of its evolution line a wild spawn takes at the level the League gave it, so a level 12 spawn is a
 * Charmander and not a Charizard, and a level 70 spawn is a Charizard and not a Charmander.
 */
object WildSpawnStage {
    /**
     * Steps back from the spawned species. [minimums] holds the lowest level of the spawned species and then of each
     * earlier stage that itself evolved from something, nearest first; a null means nothing says, and the line stops
     * stepping back there.
     */
    fun stepsBack(level: Int, minimums: List<Int?>): Int {
        var steps = 0
        while (steps < minimums.size) {
            val minimum = minimums[steps] ?: break
            if (level >= minimum) break
            steps++
        }
        return steps
    }

    /**
     * One evolution of the current stage. [minLevel] and [maxLevel] come from its level condition, with a null
     * [minLevel] for an evolution that has none (stone, trade, friendship). [blocked] when the Pokemon itself rules
     * it out (gender, nature, stats); [unmet] when only its surroundings or an owner would have to change (time of
     * day, biome, held item, party).
     */
    data class Branch<T>(val evolution: T, val minLevel: Int?, val maxLevel: Int, val blocked: Boolean, val unmet: Boolean)

    /**
     * The evolutions a wild spawn of [level] takes one step along: those its level condition allows and the Pokemon
     * itself does not rule out, keeping only the ones whose other conditions hold too when there are any.
     */
    fun <T> next(level: Int, branches: List<Branch<T>>): List<T> {
        val reached = branches.filter { it.minLevel != null && level in it.minLevel..it.maxLevel && !it.blocked }
        val met = reached.filterNot { it.unmet }
        return (met.ifEmpty { reached }).map { it.evolution }
    }
}
