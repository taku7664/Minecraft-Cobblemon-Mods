package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.internal.ai.BattleDifficultyProfiles
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test

/**
 * Boss doubles with the narrowed second turn against Boss doubles without it, each pairing played from both
 * sides. Opt-in with -Psweeps: a pairing is minutes of Boss decisions, and a small edge needs many of them.
 *
 * Boss doubles rarely finishes four against four in the turn limit, so a battle still going is decided on
 * the HP left: ahead by [DECISIVE_LEAD] Pokemon or more is a win, anything closer a draw.
 */
class LocalNarrowSecondTurnDuelTest {
    @Test
    fun `the narrowed second turn is measured against skipping it in boss doubles`() {
        Assumptions.assumeTrue(System.getProperty("betterai.sweeps") == "true", "opt-in with -Psweeps")
        val narrowed = LocalDecisionTuning.CURRENT
        val skipped = LocalDecisionTuning.CURRENT.copy(id = "skip-second-turn", narrowSecondTurn = false)
        var points = 0.0
        var games = 0
        var leadTotal = 0.0
        for (definition in LocalSelfPlayMeasurement.definitions(PAIRS, SEED, BattleFormat.DOUBLE)) {
            val asCycle = LocalTacticalScenarioBattle.run(definition, MAXIMUM_TURNS, narrowed, skipped,
                BattleDifficultyProfiles.BOSS, BattleDifficultyProfiles.BOSS)
            val asOffense = LocalTacticalScenarioBattle.run(definition, MAXIMUM_TURNS, skipped, narrowed,
                BattleDifficultyProfiles.BOSS, BattleDifficultyProfiles.BOSS)
            // The narrowed side's HP lead at the end of each game.
            val leads = listOf(asCycle.cycleRemainingHp - asCycle.offenseRemainingHp,
                asOffense.offenseRemainingHp - asOffense.cycleRemainingHp)
            val scores = listOf(score(asCycle, "cycle", leads[0]), score(asOffense, "offense", leads[1]))
            points += scores.sum()
            games += 2
            leadTotal += leads.sum()
            println("${definition.name}: narrowed ${scores.joinToString("/")} lead ${leads.joinToString("/") { "%+.2f".format(it) }} " +
                "turns ${asCycle.turns.size}/${asOffense.turns.size} winners ${asCycle.winner}/${asOffense.winner}")
        }
        println("narrowed vs skipped: score %.3f over %d games, mean HP lead %+.3f".format(points / games, games, leadTotal / games))
    }

    private fun score(report: LocalTacticalScenarioReport, narrowedSide: String, lead: Double): Double = when {
        report.winner == narrowedSide -> 1.0
        report.winner != null -> 0.0
        lead >= DECISIVE_LEAD -> 1.0
        lead <= -DECISIVE_LEAD -> 0.0
        else -> 0.5
    }

    private companion object {
        const val PAIRS = 12
        const val SEED = 20260928
        const val MAXIMUM_TURNS = 20
        const val DECISIVE_LEAD = 0.5
    }
}
