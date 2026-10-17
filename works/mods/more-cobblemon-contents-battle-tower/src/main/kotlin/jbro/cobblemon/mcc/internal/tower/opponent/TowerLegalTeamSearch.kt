package jbro.cobblemon.mcc.internal.tower.opponent

/** Mega Stones end in -ite (Garchompite, Charizardite X, Lucarionite Z); a Griseous Core is no Mega Stone. */
private val MEGA_STONE = Regex("^mega_showdown:[a-z_]+ite(_[xyz])?$")

internal fun String?.isTowerMegaStone(): Boolean = this != null && MEGA_STONE.matches(this)

internal object TowerLegalTeamSearch {
    fun exists(pool: List<TowerPokemonSet>, required: Int): Boolean = select(pool, required) != null

    /**
     * The first team in [pool] order with unique species and held items. [megaStones], when set, bounds how many
     * members hold a Mega Stone: only one Pokemon can Mega Evolve in a battle, so a second stone is a wasted item.
     */
    fun select(pool: List<TowerPokemonSet>, required: Int, megaStones: IntRange? = null): List<TowerPokemonSet>? {
        require(required > 0) { "Required team size must be positive" }
        val selected = ArrayList<TowerPokemonSet>(required)
        // stonesFrom[i]: Mega Stone sets at or after index i, so a branch that can no longer reach the minimum stops.
        val stonesFrom = IntArray(pool.size + 1)
        for (index in pool.lastIndex downTo 0) stonesFrom[index] = stonesFrom[index + 1] + if (pool[index].heldItemId.isTowerMegaStone()) 1 else 0
        return if (search(pool, required, megaStones, stonesFrom, 0, selected, HashSet(), HashSet())) {
            selected.toList()
        } else {
            null
        }
    }

    private fun search(
        pool: List<TowerPokemonSet>,
        required: Int,
        megaStones: IntRange?,
        stonesFrom: IntArray,
        startIndex: Int,
        selected: MutableList<TowerPokemonSet>,
        species: MutableSet<String>,
        heldItems: MutableSet<String>,
    ): Boolean {
        if (selected.size == required) return megaStones == null || selected.count { it.heldItemId.isTowerMegaStone() } in megaStones
        if (pool.size - startIndex < required - selected.size) return false
        val stones = if (megaStones == null) 0 else selected.count { it.heldItemId.isTowerMegaStone() }
        val missingStones = if (megaStones == null) 0 else megaStones.first - stones
        if (missingStones > minOf(stonesFrom[startIndex], required - selected.size)) return false

        for (index in startIndex until pool.size) {
            val candidate = pool[index]
            val heldItem = candidate.heldItemId
            if (candidate.speciesId in species || heldItem != null && heldItem in heldItems) continue
            if (megaStones != null) {
                val stone = heldItem.isTowerMegaStone()
                if (stone && stones >= megaStones.last) continue
                // Every seat left is needed for a stone.
                if (!stone && missingStones >= required - selected.size) continue
            }

            selected.add(candidate)
            species.add(candidate.speciesId)
            if (heldItem != null) heldItems.add(heldItem)

            if (search(pool, required, megaStones, stonesFrom, index + 1, selected, species, heldItems)) return true

            selected.removeAt(selected.lastIndex)
            species.remove(candidate.speciesId)
            if (heldItem != null) heldItems.remove(heldItem)
        }
        return false
    }
}
