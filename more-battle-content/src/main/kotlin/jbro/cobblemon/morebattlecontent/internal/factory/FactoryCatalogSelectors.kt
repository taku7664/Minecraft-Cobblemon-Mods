package jbro.cobblemon.morebattlecontent.internal.factory

import java.util.Collections
import jbro.cobblemon.morebattlecontent.api.ai.BattleStrategyBrief
import jbro.cobblemon.morebattlecontent.api.ai.BattleTeamMemberPlan
import kotlin.random.Random

internal interface FactoryCatalogRandom {
    fun nextLong(bound: Long): Long
    fun nextInt(bound: Int): Int
}

private object DefaultFactoryCatalogRandom : FactoryCatalogRandom {
    override fun nextLong(bound: Long): Long = Random.Default.nextLong(bound)
    override fun nextInt(bound: Int): Int = Random.Default.nextInt(bound)
}

internal class FactoryDraftSelector(
    private val catalog: FactoryCatalog,
    private val random: FactoryCatalogRandom = DefaultFactoryCatalogRandom,
) {
    fun select(
        levelMode: FactoryLevelMode,
        round: Int,
        rentAndTradeCount: Int,
        previousSetIds: Set<String> = emptySet(),
    ): FactoryRentalDraft? {
        val currentWindow = FactoryProgression.poolWindow(levelMode, round)
        val strongerCount = FactoryProgression.strongerOfferCount(rentAndTradeCount)
        val nextWindow = FactoryProgression.poolWindow(levelMode, round + 1)
        val current = orderedCandidates(catalog.rentalPool(currentWindow), previousSetIds).map {
            DraftCandidate(it, FactoryProgression.uniformIvForRound(round))
        }
        val stronger = orderedCandidates(catalog.rentalPool(nextWindow), previousSetIds).map {
            DraftCandidate(it, FactoryProgression.uniformIvForRound(round + 1))
        }
        val groups = listOf(stronger to strongerCount, current to DRAFT_SIZE - strongerCount)
        val selected = selectWithOverlapLimit(groups, previousSetIds, MAX_PREVIOUS_OVERLAP)
            ?: selectWithOverlapLimit(groups, previousSetIds, DRAFT_SIZE)
            ?: return null
        return FactoryRentalDraft(
            selected.map { selection ->
                selection.candidate.template.materialize(selection.candidate.uniformIv, selection.heldItemId, random)
            },
        )
    }

    private fun selectWithOverlapLimit(
        groups: List<Pair<List<DraftCandidate>, Int>>,
        previousSetIds: Set<String>,
        maxPreviousOverlap: Int,
    ): List<DraftSelection>? {
        val selected = ArrayList<DraftSelection>(DRAFT_SIZE)
        val found = selectGroup(
            groups = groups,
            groupIndex = 0,
            candidateIndex = 0,
            selectedInGroup = 0,
            selected = selected,
            species = HashSet(),
            heldItems = HashSet(),
            previousSetIds = previousSetIds,
            previousOverlap = 0,
            maxPreviousOverlap = maxPreviousOverlap,
        )
        return selected.takeIf { found }
    }

    private fun selectGroup(
        groups: List<Pair<List<DraftCandidate>, Int>>,
        groupIndex: Int,
        candidateIndex: Int,
        selectedInGroup: Int,
        selected: MutableList<DraftSelection>,
        species: MutableSet<String>,
        heldItems: MutableSet<String>,
        previousSetIds: Set<String>,
        previousOverlap: Int,
        maxPreviousOverlap: Int,
    ): Boolean {
        if (groupIndex == groups.size) return true
        val (pool, required) = groups[groupIndex]
        if (selectedInGroup == required) {
            return selectGroup(
                groups,
                groupIndex + 1,
                0,
                0,
                selected,
                species,
                heldItems,
                previousSetIds,
                previousOverlap,
                maxPreviousOverlap,
            )
        }
        if (pool.size - candidateIndex < required - selectedInGroup) return false
        for (index in candidateIndex until pool.size) {
            val candidate = pool[index]
            val repeated = candidate.template.setId in previousSetIds
            if (candidate.template.speciesId in species) continue
            if (repeated && previousOverlap >= maxPreviousOverlap) continue
            for (item in shuffled(candidate.template.heldItemIds)) {
                if (item != null && item in heldItems) continue
                selected += DraftSelection(candidate, item)
                species += candidate.template.speciesId
                if (item != null) heldItems += item
                if (
                    selectGroup(
                        groups,
                        groupIndex,
                        index + 1,
                        selectedInGroup + 1,
                        selected,
                        species,
                        heldItems,
                        previousSetIds,
                        previousOverlap + if (repeated) 1 else 0,
                        maxPreviousOverlap,
                    )
                ) return true
                selected.removeAt(selected.lastIndex)
                species -= candidate.template.speciesId
                if (item != null) heldItems -= item
            }
        }
        return false
    }

    private fun <T> shuffled(values: List<T>): List<T> = values.toMutableList().also(::shuffle)

    private fun orderedCandidates(
        values: List<FactoryRentalTemplate>,
        previousSetIds: Set<String>,
    ): List<FactoryRentalTemplate> = shuffled(values).sortedBy { if (it.setId in previousSetIds) 1 else 0 }

    private fun <T> shuffle(values: MutableList<T>) {
        for (index in values.lastIndex downTo 1) Collections.swap(values, index, random.nextInt(index + 1))
    }

    private data class DraftCandidate(val template: FactoryRentalTemplate, val uniformIv: Int)
    private data class DraftSelection(val candidate: DraftCandidate, val heldItemId: String?)

    private companion object {
        const val DRAFT_SIZE = 6
        const val MAX_PREVIOUS_OVERLAP = 3
    }
}

