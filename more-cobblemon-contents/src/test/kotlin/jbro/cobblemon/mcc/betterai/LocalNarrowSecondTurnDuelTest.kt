package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.internal.ai.BattleDifficultyProfiles
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test

/**
 * Boss doubles with the narrowed second turn against Boss doubles without it, each pairing played from both
 * sides. Opt-in with -Psweeps: a pairing is minutes of Boss decisions, and a small edge needs many of them.
 */
class LocalNarrowSecondTurnDuelTest {
    @Test
    fun `the narrowed second turn is measured against skipping it in boss doubles`() {
        Assumptions.assumeTrue(System.getProperty("betterai.sweeps") == "true", "opt-in with -Psweeps")
        val narrowed = LocalDecisionTuning.CURRENT
        val skipped = LocalDecisionTuning.CURRENT.copy(id = "skip-second-turn", narrowSecondTurn = false)
        val pairs = LocalSelfPlayMeasurement.definitions(PAIRS, SEED, BattleFormat.DOUBLE).map { definition ->
            val asCycle = LocalTacticalScenarioBattle.run(definition, MAXIMUM_TURNS, narrowed, skipped,
                BattleDifficultyProfiles.BOSS, BattleDifficultyProfiles.BOSS)
            val asOffense = LocalTacticalScenarioBattle.run(definition, MAXIMUM_TURNS, skipped, narrowed,
                BattleDifficultyProfiles.BOSS, BattleDifficultyProfiles.BOSS)
            LocalHeadToHeadPair.fromReports(asCycle, asOffense).also { println("${definition.name}: $it") }
        }
        println(LocalHeadToHeadTally("narrowed vs skipped", pairs).row())
    }

    private companion object {
        const val PAIRS = 12
        const val SEED = 20260928
        const val MAXIMUM_TURNS = 20
    }
}
