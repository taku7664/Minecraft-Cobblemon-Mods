package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleDifficultyProfile
import jbro.cobblemon.morebattlecontent.api.ai.BattleDifficultyProfiles
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionPolicy
import jbro.cobblemon.morebattlecontent.betterai.search.LocalRecursiveLookaheadEvaluator
import org.junit.jupiter.api.Test

/**
 * Historical comparison that motivated reducing the shipped Boss depth from four to three.
 *
 * Iterative deepening throws away a depth it could not finish and keeps the last one it did, which is
 * the right thing to do with an incomplete answer. The consequence is that a requested depth nobody
 * reaches is not a deeper search, it is the same search plus the time spent failing to go deeper.
 *
 * Before the 1/2/2/3 contract, Boss requested four plies and truncated in 29 positions out of 40,
 * reaching 3.23 on average. Advanced requested three and reached 2.70. They were, most of the time,
 * returning an answer from the
 * same depth - which is exactly what the ladder measurement shows: Boss against Advanced comes back
 * at 45.5% over 60 battles, a gap of under three battles and squarely inside the noise the report
 * warns about.
 *
 * The shipped Boss now requests three plies. This opt-in historical characterization keeps measuring
 * both halves of the rejected four-ply alternative: whether the chosen action
 * actually differs, and what the request costs when it does not.
 */
class LocalBossRequestedDepthTest {
    @Test
    fun `report what asking for a fourth ply costs and changes`() {
        val positions = recordPositions()
        println("positions=${positions.size}")
        listOf(4, 3).forEach { requestedPlies ->
            val profile = BattleTrainerProfile(
                skillLevel = 3,
                personality = BattleTrainerProfile.champion().personality,
                difficulty = bossWith(requestedPlies),
            )
            var depth = 0
            var truncated = 0
            var nodes = 0
            val choices = mutableListOf<String>()
            val startedAt = System.nanoTime()
            positions.forEach { context ->
                val calculated = PublicBattleTacticalCalculator.calculate(context)
                val base = LocalBattleActionPolicy.rank(calculated, null, profile, LocalDecisionTuning.CURRENT)
                val evaluation = LocalRecursiveLookaheadEvaluator.evaluate(
                    ranked = base,
                    context = calculated,
                    profile = profile,
                    tuning = LocalDecisionTuning.CURRENT,
                )
                depth += evaluation.depthCompleted
                nodes += evaluation.nodesVisited
                if (evaluation.truncated) truncated++
                choices += evaluation.ranked.maxByOrNull { it.comparisonValue }
                    ?.outcome?.candidate?.actionId ?: "none"
            }
            val n = positions.size.coerceAtLeast(1)
            chosen[requestedPlies] = choices
            println(
                String.format(
                    "requested=%dply  reached=%4.2f  truncated=%5.1f%%  nodes=%8.0f  ms/decision=%6.1f",
                    requestedPlies, depth.toDouble() / n, truncated.toDouble() / n * 100,
                    nodes.toDouble() / n, (System.nanoTime() - startedAt) / 1_000_000.0 / n,
                ),
            )
        }
        val four = chosen.getValue(4)
        val three = chosen.getValue(3)
        val differing = four.indices.count { four[it] != three[it] }
        println(
            String.format(
                "asking for the fourth ply changes the chosen action in %d/%d positions (%.1f%%)",
                differing, four.size, differing * 100.0 / four.size.coerceAtLeast(1),
            ),
        )
    }

    /** Boss in every respect except how many plies it asks for. */
    private fun bossWith(plies: Int): BattleDifficultyProfile {
        val boss = BattleDifficultyProfiles.BOSS
        return boss.copy(lookaheadPlies = plies)
    }

    private fun recordPositions(): List<BattleDecisionContext> {
        val recorded = mutableListOf<BattleDecisionContext>()
        LocalSelfPlayMeasurement.definitions(4, 20260829).forEach { definition ->
            LocalTacticalScenarioBattle.run(definition, maximumTurns = 20, recordedContexts = recorded)
        }
        return recorded.filter { it.candidates.size > 1 }.take(40)
    }

    private val chosen = mutableMapOf<Int, List<String>>()
}