internal sealed interface FactoryOpponentSelectionResult {
    data class Selected(
        val concept: FactoryTrainerConcept,
        val team: List<FactoryRentalSet>,
        val strategy: BattleStrategyBrief,
    ) : FactoryOpponentSelectionResult

    data object NoEligibleConcept : FactoryOpponentSelectionResult
}

internal class FactoryOpponentSelector(
    private val catalog: FactoryCatalog,
    private val random: FactoryCatalogRandom = DefaultFactoryCatalogRandom,
) {
    fun select(format: FactoryBattleFormat, levelMode: FactoryLevelMode, round: Int): FactoryOpponentSelectionResult {
        val window = FactoryProgression.poolWindow(levelMode, round)
        val eligible = catalog.conceptsFor(format).mapNotNull { concept ->
            FactoryConceptTeamSearch.select(catalog, concept, format, window, random)?.let { concept to it }
        }
        if (eligible.isEmpty()) return FactoryOpponentSelectionResult.NoEligibleConcept
        val selected = selectWeighted(eligible)
        val iv = FactoryProgression.uniformIvForRound(round)
        val team = selected.second.map { it.template.materialize(iv, it.heldItemId, random) }
        val strategyMembers = selected.second.zip(team).map { (selectedMember, rentalSet) ->
            BattleTeamMemberPlan(
                speciesId = selectedMember.template.speciesId,
                roles = selectedMember.plan.roles,
                tacticalSummary = selectedMember.plan.tacticalSummary,
                preferredMoveIds = selectedMember.plan.preferredMoveIds.intersect(rentalSet.moveIds.toSet()),
                leadPriority = selectedMember.plan.leadPriority,
                preservationPriority = selectedMember.plan.preservationPriority,
            )
        }
        return FactoryOpponentSelectionResult.Selected(
            concept = selected.first,
            team = Collections.unmodifiableList(ArrayList(team)),
            strategy = BattleStrategyBrief(
                strategyId = "cobblemon_more_battle_content:factory/${selected.first.conceptId}",
                displayNameKey = selected.first.displayNameKey,
                descriptionKey = selected.first.descriptionKey,
                aiSummary = selected.first.aiSummary,
                objectives = selected.first.objectives,
                members = strategyMembers,
            ),
        )
    }

    private fun selectWeighted(
        eligible: List<Pair<FactoryTrainerConcept, List<FactoryConceptTeamMember>>>,
    ): Pair<FactoryTrainerConcept, List<FactoryConceptTeamMember>> {
        val total = eligible.sumOf { it.first.weight.toLong() }
        val ticket = random.nextLong(total)
        var upperBound = 0L
        for (candidate in eligible) {
            upperBound += candidate.first.weight
            if (ticket < upperBound) return candidate
        }
        error("Factory concept selection exceeded its validated total")
    }
}
