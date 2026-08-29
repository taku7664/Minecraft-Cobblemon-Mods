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
 * Whether `foresightWeight` moves a decision at all, asked without playing a battle.
 *
 * The ladder sweep reported the lever as non-monotonic: 0.60 beat 0.25 at 56.1%, 1.0 beat 0.60 at
 * 49.1%, and 1.0 beat 0.25 at 46.4%. Read as three measurements that is intransitive and needs an
 * explanation. Read as one measurement it is nothing at all - sixty battles put a standard error of
 * 6.5 points on every one of those numbers, so the widest of them is under one sigma from a coin.
 * A lever that does something and a lever that does nothing produce that table equally well.
 *
 * Win rate is the wrong instrument for the question. Two settings that pick the same action can
 * never be separated by any number of battles, and that comparison is deterministic: run the same
 * recorded positions at each weight and count where the chosen action differs. Zero divergence
 * closes the question outright. Non-zero divergence says the lever moves decisions and hands the
 * self-play measurement a reason to be run at a width that could see it.
 */
class LocalForesightLeverTest {
    @Test
    fun `report how often the foresight weight changes the chosen action`() {
        val positions = recordPositions()
        val chosen = LinkedHashMap<String, List<String>>()

        WEIGHTS.forEach { weight ->
            chosen["w=%.2f".format(weight)] = decisionsFor(positions, advancedAt(FIXED_PLIES, weight))
        }
        // The floor the split is supposed to collapse to. If the lever works, w=0 at two plies and a
        // one-ply search are the same trainer, because the far half is multiplied away.
        chosen["1ply"] = decisionsFor(positions, advancedAt(1, 1.0))

        println("FORESIGHT LEVER  positions=${positions.size}  depth=$FIXED_PLIES plies")
        println("Divergence is the share of positions where the chosen action differs.")
        println()
        val labels = chosen.keys.toList()
        print("%-10s".format(""))
        labels.forEach { print("%9s".format(it)) }
        println()
        labels.forEach { row ->
            print("%-10s".format(row))
            labels.forEach { column ->
                val a = chosen.getValue(row)
                val b = chosen.getValue(column)
                val differing = a.indices.count { a[it] != b[it] }
                print("%8.1f%%".format(differing * 100.0 / a.size.coerceAtLeast(1)))
            }
            println()
        }
        println()
        println("At n=${positions.size} one position is ${"%.1f".format(100.0 / positions.size)} points.")
        println("A row of zeroes against w=1.00 means no battle count can ever separate those tiers.")
    }

    private fun decisionsFor(
        positions: List<BattleDecisionContext>,
        difficulty: BattleDifficultyProfile,
    ): List<String> {
        val profile = BattleTrainerProfile(
            skillLevel = 3,
            personality = BattleTrainerProfile.champion().personality,
            difficulty = difficulty,
        )
        return positions.map { context ->
            val calculated = PublicBattleTacticalCalculator.calculate(context)
            val base = LocalBattleActionPolicy.rank(calculated, null, profile, LocalDecisionTuning.CURRENT)
            val evaluation = LocalRecursiveLookaheadEvaluator.evaluate(
                ranked = base,
                context = calculated,
                profile = profile,
                tuning = LocalDecisionTuning.CURRENT,
            )
            evaluation.ranked.maxByOrNull { it.comparisonValue }?.outcome?.candidate?.actionId ?: "none"
        }
    }

    private fun advancedAt(plies: Int, weight: Double): BattleDifficultyProfile =
        BattleDifficultyProfiles.ADVANCED.copy(lookaheadPlies = plies, foresightWeight = weight)

    private fun recordPositions(): List<BattleDecisionContext> {
        val recorded = mutableListOf<BattleDecisionContext>()
        LocalSelfPlayMeasurement.definitions(8, SEED).forEach { definition ->
            LocalTacticalScenarioBattle.run(definition, maximumTurns = 20, recordedContexts = recorded)
        }
        return recorded.filter { it.candidates.size > 1 }.take(80)
    }

    private companion object {
        const val FIXED_PLIES = 2
        const val SEED = 20260830
        val WEIGHTS = listOf(0.0, 0.25, 0.60, 0.85, 1.0)
    }
}
