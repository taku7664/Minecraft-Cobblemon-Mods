package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.calculation.LocalChanceModel
import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.search.LocalSearchStrategy
import jbro.cobblemon.mcc.internal.ai.BattleDifficultyProfile
import jbro.cobblemon.mcc.internal.ai.BattleDifficultyProfiles
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test

/**
 * One search switch against its alternative, in Boss battles, each pairing played from both sides.
 *
 * Opt-in: -Psweeps -PsweepOnly=<name>, a name from [DUELS] (several with commas). A pairing is minutes of
 * Boss decisions and a small edge needs many of them, so only the asked-for duels run.
 *
 * A battle still going at the turn limit is decided on the HP left: ahead by [DECISIVE_LEAD] Pokemon or
 * more is a win, anything closer a draw.
 */
class LocalSearchSwitchDuelTest {
    private class Duel(val challenger: LocalDecisionTuning, val defender: LocalDecisionTuning, val format: BattleFormat)

    @Test
    fun `search switches are measured against their alternatives`() {
        Assumptions.assumeTrue(System.getProperty("betterai.sweeps") == "true", "opt-in with -Psweeps")
        val asked = System.getProperty("aiengine.sweepOnly").orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
        Assumptions.assumeTrue(asked.isNotEmpty(), "name a duel with -PsweepOnly=" + DUELS.keys.joinToString("|"))
        for (name in asked) {
            val duel = requireNotNull(DUELS[name]) { "no duel $name; known: ${DUELS.keys}" }
            println(run(name, duel))
        }
    }

    private fun run(name: String, duel: Duel, difficulty: BattleDifficultyProfile = BattleDifficultyProfiles.BOSS): String {
        var points = 0.0
        var games = 0
        var leadTotal = 0.0
        for (definition in LocalSelfPlayMeasurement.definitions(PAIRS, SEED, duel.format)) {
            val asCycle = LocalTacticalScenarioBattle.run(definition, MAXIMUM_TURNS, duel.challenger, duel.defender, difficulty, difficulty)
            val asOffense = LocalTacticalScenarioBattle.run(definition, MAXIMUM_TURNS, duel.defender, duel.challenger, difficulty, difficulty)
            // The challenger's HP lead at the end of each game.
            val leads = listOf(asCycle.cycleRemainingHp - asCycle.offenseRemainingHp,
                asOffense.offenseRemainingHp - asOffense.cycleRemainingHp)
            val scores = listOf(score(asCycle, "cycle", leads[0]), score(asOffense, "offense", leads[1]))
            points += scores.sum()
            games += 2
            leadTotal += leads.sum()
            println("$name ${definition.name}: challenger ${scores.joinToString("/")} lead ${leads.joinToString("/") { "%+.2f".format(it) }} " +
                "turns ${asCycle.turns.size}/${asOffense.turns.size} winners ${asCycle.winner}/${asOffense.winner}")
        }
        return "$name: challenger score %.3f over %d games, mean HP lead %+.3f".format(points / games, games, leadTotal / games)
    }

    private fun score(report: LocalTacticalScenarioReport, challengerSide: String, lead: Double): Double = when {
        report.winner == challengerSide -> 1.0
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
        val CURRENT = LocalDecisionTuning.CURRENT

        /** Challenger against defender; the shipped tuning is on one side of each. */
        val DUELS = mapOf(
            "narrow" to Duel(CURRENT.copy(id = "narrow-second-turn", narrowSecondTurn = true), CURRENT, BattleFormat.DOUBLE),
            "monte-carlo" to Duel(CURRENT.copy(id = "monte-carlo", searchStrategy = LocalSearchStrategy.MONTE_CARLO), CURRENT, BattleFormat.DOUBLE),
            "monte-carlo-singles" to Duel(CURRENT.copy(id = "monte-carlo", searchStrategy = LocalSearchStrategy.MONTE_CARLO), CURRENT, BattleFormat.SINGLE),
            "per-turn" to Duel(CURRENT, CURRENT.copy(id = "summed-turns", perTurnSearchValues = false), BattleFormat.DOUBLE),
            "keep-finished" to Duel(CURRENT, CURRENT.copy(id = "discard-cut-depth", keepFinishedCandidates = false), BattleFormat.DOUBLE),
            "intent" to Duel(CURRENT, CURRENT.copy(id = "no-intent", intentResponseWeight = 0.0), BattleFormat.DOUBLE),
            "chance" to Duel(CURRENT, CURRENT.copy(id = "roll-classes", chanceModel = LocalChanceModel.ROLL_CLASSES), BattleFormat.DOUBLE),
            "chance-singles" to Duel(CURRENT, CURRENT.copy(id = "roll-classes", chanceModel = LocalChanceModel.ROLL_CLASSES), BattleFormat.SINGLE),
        )
    }
}
