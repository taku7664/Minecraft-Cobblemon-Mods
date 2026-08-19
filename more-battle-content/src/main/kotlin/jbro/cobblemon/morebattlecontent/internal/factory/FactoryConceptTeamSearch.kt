package jbro.cobblemon.morebattlecontent.internal.factory

import java.util.Collections

internal data class FactoryConceptTeamMember(
    val plan: FactoryConceptMemberPlan,
    val template: FactoryRentalTemplate,
    val heldItemId: String?,
)

internal object FactoryConceptTeamSearch {
    fun select(
        catalog: FactoryCatalog,
        concept: FactoryTrainerConcept,
        format: FactoryBattleFormat,
        window: FactoryPoolWindow? = null,
        random: FactoryCatalogRandom? = null,
    ): List<FactoryConceptTeamMember>? {
        require(format in concept.formats) { "Factory concept does not support the requested format" }
        val required = concept.members.filter(FactoryConceptMemberPlan::required)
        if (required.size > format.selectionSize) return null
        val optional = concept.members.filterNot(FactoryConceptMemberPlan::required)
        val optionalCount = format.selectionSize - required.size
        for (optionalSelection in randomized(combinations(optional, optionalCount), random)) {
            val selectedPlans = required + optionalSelection
            val selected = ArrayList<FactoryConceptTeamMember>(format.selectionSize)
            if (selectTemplates(catalog, selectedPlans, window, random, 0, selected, HashSet(), HashSet())) {
                return selected.toList()
            }
        }
        return null
    }

    private fun selectTemplates(
        catalog: FactoryCatalog,
        plans: List<FactoryConceptMemberPlan>,
        window: FactoryPoolWindow?,
        random: FactoryCatalogRandom?,
        index: Int,
        selected: MutableList<FactoryConceptTeamMember>,
        species: MutableSet<String>,
        heldItems: MutableSet<String>,
    ): Boolean {
        if (index == plans.size) return true
        val plan = plans[index]
        val candidates = randomized(catalog.setsFor(plan).filter { window == null || it.belongsTo(window) }, random)
        for (candidate in candidates) {
            if (candidate.speciesId in species) continue
            for (item in randomized(candidate.heldItemIds, random)) {
                if (item != null && item in heldItems) continue
                selected.add(FactoryConceptTeamMember(plan, candidate, item))
                species.add(candidate.speciesId)
                if (item != null) heldItems.add(item)
                if (selectTemplates(catalog, plans, window, random, index + 1, selected, species, heldItems)) return true
                selected.removeAt(selected.lastIndex)
                species.remove(candidate.speciesId)
                if (item != null) heldItems.remove(item)
            }
        }
        return false
    }

    private fun <T> combinations(values: List<T>, size: Int): List<List<T>> {
        if (size == 0) return listOf(emptyList())
        if (size > values.size) return emptyList()
        val result = ArrayList<List<T>>()
        fun collect(start: Int, selected: MutableList<T>) {
            if (selected.size == size) {
                result += selected.toList()
                return
            }
            for (index in start until values.size) {
                selected += values[index]
                collect(index + 1, selected)
                selected.removeAt(selected.lastIndex)
            }
        }
        collect(0, ArrayList(size))
        return result
    }

    private fun <T> randomized(values: List<T>, random: FactoryCatalogRandom?): List<T> {
        if (random == null || values.size < 2) return values
        return values.toMutableList().also { shuffled ->
            for (index in shuffled.lastIndex downTo 1) {
                Collections.swap(shuffled, index, random.nextInt(index + 1))
            }
        }
    }
}
