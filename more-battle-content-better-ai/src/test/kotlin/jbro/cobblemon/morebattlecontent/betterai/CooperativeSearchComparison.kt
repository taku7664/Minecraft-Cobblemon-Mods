package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.betterai.search.LocalLookaheadEvaluation
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalActionMixingContext
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalWeightedActionSelector
import org.junit.jupiter.api.Assertions.*

/** Fixed-depth ranking comparison, not stochastic final choice, native strength or equal-time performance. */
internal object CooperativeSearchComparison {
    data class MixingObservation(val completePool: Boolean, val unsearchedDraws: Int,
        val observedUnsearchedMass: Double)

    fun verifyLeaderRecovery(label: String, original: LocalLookaheadEvaluation,
        repaired: LocalLookaheadEvaluation, wide: LocalLookaheadEvaluation): MixingObservation {
        assertEquals(0.0, verifyAndReport("revalidated-$label", repaired, wide), 1e-9)
        assertTrue(repaired.ranked.first().outcome.candidate.actionId in repaired.responseCoverageByAction,
            "$label leader must actually be searched")
        assertTrue(repaired.responseCoverageByAction.keys.containsAll(original.responseCoverageByAction.keys))
        return reportWeightedChoices(label, repaired, wide)
    }

    /** Real selector, synthetic balanced/no-memory context; observed seed counts are not independent battles. */
    private fun reportWeightedChoices(label: String, repaired: LocalLookaheadEvaluation,
        wide: LocalLookaheadEvaluation): MixingObservation {
        val selector = LocalWeightedActionSelector()
        val mixing = LocalActionMixingContext.balanced(0.5)
        val reference = wide.ranked.associateBy { it.outcome.candidate.actionId }
        val best = reference.values.maxOf { it.comparisonValue }
        val seen = linkedMapOf<String, Double>()
        var unsearchedDraws = 0
        var referenceLoss = 0.0
        var wideReferenceLoss = 0.0
        var shortlistSize = 0
        repeat(1_024) { seed ->
            val selected = selector.choose(repaired.ranked, seed.toLong(), mixing)
            val id = selected.rank.outcome.candidate.actionId
            assertTrue(selected.probability > 0.0 && selected.probability <= 1.0)
            seen.putIfAbsent(id, selected.probability)?.let { assertEquals(it, selected.probability, 0.0) }
            if (seed > 0) assertEquals(shortlistSize, selected.shortlistSize)
            shortlistSize = selected.shortlistSize
            if (id !in repaired.responseCoverageByAction) unsearchedDraws++
            referenceLoss += best - reference.getValue(id).comparisonValue
            val wideChoice = selector.choose(wide.ranked, seed.toLong(), mixing).rank.outcome.candidate.actionId
            wideReferenceLoss += best - reference.getValue(wideChoice).comparisonValue
        }
        val complete = seen.size == shortlistSize
        if (complete) assertEquals(1.0, seen.values.sum(), 1e-9)
        val observedUnsearchedMass = seen.filterKeys { it !in repaired.responseCoverageByAction }.values.sum()
        println("COOP_MIX case=$label seeds=1024 observed=${seen.size} shortlist=$shortlistSize " +
            "completePool=$complete unsearchedDraws=$unsearchedDraws observedUnsearchedMass=$observedUnsearchedMass " +
            "meanReferenceLoss=${referenceLoss / 1024} wideMeanReferenceLoss=${wideReferenceLoss / 1024}")
        return MixingObservation(complete, unsearchedDraws, observedUnsearchedMass)
    }

    fun verifyAndReport(label: String, narrow: LocalLookaheadEvaluation, wide: LocalLookaheadEvaluation): Double {
        assertFalse(narrow.truncated)
        assertFalse(wide.truncated)
        assertEquals(wide.depthCompleted, narrow.depthCompleted)
        val reference = wide.ranked.associateBy { it.outcome.candidate.actionId }
        assertEquals(wide.ranked.size, reference.size)
        assertEquals(reference.keys, wide.responseCoverageByAction.keys)
        for (rank in narrow.ranked.filter { it.outcome.candidate.actionId in narrow.responseCoverageByAction }) {
            val id = rank.outcome.candidate.actionId
            assertEquals(reference.getValue(id).comparisonValue, rank.comparisonValue, 1e-9,
                "$label shared candidate changed: $id")
        }
        val topRanked = narrow.ranked.first().outcome.candidate.actionId
        val bestScore = reference.values.maxOf { it.comparisonValue }
        val loss = bestScore - reference.getValue(topRanked).comparisonValue
        val bestRetained = reference.values.filter { kotlin.math.abs(it.comparisonValue - bestScore) <= 1e-9 }
            .any { it.outcome.candidate.actionId in narrow.responseCoverageByAction }
        println("COOP_QUALITY case=$label depth=${narrow.depthCompleted} " +
            "narrowWork=${narrow.nodesVisited} wideWork=${wide.nodesVisited} " +
            "bestScoreRetained=$bestRetained topRankedSearched=${topRanked in narrow.responseCoverageByAction} " +
            "referenceScoreLoss=$loss")
        return loss
    }
}
