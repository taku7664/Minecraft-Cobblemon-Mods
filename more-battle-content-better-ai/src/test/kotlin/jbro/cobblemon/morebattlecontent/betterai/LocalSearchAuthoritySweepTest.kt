package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleDifficultyProfiles
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

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

    @Test
    @EnabledIfSystemProperty(
        named = "betterai.sweeps",
        matches = "true",
        disabledReason = "Plays hundreds of battles to calibrate the leaf evaluator. Run with -Psweeps.",
    )
    fun `leaf weights are swept for one that survives handing over the decision`() {
        // Full authority lost at 41.6%, and the diagnosis was that the leaf evaluator is too crude to
        // be handed the decision. Before adding terms to it, the cheaper question: are the terms it
        // already has simply weighted wrong? While the heuristic decides, a rough leaf weight only
        // nudges a ranking. Once the search decides, this ratio is the AI's whole character, and none
        // of these three numbers has ever been tested against a result.
        val shipping = LocalDecisionTuning.CURRENT.copy(id = "heuristic_led", searchAuthority = 0.0)
        val variants = listOf(
            "as-built      p=0.30 k=0.35 s=0.15" to LocalDecisionTuning.CURRENT.copy(
                id = "leaf_asbuilt", searchAuthority = 1.0,
            ),
            "pressure x2   p=0.60 k=0.35 s=0.15" to LocalDecisionTuning.CURRENT.copy(
                id = "leaf_p60", searchAuthority = 1.0, leafPressureWeight = 0.60,
            ),
            "pressure x3   p=0.90 k=0.35 s=0.15" to LocalDecisionTuning.CURRENT.copy(
                id = "leaf_p90", searchAuthority = 1.0, leafPressureWeight = 0.90,
            ),
            "ko x3         p=0.30 k=1.05 s=0.15" to LocalDecisionTuning.CURRENT.copy(
                id = "leaf_k105", searchAuthority = 1.0, leafKnockoutPressure = 1.05,
            ),
            "both raised   p=0.60 k=1.05 s=0.30" to LocalDecisionTuning.CURRENT.copy(
                id = "leaf_both", searchAuthority = 1.0, leafPressureWeight = 0.60,
                leafKnockoutPressure = 1.05, leafSpeedControlValue = 0.30,
            ),
        )

        val rows = variants.map { (label, tuning) ->
            label to LocalSelfPlayMeasurement.headToHead(
                label = label,
                challenger = tuning,
                defender = shipping,
                battles = BATTLES_PLAYED,
                seed = SEED,
            )
        }

        val report = buildString {
            appendLine("=".repeat(112))
            appendLine("LEAF WEIGHT SWEEP UNDER FULL SEARCH AUTHORITY  battles=$BATTLES_PLAYED per variant")
            appendLine("=".repeat(112))
            appendLine("Every row is search-led against the shipping heuristic-led tuning, so share is")
            appendLine("directly 'would handing over the decision with these weights be an improvement'.")
            appendLine()
            rows.forEach { (_, tally) -> appendLine(tally.row()) }
            appendLine()
            appendLine("A row above 50% means the loss was a calibration problem and the inversion can be")
            appendLine("reopened. All rows below means the leaf is missing information, not weight, and")
            appendLine("terms have to be added before this is worth asking again.")
        }
        println(report)

        assertTrue(rows.all { it.second.battles == BATTLES_PLAYED * 2 }, report)
    }

    @Test
    @EnabledIfSystemProperty(
        named = "betterai.sweeps",
        matches = "true",
        disabledReason = "Plays hundreds of battles. Run with -Psweeps.",
    )
    fun `each evaluator alone is measured against the two of them together`() {
        // The head-to-head that rejected the inversion was not the comparison it looked like.
        //
        // What ships is heuristic *and* search: the heuristic ranks and the search adds its verdict.
        // Full authority removes the heuristic's value half, so that duel was "both evaluators" against
        // "the search alone", and the combination has strictly more information. Losing it says nothing
        // about which evaluator is better - only that two are better than one.
        //
        // The missing arm is the heuristic alone. If the combination beats both, they are complementary
        // and replacing either with the other is the wrong shape of change entirely.
        val combined = LocalDecisionTuning.CURRENT.copy(id = "combined")
        val searchOnly = LocalDecisionTuning.CURRENT.copy(id = "search_only", searchAuthority = 1.0)
        val heuristicOnly = LocalDecisionTuning.CURRENT.copy(
            id = "heuristic_only",
            // Nothing the search finds can reach the ranking.
            maximumLookaheadAdjustment = 0.0,
        )

        val searchDuel = LocalSelfPlayMeasurement.headToHead(
            "search alone vs combined", searchOnly, combined, BATTLES_PLAYED, SEED,
        )
        val heuristicDuel = LocalSelfPlayMeasurement.headToHead(
            "heuristic alone vs combined", heuristicOnly, combined, BATTLES_PLAYED, SEED,
        )

        val report = buildString {
            appendLine("=".repeat(112))
            appendLine("ONE EVALUATOR OR TWO  battles=$BATTLES_PLAYED per duel")
            appendLine("=".repeat(112))
            appendLine(searchDuel.row())
            appendLine(heuristicDuel.row())
            appendLine()
            appendLine("Both below 50% means the two evaluators are complementary and the shipping")
            appendLine("comparison is an ensemble rather than one model drowning another. In that case")
            appendLine("'the search is outvoted' was never the defect it was taken for, and the room that")
            appendLine("difficulty and personality need has to come from somewhere else.")
        }
        println(report)

        assertTrue(searchDuel.battles == BATTLES_PLAYED * 2, report)
        assertTrue(heuristicDuel.battles == BATTLES_PLAYED * 2, report)
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
