package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.search.LocalLookaheadBudget
import jbro.cobblemon.mcc.betterai.search.LocalLookaheadBudgetPolicy
import jbro.cobblemon.mcc.internal.ai.BattleDifficultyProfile
import jbro.cobblemon.mcc.internal.ai.BattleDifficultyProfiles
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test

/**
 * Boss against Introductory on the teams of a real tournament ([LocalTournamentGames]), each game replayed with the
 * real players' three on each side.
 *
 * Each game is played with Boss on the real winner's team and again with Boss on the real loser's. If the better
 * brain mattered, the real loser's team would win more often under Boss than under Introductory; if the games simply
 * came out as they did in the tournament whichever brain played them, the teams decided and the brains did not. The
 * mirrors (Boss against Boss, Introductory against Introductory) say how far each team carries itself.
 *
 * Opt-in: -Psweeps -PsweepOnly=tournament. The system properties aiengine.tournamentSeeds (draws per game and
 * pairing) and aiengine.tournamentShard ("i/n", the games this run takes) size a run. One line per battle:
 * `TOURNAMENT <pairing> <game> <draw> p1=<difficulty> p2=<difficulty> winner=<p1|p2|draw> lead=<p1 HP lead> tera=<p1/p2 turn>`.
 */
class LocalTournamentDuelTest {
    @Test
    fun `boss and introductory replay a real tournament`() {
        Assumptions.assumeTrue(System.getProperty("betterai.sweeps") == "true", "opt-in with -Psweeps")
        Assumptions.assumeTrue("tournament" in System.getProperty("aiengine.sweepOnly").orEmpty().split(','),
            "opt-in with -PsweepOnly=tournament")
        val seeds = System.getProperty("aiengine.tournamentSeeds")?.toIntOrNull() ?: SEEDS
        val (shard, shards) = (System.getProperty("aiengine.tournamentShard") ?: "0/1").split('/').map(String::toInt)
        val games = LocalTournamentGames.all().filterIndexed { index, _ -> index % shards == shard }
        val pairings = if (System.getProperty("aiengine.tournamentPairings") == "reserve") RESERVE_PAIRINGS else PAIRINGS
        for (game in games) {
            for (draw in 0 until seeds) {
                val definition = LocalTacticalScenarioDefinition(
                    name = game.name,
                    cycleSetIds = game.p1,
                    offenseSetIds = game.p2,
                    seed = (game.name + "#" + draw).hashCode(),
                )
                for ((pairing, p1, p2) in pairings) {
                    val report = LocalTacticalScenarioBattle.run(definition, MAXIMUM_TURNS, p1.tuning, p2.tuning,
                        p1.difficulty, p2.difficulty, lookaheadBudget = UNLIMITED, terastallization = TERASTALLIZATION)
                    val tera = listOf(report.turns.indexOfFirst { "+테라" in it.cycleActual }, report.turns.indexOfFirst { "+테라" in it.offenseActual })
                        .map { if (it < 0) "-" else "T${report.turns[it].turn}" }
                    val lead = report.cycleRemainingHp - report.offenseRemainingHp
                    val winner = when {
                        report.winner == "cycle" -> "p1"
                        report.winner == "offense" -> "p2"
                        lead >= DECISIVE_LEAD -> "p1"
                        lead <= -DECISIVE_LEAD -> "p2"
                        else -> "draw"
                    }
                    println("TOURNAMENT $pairing ${game.name} $draw p1=${p1.label} p2=${p2.label} winner=$winner " +
                        "real=${game.winner} complete=${game.complete} turns=${report.turns.size} lead=${"%+.2f".format(lead)} tera=${tera.joinToString("/")}")
                }
            }
        }
    }

    private companion object {
        const val SEEDS = 4
        /** -Daiengine.tournamentTera=false plays the same games without Terastallization. */
        val TERASTALLIZATION = System.getProperty("aiengine.tournamentTera") != "false"
        const val MAXIMUM_TURNS = 30
        const val DECISIVE_LEAD = 0.5
        class Brain(val label: String, val difficulty: BattleDifficultyProfile, val tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT)
        val BOSS = Brain("boss", BattleDifficultyProfiles.BOSS)
        val INTRODUCTORY = Brain("introductory", BattleDifficultyProfiles.INTRODUCTORY)
        /** Boss that spends its Tera whenever this turn's gain is the best, with no reserve for the ace. */
        val BOSS_SPEND = Brain("boss-spend", BattleDifficultyProfiles.BOSS,
            LocalDecisionTuning.CURRENT.copy(id = "mechanic-spend", mechanicReserve = false))

        /** Each pairing with its p1 and p2 brain; Boss against Introductory is played from both sides. */
        val PAIRINGS: List<Triple<String, Brain, Brain>> = listOf(
            Triple("boss-vs-introductory", BOSS, INTRODUCTORY),
            Triple("boss-vs-introductory", INTRODUCTORY, BOSS),
            Triple("boss-mirror", BOSS, BOSS),
            Triple("introductory-mirror", INTRODUCTORY, INTRODUCTORY),
        )

        /** -Daiengine.tournamentPairings=reserve: Boss keeping its Tera for the ace against Boss spending it, both sides. */
        val RESERVE_PAIRINGS: List<Triple<String, Brain, Brain>> = listOf(
            Triple("reserve-vs-spend", BOSS, BOSS_SPEND),
            Triple("reserve-vs-spend", BOSS_SPEND, BOSS),
        )

        /** As in [LocalSearchSwitchDuelTest]: no time limit, so the run measures the brain and not the machine. */
        val UNLIMITED: (BattleTrainerTier) -> LocalLookaheadBudget = { tier ->
            LocalLookaheadBudgetPolicy.forTier(tier).copy(timeMillis = Long.MAX_VALUE,
                nodeLimit = System.getProperty("aiengine.duelNodes")?.toIntOrNull() ?: 50_000_000)
        }
    }
}
