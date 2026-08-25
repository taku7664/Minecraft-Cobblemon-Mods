package jbro.cobblemon.morebattlecontent.internal.factory

import java.util.Collections
import jbro.cobblemon.morebattlecontent.api.ai.BattleStrategyBrief
import jbro.cobblemon.morebattlecontent.api.ai.BattleTeamMemberPlan
import jbro.cobblemon.morebattlecontent.api.ai.BattleTeamRole
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
        recentSpeciesIds: Set<String> = emptySet(),
    ): FactoryRentalDraft? {
        val currentWindow = FactoryProgression.playerPoolWindow(levelMode, round)
        val strongerCount = FactoryProgression.strongerOfferCount(rentAndTradeCount)
        val nextWindow = FactoryProgression.playerPoolWindow(levelMode, round + 1)
        val current = orderedCandidates(catalog.rentalPool(currentWindow), recentSpeciesIds).map {
            DraftCandidate(it, FactoryProgression.uniformIvForRound(round))
        }
        val stronger = orderedCandidates(catalog.rentalPool(nextWindow), recentSpeciesIds).map {
            DraftCandidate(it, FactoryProgression.uniformIvForRound(round + 1))
        }
        val groups = listOf(stronger to strongerCount, current to DRAFT_SIZE - strongerCount)
        val selected = selectWithOverlapLimit(groups, recentSpeciesIds, NO_PREVIOUS_OVERLAP)
            ?: selectWithOverlapLimit(groups, recentSpeciesIds, MAX_FALLBACK_PREVIOUS_OVERLAP)
            ?: selectWithOverlapLimit(groups, recentSpeciesIds, DRAFT_SIZE)
            ?: return null
        return FactoryRentalDraft(
            selected.map { candidate ->
                candidate.template.materialize(candidate.uniformIv)
            },
        )
    }

    private fun selectWithOverlapLimit(
        groups: List<Pair<List<DraftCandidate>, Int>>,
        recentSpeciesIds: Set<String>,
        maxPreviousOverlap: Int,
    ): List<DraftCandidate>? {
        val selected = ArrayList<DraftCandidate>(DRAFT_SIZE)
        val found = selectGroup(
            groups = groups,
            groupIndex = 0,
            candidateIndex = 0,
            selectedInGroup = 0,
            selected = selected,
            species = HashSet(),
            recentSpeciesIds = recentSpeciesIds,
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
        selected: MutableList<DraftCandidate>,
        species: MutableSet<String>,
        recentSpeciesIds: Set<String>,
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
                recentSpeciesIds,
                previousOverlap,
                maxPreviousOverlap,
            )
        }
        if (pool.size - candidateIndex < required - selectedInGroup) return false
        for (index in candidateIndex until pool.size) {
            val candidate = pool[index]
            val repeated = candidate.template.speciesId in recentSpeciesIds
            if (candidate.template.speciesId in species) continue
            if (repeated && previousOverlap >= maxPreviousOverlap) continue
            selected += candidate
            species += candidate.template.speciesId
            if (
                selectGroup(
                    groups,
                    groupIndex,
                    index + 1,
                    selectedInGroup + 1,
                    selected,
                    species,
                    recentSpeciesIds,
                    previousOverlap + if (repeated) 1 else 0,
                    maxPreviousOverlap,
                )
            ) return true
            selected.removeAt(selected.lastIndex)
            species -= candidate.template.speciesId
        }
        return false
    }

    private fun <T> shuffled(values: List<T>): List<T> = values.toMutableList().also(::shuffle)

    private fun orderedCandidates(
        values: List<FactoryRentalTemplate>,
        recentSpeciesIds: Set<String>,
    ): List<FactoryRentalTemplate> = shuffled(values).sortedBy { if (it.speciesId in recentSpeciesIds) 1 else 0 }

    private fun <T> shuffle(values: MutableList<T>) {
        for (index in values.lastIndex downTo 1) Collections.swap(values, index, random.nextInt(index + 1))
    }

    private data class DraftCandidate(val template: FactoryRentalTemplate, val uniformIv: Int)
    private companion object {
        const val DRAFT_SIZE = 6
        const val NO_PREVIOUS_OVERLAP = 0
        const val MAX_FALLBACK_PREVIOUS_OVERLAP = 3
    }
}

