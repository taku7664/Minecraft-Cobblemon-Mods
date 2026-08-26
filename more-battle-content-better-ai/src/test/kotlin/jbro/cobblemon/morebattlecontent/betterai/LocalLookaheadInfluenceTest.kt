package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleDifficultyProfile
import jbro.cobblemon.morebattlecontent.api.ai.BattleDifficultyProfiles
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Asks why a deeper search does not change the decision.
 *
 * The tier measurement established *that* Advanced and Boss decide almost identically. It could not
 * say why, and there are two very different explanations with the same symptom.
 *
 * Either the extra depth genuinely produces no new conclusion - in a game where most of the value
 * lands on the first turn, that would be an honest property of the domain and no weighting could fix
 * it - or the depth does reach a different conclusion and the ranking is burying it. The final
 * comparison is `heuristic + searchDelta`, where the heuristic already prices the immediate turn and
 * the search prices the immediate turn again as part of its own total. Two correlated terms added
 * together mostly preserve the order of the larger one.
 *
 * Ranking each position three ways separates the cases. If the search-only ranking moves with depth
 * while the production ranking does not, the lever works and the weighting is what is broken.
 */
class LocalLookaheadInfluenceTest {
    @Test
    fun `the search is measured for whether depth changes its own conclusion`() {
        val contexts = recordPositions()
        val tiers = listOf(
            "introductory" to BattleDifficultyProfiles.INTRODUCTORY,
            "standard" to BattleDifficultyProfiles.STANDARD,
            "advanced" to BattleDifficultyProfiles.ADVANCED,
            "boss" to BattleDifficultyProfiles.BOSS,
        )
        val readingsByTier = tiers.associate { (name, profile) ->
            name to contexts.map { context -> read(context, profile) }
        }

        val report = buildString {
            appendLine("=".repeat(104))
            appendLine("LOOKAHEAD INFLUENCE  positions=${contexts.size}")
            appendLine("=".repeat(104))
            appendLine("Three rankings of the same position:")
            appendLine("  heuristic  - the immediate-turn score alone, before any search")
            appendLine("  search     - the search delta alone, ignoring the heuristic")
            appendLine("  production - heuristic + search delta, what actually ships")
            appendLine()
            appendLine("%-14s %-24s %-24s %s".format("", "search vs heuristic", "production vs heuristic", "magnitude"))
            tiers.forEach { (name, _) ->
                val readings = readingsByTier.getValue(name)
                val searchMoved = readings.count { it.searchBest != it.heuristicBest }
                val productionMoved = readings.count { it.productionBest != it.heuristicBest }
                val meanSwing = readings.map { abs(it.topSwing) }.average()
                val meanSpread = readings.map { it.heuristicSpread }.average()
                appendLine(
                    "%-14s %-24s %-24s swing=%6.1f  heuristic gap=%6.1f".format(
                        name,
                        "%5.1f%% (%d/%d)".format(searchMoved * 100.0 / contexts.size, searchMoved, contexts.size),
                        "%5.1f%% (%d/%d)".format(
                            productionMoved * 100.0 / contexts.size, productionMoved, contexts.size,
                        ),
                        meanSwing, meanSpread,
                    ),
                )
            }
            appendLine()
            appendLine("-- does depth change what the SEARCH concludes --")
            appendLine("Compared against introductory, which searches a single ply.")
            val baseline = readingsByTier.getValue("introductory")
            tiers.drop(1).forEach { (name, _) ->
                val readings = readingsByTier.getValue(name)
                val searchDiffers = readings.indices.count { readings[it].searchBest != baseline[it].searchBest }
                val productionDiffers =
                    readings.indices.count { readings[it].productionBest != baseline[it].productionBest }
                appendLine(
                    "  %-14s search-only differs=%5.1f%% (%d/%d)   production differs=%5.1f%% (%d/%d)".format(
                        name,
                        searchDiffers * 100.0 / contexts.size, searchDiffers, contexts.size,
                        productionDiffers * 100.0 / contexts.size, productionDiffers, contexts.size,
                    ),
                )
            }
            appendLine()
            appendLine("If search-only moves with depth while production does not, the depth is being")
            appendLine("computed and then outvoted. If neither moves, the depth genuinely finds nothing")
            appendLine("new and no reweighting would help.")
        }
        println(report)

        assertTrue(contexts.isNotEmpty(), report)
        assertTrue(readingsByTier.values.all { it.size == contexts.size }, report)
    }

    private data class Reading(
        val heuristicBest: String,
        val searchBest: String,
        val productionBest: String,
        /** Search delta carried by the production winner. */
        val topSwing: Double,
        /** Distance between the top two heuristic scores - what a search delta has to cross. */
        val heuristicSpread: Double,
    )

    private fun read(context: BattleDecisionContext, difficulty: BattleDifficultyProfile): Reading {
        val breakdown = LocalDecisionInstrumentation.inspect(
            context = context,
            profile = BattleTrainerProfile(
                skillLevel = 2,
                personality = BattleTrainerProfile.champion().personality,
                difficulty = difficulty,
            ),
        )
        val candidates = breakdown.candidates
        val byHeuristic = candidates.sortedByDescending { it.heuristicOnlyValue }
        val production = candidates.maxByOrNull { it.comparisonValue }
        return Reading(
            heuristicBest = byHeuristic.firstOrNull()?.actionId ?: "none",
            searchBest = candidates.maxByOrNull { it.lookaheadUtility }?.actionId ?: "none",
            productionBest = production?.actionId ?: "none",
            topSwing = production?.lookaheadUtility ?: 0.0,
            heuristicSpread = if (byHeuristic.size < 2) 0.0 else {
                byHeuristic[0].heuristicOnlyValue - byHeuristic[1].heuristicOnlyValue
            },
        )
    }

    private fun recordPositions(): List<BattleDecisionContext> {
        val recorded = mutableListOf<BattleDecisionContext>()
        LocalSelfPlayMeasurement.definitions(BATTLES, SEED).forEach { definition ->
            LocalTacticalScenarioBattle.run(definition, maximumTurns = 20, recordedContexts = recorded)
        }
        return recorded.filter { it.candidates.size > 1 }.take(POSITION_LIMIT)
    }

    private companion object {
        const val BATTLES = 4
        const val POSITION_LIMIT = 40
        const val SEED = 20260825
    }
}
