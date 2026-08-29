package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleDifficultyProfiles
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

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
    @EnabledIfSystemProperty(
        named = "betterai.sweeps",
        matches = "true",
        disabledReason = "A Boss-tier decision is allowed 3 seconds of search, so a full ladder of " +
            "tier duels costs tens of minutes of wall clock. That is a verification run, not a " +
            "regression gate. Run with -Psweeps.",
    )
    fun `difficulty tiers are measured for monotonic strength`() {
        val tiers = listOf(
            "introductory" to BattleDifficultyProfiles.INTRODUCTORY,
            "standard" to BattleDifficultyProfiles.STANDARD,
            "advanced" to BattleDifficultyProfiles.ADVANCED,
            "boss" to BattleDifficultyProfiles.BOSS,
        )
        // Each tier against the one below it. Adjacent pairs, not all pairs: if every step up wins its
        // own comparison the ordering holds, and adjacent steps are the hardest case to pass.
        val duels = tiers.zipWithNext { (lowerName, lower), (upperName, upper) ->
            Triple(
                "$upperName vs $lowerName",
                upper.lookaheadPlies to lower.lookaheadPlies,
                LocalSelfPlayMeasurement.tierDuel(
                    label = "$upperName (${upper.lookaheadPlies}ply) vs $lowerName (${lower.lookaheadPlies}ply)",
                    challenger = upper,
                    defender = lower,
                    battles = TIER_BATTLES,
                    seed = SEED,
                ),
            )
        }

        val report = buildString {
            appendLine("=".repeat(104))
            appendLine("DIFFICULTY TIER REGRESSION  seed=$SEED battles=$TIER_BATTLES per pairing")
            appendLine("=".repeat(104))
            appendLine("A tier only means something if a deeper search reaches the ranking. Under the legacy")
            appendLine("trust gate it did not - the search was multiplied by (revealedMoves/4)^2 per")
            appendLine("unrevealed opponent, which is 0.0 before anything is revealed - so every tier played")
            appendLine("the same flat heuristic. These duels are what that fix has to justify.")
            appendLine()
            duels.forEach { (_, plies, tally) ->
                appendLine("${tally.row()}   plies ${plies.first} vs ${plies.second}")
            }
            appendLine()
            appendLine("share above 50% means the deeper tier is genuinely stronger.")
            appendLine("At n=${TIER_BATTLES * 2} one battle is ${"%.1f".format(100.0 / (TIER_BATTLES * 2))} points, so read small gaps as noise.")
        }
        println(report)

        duels.forEach { (name, _, tally) ->
            assertTrue(
                tally.challengerWins + tally.defenderWins + tally.undecided == TIER_BATTLES * 2,
                "$name did not produce a complete tally\n$report",
            )
        }
    }

    private companion object {
        /**
         * A 40-battle sample resolves to 2.5 percentage points per battle, so it can only see large
         * effects. That is deliberate for the default run - it is a smoke check that the harness works
         * and the shape of play has not collapsed. Run with -Psweeps for a sample big enough to
         * separate a real difference from a coin flip.
         */
        val BATTLES = if (System.getProperty("betterai.sweeps") == "true") 200 else 40
        /**
         * Deliberately small on an ordinary run. Each pairing plays this many battles twice, and the
         * deeper tier spends up to a second and a half per decision, so the ladder is minutes per
         * pairing however it is sized. The report prints the resolution per battle so a narrow result
         * is read as narrow.
         *
         * A sweep widens it, because the one result that has ever come back outside the noise -
         * two plies beating one - arrived at n=24 where a single battle is 4.2 points. A number that
         * would change a conclusion in the plan has to be measured at a width that can carry it.
         */
        val TIER_BATTLES = if (System.getProperty("betterai.sweeps") == "true") 30 else 12
        const val SEED = 20260825
    }
}