internal sealed interface FactoryOpponentSelectionResult {
    data class Selected(
        val trainer: FactoryTrainerProfile,
        val team: List<FactoryRentalSet>,
        val strategy: BattleStrategyBrief,
    ) : FactoryOpponentSelectionResult

    data object NoEligibleTrainer : FactoryOpponentSelectionResult
}

internal class FactoryOpponentSelector(
    private val catalog: FactoryCatalog,
    private val random: FactoryCatalogRandom = DefaultFactoryCatalogRandom,
) {
    fun select(
        format: FactoryBattleFormat,
        levelMode: FactoryLevelMode,
        round: Int,
        excludedTrainerIds: Set<String> = emptySet(),
    ): FactoryOpponentSelectionResult {
        val window = FactoryProgression.opponentPoolWindow(levelMode, round)
        val templates = selectTeam(catalog.rentalPool(window), format.selectionSize)
            ?: return FactoryOpponentSelectionResult.NoEligibleTrainer
        val eligible = catalog.trainersFor(format)
        if (eligible.isEmpty()) return FactoryOpponentSelectionResult.NoEligibleTrainer
        val fresh = eligible.filterNot { it.trainerId in excludedTrainerIds }
        val trainer = selectWeighted(fresh.ifEmpty { eligible })
        val iv = FactoryProgression.uniformIvForRound(round)
        val team = templates.map { it.materialize(iv) }
        val strategyMembers = templates.zip(team).mapIndexed { index, (template, rentalSet) ->
            val roles = if (index == 0) template.roles + BattleTeamRole.ACE else template.roles - BattleTeamRole.ACE
            BattleTeamMemberPlan(
                speciesId = template.speciesId,
                roles = roles.ifEmpty { setOf(BattleTeamRole.WEAKNESS_COVER) },
                tacticalSummary = "Use this fixed rental preset according to its public moves and team role.",
                preferredMoveIds = template.preferredMoveIds.intersect(rentalSet.moveIds.toSet()),
                leadPriority = template.leadPriority,
                preservationPriority = template.preservationPriority,
            )
        }
        return FactoryOpponentSelectionResult.Selected(
            trainer = trainer,
            team = Collections.unmodifiableList(ArrayList(team)),
            strategy = BattleStrategyBrief(
                strategyId = "cobblemon_more_battle_content:factory/${trainer.trainerId}",
                displayNameKey = trainer.displayNameKey,
                descriptionKey = trainer.descriptionKey,
                aiSummary = trainer.aiSummary,
                objectives = trainer.objectives,
                members = strategyMembers,
            ),
        )
    }

    private fun selectTeam(candidates: List<FactoryRentalTemplate>, size: Int): List<FactoryRentalTemplate>? {
        val ordered = candidates.toMutableList().also(::shuffle)
        val selected = ArrayList<FactoryRentalTemplate>(size)
        return selected.takeIf { selectTeam(ordered, size, 0, selected, HashSet()) }
    }

    private fun selectTeam(
        candidates: List<FactoryRentalTemplate>,
        required: Int,
        startIndex: Int,
        selected: MutableList<FactoryRentalTemplate>,
        species: MutableSet<String>,
    ): Boolean {
        if (selected.size == required) return true
        if (candidates.size - startIndex < required - selected.size) return false
        for (index in startIndex until candidates.size) {
            val candidate = candidates[index]
            if (candidate.speciesId in species) continue
            selected += candidate
            species += candidate.speciesId
            if (selectTeam(candidates, required, index + 1, selected, species)) return true
            selected.removeAt(selected.lastIndex)
            species -= candidate.speciesId
        }
        return false
    }

    private fun selectWeighted(eligible: List<FactoryTrainerProfile>): FactoryTrainerProfile {
        val total = eligible.sumOf { it.weight.toLong() }
        val ticket = random.nextLong(total)
        var upperBound = 0L
        for (candidate in eligible) {
            upperBound += candidate.weight
            if (ticket < upperBound) return candidate
        }
        error("Factory trainer selection exceeded its validated total")
    }

    private fun <T> shuffle(values: MutableList<T>) {
        for (index in values.lastIndex downTo 1) Collections.swap(values, index, random.nextInt(index + 1))
    }
}
