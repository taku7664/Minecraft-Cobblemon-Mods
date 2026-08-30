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
    fun `sweep the difficulty levers at a fixed depth`() {
        val report = buildString {
            appendLine("LADDER SWEEP  depth held at $FIXED_PLIES plies for every arm but the last")
            appendLine("Anything that separates here separates without spending a deeper search.")
            appendLine("Read the paired row, not the battle row: an unbalanced pairing scores one win")
            appendLine("and one loss whatever either trainer does, and that is noise, not evidence.")
            appendLine()
            appendLine("-- regret band: whether the tier is allowed a worse action at all --")
            appendLine("Every other axis measured flat, and the draw diagnostics say why. Sharpness")
            appendLine("swings non-best choices from 44.1% of contested turns to 17.6% and wins nothing,")
            appendLine("because the band admits only actions that are genuinely interchangeable - which")
            appendLine("means the band is well calibrated and that no lever inside it can be a")
            appendLine("difficulty. The band itself is the boundary, and widening it moves the share of")
            appendLine("turns with no choice at all from 43.3% down to 13.3%.")
            appendLine("Challenger is the *narrow* arm here, so above 50% means narrow plays better and")
            appendLine("the ladder points the right way.")
            BAND_ARMS.forEach { (narrow, wide) ->
                val tally = LocalSelfPlayMeasurement.tierDuel(
                    label = "band $narrow vs $wide",
                    challenger = base().copy(decisionRegretBand = narrow),
                    defender = base().copy(decisionRegretBand = wide),
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

    /**
     * The shipped tiers against each other, exactly as a player meets them.
     *
     * Separate from the lever sweep and reporting as it goes, for a reason that cost half an hour:
     * the sweep accumulated its whole report into one string and printed at the end, and when the
     * JVM died partway through a Boss arm every arm that had already finished died with it. A
     * measurement that only exists at the end of a thirty-minute run is a measurement that does not
     * survive its own failure.
     *
     * Boss searches four plies, so this is the most expensive thing in the module. Fewer definitions
     * than the lever arms use, and the reported error says what that costs.
     */
    @Test
    @EnabledIfSystemProperty(
        named = "betterai.sweeps",
        matches = "true",
        disabledReason = "The Boss arms search four plies. Run with -Psweeps when the ladder is in question.",
    )
    fun `measure the shipped ladder rung by rung`() {
        println("SHIPPED LADDER  challenger is always the higher tier")
        println("Adjacent rungs are the promise a difficulty setting makes; the ends say whether the")
        println("ladder has any total height. Before the band existed the middle rungs were 48.1% and")
        println("45.5% - three stages that were the same stage.")
        println()
        LADDER_ARMS.forEach { (lower, higher) ->
            val tally = LocalSelfPlayMeasurement.tierDuel(
                label = "${higher.tier} vs ${lower.tier}",
                challenger = higher,
                defender = lower,
                battles = LADDER_DEFINITIONS,
                seed = SEED,
            )
            // Printed per arm rather than collected, so a run that dies still leaves its evidence.
            println("  " + tally.row())
            println("  " + tally.pairedRow())
            println()
        }
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

        /** Boss searches four plies per decision, so the ladder buys its arms at a narrower width. */
        const val LADDER_DEFINITIONS = 50
        const val SEED = 20260829

        /**
         * Narrow against wide, at the ends of what the draw diagnostics showed the band reaching.
         *
         * Both arms take the narrow side as challenger, so a working ladder reads above 50% and the
         * sign of the result is the whole question. A band that widens without weakening would be one
         * more setting that changes behaviour and not strength, and this module has now found three.
         */
        val BAND_ARMS = listOf(1.0 to 4.0, 1.0 to 8.0)

        /** The rung that carried the whole ladder, taken at a width that can hold it. */
        val DEPTH_ARMS = listOf(1 to 2)

        /**
         * The shipped tiers, adjacent rungs first and then the full ladder.
         *
         * Adjacent rungs are the promise a difficulty setting actually makes - a player who moves up
         * one stage should meet a better trainer - and the ends say whether the ladder has any total
         * height. Before the band existed the middle rungs measured 48.1% and 45.5%, which is to say
         * the ladder had three stages that were the same stage.
         */
        val LADDER_ARMS = listOf(
            BattleDifficultyProfiles.INTRODUCTORY to BattleDifficultyProfiles.STANDARD,
            BattleDifficultyProfiles.STANDARD to BattleDifficultyProfiles.ADVANCED,
            BattleDifficultyProfiles.ADVANCED to BattleDifficultyProfiles.BOSS,
            BattleDifficultyProfiles.INTRODUCTORY to BattleDifficultyProfiles.BOSS,
        )

    }
}
