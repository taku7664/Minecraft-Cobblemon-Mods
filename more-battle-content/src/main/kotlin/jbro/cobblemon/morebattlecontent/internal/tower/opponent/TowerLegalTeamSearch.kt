package jbro.cobblemon.morebattlecontent.internal.tower.opponent

internal object TowerLegalTeamSearch {
    fun exists(pool: List<TowerPokemonSet>, required: Int): Boolean = select(pool, required) != null

    fun select(pool: List<TowerPokemonSet>, required: Int): List<TowerPokemonSet>? {
        require(required > 0) { "Required team size must be positive" }
        val selected = ArrayList<TowerPokemonSet>(required)
        return if (search(pool, required, 0, selected, HashSet(), HashSet())) {
            selected.toList()
        } else {
            null
        }
    }

    private fun search(
        pool: List<TowerPokemonSet>,
        required: Int,
        startIndex: Int,
        selected: MutableList<TowerPokemonSet>,
        species: MutableSet<String>,
        heldItems: MutableSet<String>,
    ): Boolean {
        if (selected.size == required) return true
        if (pool.size - startIndex < required - selected.size) return false

        for (index in startIndex until pool.size) {
            val candidate = pool[index]
            val heldItem = candidate.heldItemId
            if (candidate.speciesId in species || heldItem != null && heldItem in heldItems) continue

            selected.add(candidate)
            species.add(candidate.speciesId)
            if (heldItem != null) heldItems.add(heldItem)

            if (search(pool, required, index + 1, selected, species, heldItems)) return true

            selected.removeAt(selected.lastIndex)
            species.remove(candidate.speciesId)
            if (heldItem != null) heldItems.remove(heldItem)
        }
        return false
    }
}
