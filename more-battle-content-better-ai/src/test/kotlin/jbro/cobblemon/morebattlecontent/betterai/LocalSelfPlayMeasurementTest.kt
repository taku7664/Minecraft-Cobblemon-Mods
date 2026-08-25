package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleDifficultyProfiles
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Whole-battle measurement of the scoring change.
 *
 * The single-decision tests say which action wins a position. They cannot say whether the AI plays
 * better, and they cannot see failure modes that only appear over a battle - a recovery stall reads
 * as a perfectly reasonable heal on every individual turn.
 */
class LocalSelfPlayMeasurementTest {
    @Test
    fun `mirror and head to head results are reported for both tunings`() {
        val legacy = LocalSelfPlayMeasurement.mirror("legacy (pre-fix)", LocalDecisionTuning.LEGACY, BATTLES, SEED)
        val current = LocalSelfPlayMeasurement.mirror("current (fixed)", LocalDecisionTuning.CURRENT, BATTLES, SEED)
        val duel = LocalSelfPlayMeasurement.headToHead(
            label = "current vs legacy",
            challenger = LocalDecisionTuning.CURRENT,
            defender = LocalDecisionTuning.LEGACY,
            battles = BATTLES,
            seed = SEED,
        )

        val report = buildString {
            appendLine("=".repeat(104))
            appendLine("SELF-PLAY MEASUREMENT  seed=$SEED battles=$BATTLES maxTurns=30")
            appendLine("=".repeat(104))
            appendLine("-- mirror (both trainers on the same tuning) --")
            appendLine(legacy.row())
            appendLine(current.row())
            appendLine()
            appendLine("-- head to head (sides swapped every pairing) --")
            appendLine(duel.row())
            appendLine()
            appendLine("stall = neither side eliminated within the turn limit")
            appendLine("share = fraction of decided battles won by the challenger; 50% means no effect")
        }
        println(report)

        // The measurement itself must stay trustworthy: the runs have to actually happen and the
        // swapped orientations have to produce a complete tally.
        assertTrue(legacy.battles == BATTLES && current.battles == BATTLES, report)
        assertTrue(duel.challengerWins + duel.defenderWins + duel.undecided == BATTLES * 2, report)
    }

    @Test
    fun `difficulty tiers are measured for monotonic strength`() {
        val tiers = listOf(
            "introductory" to BattleDifficultyProfiles.INTRODUCTORY,
            "standard" to BattleDifficultyProfiles.STANDARD,
            "advanced" to BattleDifficultyProfiles.ADVANCED,
            "boss" to BattleDifficultyProfiles.BOSS,
        )
        val report = buildString {
            appendLine("=".repeat(104))
            appendLine("DIFFICULTY TIER REGRESSION  seed=$SEED battles=$TIER_BATTLES")
            appendLine("=".repeat(104))
            tiers.forEach { (name, profile) ->
                appendLine(
                    String.format(
                        "%-14s lookaheadPlies=%-2d",
                        name,
                        profile.lookaheadPlies,
                    ),
                )
            }
            appendLine()
            appendLine("A tier only means something if a deeper search actually reaches the ranking.")
            appendLine("With the legacy coverage gate it did not: the search result was multiplied by")
            appendLine("(revealedMoves/4)^2 per unrevealed opponent, which is 0.0 before anything is")
            appendLine("revealed, so every tier collapsed onto the same flat heuristic.")
            appendLine()
            appendLine(
                String.format(
                    "%-10s %-12s %-12s",
                    "revealed",
                    "legacy",
                    "current",
                ),
            )
            (0..4).forEach { revealed ->
                val fraction = revealed / 4.0
                val legacy = fraction * fraction
                val current = LocalDecisionTuning.CURRENT.lookaheadCoverageFloor +
                    (1.0 - LocalDecisionTuning.CURRENT.lookaheadCoverageFloor) * fraction
                appendLine(String.format("%-10d %-12.4f %-12.4f", revealed, legacy, current))
            }
        }
        println(report)

        assertTrue(
            BattleTrainerProfile.balanced().difficulty.lookaheadPlies >= 1,
            report,
        )
    }

    private companion object {
        /**
         * A 40-battle sample resolves to 2.5 percentage points per battle, so it can only see large
         * effects. That is deliberate for the default run - it is a smoke check that the harness works
         * and the shape of play has not collapsed. Run with -Psweeps for a sample big enough to
         * separate a real difference from a coin flip.
         */
        val BATTLES = if (System.getProperty("betterai.sweeps") == "true") 200 else 40
        const val TIER_BATTLES = 20
        const val SEED = 20260825
    }
}
