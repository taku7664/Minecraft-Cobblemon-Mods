package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleDifficultyProfiles
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Sweeps how much of the value judgement should belong to the search.
 *
 * The search reaches a different conclusion from the immediate heuristic in 57-72% of positions and
 * changes the final answer in 10-17.5% of them, because the comparison adds its verdict to a
 * heuristic that has already priced the same turn. `searchAuthority` withdraws the heuristic's value
 * half in proportion, leaving only its statements about the candidate - penalties, ally collateral,
 * mechanic cost, strategy alignment - which no board evaluation can reconstruct.
 *
 * Handing the whole judgement over is the obvious end point and not obviously the right one, so it is
 * a weight rather than a switch. What this reports is the exchange rate: how much the answer moves,
 * and, at each setting, whether it is still the answer of a competent player.
 *
 * The second half of that is the part this project has learned to insist on. A change measured only
 * by how much behaviour it altered was already accepted once here and turned out to be the AI playing
 * worse moves; the paired penalty tests exist because of it, and they run at every setting below.
 */
class LocalSearchAuthoritySweepTest {
    @Test
    fun `search authority is swept for how much of the answer it changes`() {
        val contexts = recordPositions()
        val settings = listOf(0.0, 0.35, 0.7, 1.0)
        val profile = BattleTrainerProfile(
            skillLevel = 2,
            personality = BattleTrainerProfile.champion().personality,
            difficulty = BattleDifficultyProfiles.BOSS,
        )

        val rows = settings.map { authority ->
            val tuning = LocalDecisionTuning.CURRENT.copy(id = "auth$authority", searchAuthority = authority)
            val readings = contexts.map { context ->
                val breakdown = LocalDecisionInstrumentation.inspect(context, profile, tuning = tuning)
                val heuristicBest = breakdown.candidates.maxByOrNull { it.heuristicOnlyValue }?.actionId
                val productionBest = breakdown.candidates.maxByOrNull { it.comparisonValue }?.actionId
                heuristicBest to productionBest
            }
            authority to readings
        }

        val report = buildString {
            appendLine("=".repeat(100))
            appendLine("SEARCH AUTHORITY SWEEP  positions=${contexts.size}  tier=boss")
            appendLine("=".repeat(100))
            appendLine("0.00 = the heuristic decides and the search advises, which is what shipped.")
            appendLine("1.00 = the search owns the value judgement; the heuristic keeps only the things")
            appendLine("       a board evaluation cannot re-derive.")
            appendLine()
            val baseline = rows.first().second
            rows.forEach { (authority, readings) ->
                val movedFromHeuristic = readings.count { it.first != it.second }
                val movedFromShipping = readings.indices.count { readings[it].second != baseline[it].second }
                appendLine(
                    "  authority=%.2f   answer differs from heuristic = %5.1f%% (%d/%d)   from shipping = %5.1f%%".format(
                        authority,
                        movedFromHeuristic * 100.0 / contexts.size, movedFromHeuristic, contexts.size,
                        movedFromShipping * 100.0 / contexts.size,
                    ),
                )
            }
            appendLine()
            appendLine("The target for this work is 40% against the heuristic. A setting that reaches it")
            appendLine("has only earned half its case; the penalty guards are the other half.")
        }
        println(report)

        assertTrue(contexts.isNotEmpty(), report)
        // Full authority has to actually change something, or the withdrawal is not reaching the
        // comparison at all and every number above is measuring nothing.
        val fullAuthority = rows.last().second
        val shipping = rows.first().second
        assertTrue(
            fullAuthority.indices.any { fullAuthority[it].second != shipping[it].second },
            report,
        )
    }

    @Test
    fun `search authority is measured against the play it produces`() {
        // The only comparison that isolates the change: the same tuning with and without it. The
        // suite's standing head-to-head runs against LEGACY, which differs in a dozen other ways, so
        // it cannot answer this.
        val shipping = LocalDecisionTuning.CURRENT.copy(id = "authority_off", searchAuthority = 0.0)
        val full = LocalDecisionTuning.CURRENT.copy(id = "authority_full", searchAuthority = 1.0)
        val duel = LocalSelfPlayMeasurement.headToHead(
            label = "search-led vs heuristic-led",
            challenger = full,
            defender = shipping,
            battles = BATTLES_PLAYED,
            seed = SEED,
        )
        val searchLed = LocalSelfPlayMeasurement.mirror("search-led", full, BATTLES_PLAYED, SEED)
        val heuristicLed = LocalSelfPlayMeasurement.mirror("heuristic-led", shipping, BATTLES_PLAYED, SEED)

        val report = buildString {
            appendLine("=".repeat(104))
            appendLine("SEARCH AUTHORITY, PLAYED OUT  battles=$BATTLES_PLAYED")
            appendLine("=".repeat(104))
            appendLine(duel.row())
            appendLine()
            appendLine(heuristicLed.row())
            appendLine(searchLed.row())
            appendLine()
            appendLine("share below 50% means handing the value judgement to the search made the AI")
            appendLine("worse, whatever it did to how often the answer changed.")
        }
        println(report)

        assertTrue(duel.challengerWins + duel.defenderWins + duel.undecided == BATTLES_PLAYED * 2, report)
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
        const val BATTLES_PLAYED = 60
    }
}
