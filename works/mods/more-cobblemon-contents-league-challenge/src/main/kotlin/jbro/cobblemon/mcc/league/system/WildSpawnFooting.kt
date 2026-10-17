package jbro.cobblemon.mcc.league.system

/**
 * Whether a wild trainer's spawn spot looks like ground a person would stand on. Cobblemon's grounded spawning takes
 * any solid block with air above, so trainers turned up on tree trunks, pillars and one-block ledges.
 */
object WildSpawnFooting {
    /** How far above the ground around it a trainer's feet may stand. */
    const val MAX_RISE = 2
    /** Neighbours with a drop of two or more under them that make a spot a ledge or pillar. */
    const val MAX_HOLLOW_NEIGHBOURS = 1

    /**
     * [feetY] is the block the trainer stands in. [surroundingGround] is where feet would stand in each column around
     * the spot (the heightmap without leaves, two blocks out). [hollowNeighbours] counts the eight adjacent columns
     * with nothing to stand on at the trainer's level or one below. Null when the spot is fine, else the reason.
     */
    fun rejection(feetY: Int, surroundingGround: List<Int>, hollowNeighbours: Int): String? {
        if (hollowNeighbours > MAX_HOLLOW_NEIGHBOURS) return "hollow"
        if (surroundingGround.isNotEmpty()) {
            val sorted = surroundingGround.sorted()
            val median = sorted[sorted.size / 2]
            if (feetY - median > MAX_RISE) return "raised"
        }
        return null
    }
}
