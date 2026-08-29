package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleDifficultyProfile
import jbro.cobblemon.morebattlecontent.api.ai.BattleDifficultyProfiles
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * What separates one difficulty tier from the next, if it is not depth.
 *
 * The ladder was re-measured on the corrected fixture and only its bottom rung came back outside the
 * noise: one ply to two is 61.1% over 60 battles, two to three is 48.1%, three to four is 45.5%. So
 * Advanced is not separated from Standard, and Boss is not separated from Advanced, and the thing
 * currently used to separate them is plies.
 *
 * `foresightWeight` was introduced as the better lever - it decides how much of what the search finds
 * past the current turn survives into the ranking. An earlier run of this sweep read it as *non-
 * monotonic*: 0.60 beat 0.25 at 56.1%, 1.0 beat 0.60 at 49.1%, and 1.0 beat 0.25 at 46.4%. That
 * reading was wrong, and wrong in a way worth recording, because nothing about those three numbers
 * was significant. Sixty battles put a standard error of 6.5 points on each, so the widest of them
 * sat under one sigma from a coin. Three coin flips are intransitive about as often as not.
 *
 * `LocalForesightLeverTest` settled the question deterministically instead: the weight moves the
 * chosen action in a perfectly ordered 0% to 12.5% span, so the lever works and the earlier table
 * was measuring the harness. What that test also showed is the real problem - the shipped tiers sit
 * at 0.60, 0.85 and 1.0, which are within 2.5% of each other. The lever is fine. The settings are
 * bunched at one end of it.
 *
 * So this sweep asks the one thing divergence cannot answer: which direction is stronger. It takes
 * the span at its ends rather than at the shipped steps, and it reports the *paired* share, because
 * the dominant variance here is which teams were drawn and pairing cancels it.
 *
 * Opt-in. Each pairing plays every definition twice over.
 */
class LocalTierLeverSweepTest {
    @Test
    @EnabledIfSystemProperty(
        named = "betterai.sweeps",
        matches = "true",
        disabledReason = "A ladder sweep is minutes per pairing. Run with -Psweeps when a lever is in question.",
    )
    fun `sweep the foresight lever at a fixed depth`() {
        val report = buildString {
            appendLine("FORESIGHT SWEEP  depth held at $FIXED_PLIES plies for every arm")
            appendLine("Anything that separates here separates without spending a deeper search.")
            appendLine("Read the paired row, not the battle row: an unbalanced pairing scores one win")
            appendLine("and one loss whatever either trainer does, and that is noise, not evidence.")
            appendLine()
            FORESIGHT_ARMS.forEach { (low, high) ->
                val tally = LocalSelfPlayMeasurement.tierDuel(
                    label = "foresight $high vs $low",
                    challenger = base(foresightWeight = high),
                    defender = base(foresightWeight = low),
                    battles = DEFINITIONS,
                    seed = SEED,
                )
                appendLine("  " + tally.row())
                appendLine("  " + tally.pairedRow())
                appendLine()
            }
            appendLine("-- depth, at the one rung the ladder measurement said was outside the noise --")
            appendLine("Re-asked at three times the width. 61.1% over sixty battles was 1.7 standard")
            appendLine("errors; that is a number to check, not a number to build a ladder on.")
            DEPTH_ARMS.forEach { (low, high) ->
                val tally = LocalSelfPlayMeasurement.tierDuel(
                    label = "depth $high vs $low",
                    challenger = BattleDifficultyProfiles.ADVANCED.copy(lookaheadPlies = high),
                    defender = BattleDifficultyProfiles.ADVANCED.copy(lookaheadPlies = low),
                    battles = DEFINITIONS,
                    seed = SEED,
                )
                appendLine("  " + tally.row())
                appendLine("  " + tally.pairedRow())
                appendLine()
            }
            appendLine("A lever worth using has to clear its own reported error by a comfortable margin.")
        }
        println(report)
    }

    /** Advanced in every respect except the lever under test, and always at the fixed depth. */
    private fun base(
        foresightWeight: Double = BattleDifficultyProfiles.ADVANCED.foresightWeight,
    ): BattleDifficultyProfile = BattleDifficultyProfiles.ADVANCED.copy(
        lookaheadPlies = FIXED_PLIES,
        foresightWeight = foresightWeight,
    )

    private companion object {
        /** Two plies, because that is the depth the ladder measurement says is actually reached and used. */
        const val FIXED_PLIES = 2
        const val DEFINITIONS = 90
        const val SEED = 20260829

        /**
         * The ends first, then the halves.
         *
         * The shipped steps were measured before and could not be resolved; taking the full span is
         * the only arm with a prior reason to show anything, and the halves say where in the span the
         * strength actually lives.
         */
        val FORESIGHT_ARMS = listOf(0.0 to 1.0, 0.0 to 0.60, 0.60 to 1.0)

        /** The rung that carried the whole ladder, taken at a width that can hold it. */
        val DEPTH_ARMS = listOf(1 to 2)
    }
}
