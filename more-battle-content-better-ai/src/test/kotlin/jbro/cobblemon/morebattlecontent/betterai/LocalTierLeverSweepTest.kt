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
 * past the current turn survives into the ranking - and the tiers already differ on it, 0.25 through
 * 1.0. Whether it actually separates anything has never been measured, because every ladder run so
 * far varied depth and foresight together and could not tell which was doing the work.
 *
 * This holds depth fixed and moves one lever at a time. A lever that separates tiers at fixed depth
 * is the answer to what a difficulty setting should mean here; a lever that does not is one more
 * thing that looks like a difficulty and is not.
 *
 * Opt-in. Each pairing plays sixty battles twice over.
 */
class LocalTierLeverSweepTest {
    @Test
    @EnabledIfSystemProperty(
        named = "betterai.sweeps",
        matches = "true",
        disabledReason = "A ladder sweep is minutes per pairing. Run with -Psweeps when a lever is in question.",
    )
    fun `sweep each difficulty lever at a fixed depth`() {
        val report = buildString {
            appendLine("TIER LEVER SWEEP  depth held at ${FIXED_PLIES} plies for every arm")
            appendLine("Anything that separates here separates without spending a deeper search.")
            appendLine()

            appendLine("-- foresight weight: how much of what the search finds is acted on --")
            FORESIGHT_ARMS.forEach { (low, high) ->
                val tally = LocalSelfPlayMeasurement.tierDuel(
                    label = "foresight $high vs $low",
                    challenger = base(foresightWeight = high),
                    defender = base(foresightWeight = low),
                    battles = BATTLES,
                    seed = SEED,
                )
                appendLine("  " + tally.row())
            }
            appendLine()

            appendLine("-- hypotheses per Pokemon: how much of the opponent it models --")
            HYPOTHESIS_ARMS.forEach { (low, high) ->
                val tally = LocalSelfPlayMeasurement.tierDuel(
                    label = "hypotheses $high vs $low",
                    challenger = base(maximumHypothesesPerPokemon = high),
                    defender = base(maximumHypothesesPerPokemon = low),
                    battles = BATTLES,
                    seed = SEED,
                )
                appendLine("  " + tally.row())
            }
            appendLine()
            appendLine("At n=${BATTLES * 2} one battle is ${"%.1f".format(100.0 / (BATTLES * 2))} points.")
            appendLine("A lever worth using has to clear that by more than a couple of battles.")
        }
        println(report)
    }

    /** Advanced in every respect except the lever under test, and always at the fixed depth. */
    private fun base(
        foresightWeight: Double = BattleDifficultyProfiles.ADVANCED.foresightWeight,
        maximumHypothesesPerPokemon: Int = BattleDifficultyProfiles.ADVANCED.maximumHypothesesPerPokemon,
    ): BattleDifficultyProfile = BattleDifficultyProfiles.ADVANCED.copy(
        lookaheadPlies = FIXED_PLIES,
        foresightWeight = foresightWeight,
        maximumHypothesesPerPokemon = maximumHypothesesPerPokemon,
    )

    private companion object {
        /** Two plies, because that is the depth the ladder measurement says is actually reached and used. */
        const val FIXED_PLIES = 2
        const val BATTLES = 30
        const val SEED = 20260829

        /** The span the shipped tiers already use, taken in the steps they already take. */
        val FORESIGHT_ARMS = listOf(0.25 to 0.60, 0.60 to 1.0, 0.25 to 1.0)
        val HYPOTHESIS_ARMS = listOf(3 to 10, 10 to 16, 3 to 16)
    }
}
