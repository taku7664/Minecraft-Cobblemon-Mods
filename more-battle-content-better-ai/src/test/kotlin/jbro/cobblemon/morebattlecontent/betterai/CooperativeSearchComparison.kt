package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.betterai.search.LocalLookaheadEvaluation
import org.junit.jupiter.api.Assertions.*

/** Fixed-depth ranking comparison, not stochastic final choice, native strength or equal-time performance. */
internal object CooperativeSearchComparison {
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
