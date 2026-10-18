package jbro.cobblemon.mcc.league.system

/**
 * How far back down its evolution line a wild spawn steps once the League has lowered its level, so a level 12
 * spawn is a Charmander and not a Charizard. A stage stays only when [level] reaches the lowest level it is met at.
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
}
