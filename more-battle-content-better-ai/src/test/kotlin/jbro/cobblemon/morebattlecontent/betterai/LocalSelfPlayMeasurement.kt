package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.morebattlecontent.api.ai.BattleDifficultyProfile
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import kotlin.random.Random

internal data class LocalSelfPlayTally(
    val label: String,
    val battles: Int,
    val cycleWins: Int,
    val offenseWins: Int,
    val draws: Int,
    val stalls: Int,
    val totalTurns: Int,
    val statusMoves: Int,
    val voluntarySwitches: Int,
) {
    val decisiveRate: Double get() = if (battles == 0) 0.0 else (cycleWins + offenseWins).toDouble() / battles
    val stallRate: Double get() = if (battles == 0) 0.0 else stalls.toDouble() / battles
    val averageTurns: Double get() = if (battles == 0) 0.0 else totalTurns.toDouble() / battles
    val switchesPerBattle: Double get() = if (battles == 0) 0.0 else voluntarySwitches.toDouble() / battles
    val statusPerBattle: Double get() = if (battles == 0) 0.0 else statusMoves.toDouble() / battles

    fun row(): String = String.format(
        "%-26s n=%-4d decisive=%5.1f%%  stall=%5.1f%%  turns=%5.2f  switch/battle=%5.2f  status/battle=%5.2f",
        label, battles, decisiveRate * 100, stallRate * 100, averageTurns, switchesPerBattle, statusPerBattle,
    )
}

/**
 * Runs whole battles instead of single decisions, so a scoring change can be judged by what it does
 * to results rather than by whether it satisfies an assertion someone wrote earlier.
 *
 * Two questions are asked separately because they answer different things: a mirror run says whether
 * a tuning produces battles that finish, and a head-to-head run says whether it actually plays better.
 * A tuning can improve one and ruin the other.
 */
internal object LocalSelfPlayMeasurement {
    /** Both trainers use the same tuning. Measures battle shape: decisiveness, stalls, churn. */
    fun mirror(
        label: String,
        tuning: LocalDecisionTuning,
        battles: Int,
        seed: Int,
        maximumTurns: Int = 30,
        format: BattleFormat = BattleFormat.SINGLE,
    ): LocalSelfPlayTally {
        val reports = definitions(battles, seed, format).map { definition ->
            LocalTacticalScenarioBattle.run(definition, maximumTurns, tuning, tuning)
        }
        return LocalSelfPlayTally(
            label = label,
            battles = reports.size,
            cycleWins = reports.count { it.winner == "cycle" },
            offenseWins = reports.count { it.winner == "offense" },
            draws = reports.count { it.winner == null },
            stalls = reports.count { it.stalled },
            totalTurns = reports.sumOf { it.turns.size },
            statusMoves = reports.sumOf { it.cycleStatusMoves + it.offenseStatusMoves },
            voluntarySwitches = reports.sumOf { it.cycleVoluntarySwitches + it.offenseVoluntarySwitches },
        )
    }

    /**
     * Challenger against defender, each battle played twice with the sides swapped.
     *
     * Swapping matters: the two teams in a definition are not balanced, so a single orientation would
     * measure the team matchup rather than the tuning.
     */
    fun headToHead(
        label: String,
        challenger: LocalDecisionTuning,
        defender: LocalDecisionTuning,
        battles: Int,
        seed: Int,
        maximumTurns: Int = 30,
    ): LocalHeadToHeadTally {
        val pairs = definitions(battles, seed).map { definition ->
            val asCycle = LocalTacticalScenarioBattle.run(definition, maximumTurns, challenger, defender)
            val asOffense = LocalTacticalScenarioBattle.run(definition, maximumTurns, defender, challenger)
            LocalHeadToHeadPair.fromReports(asCycle, asOffense)
        }
        return LocalHeadToHeadTally(label, pairs)
    }

    /**
     * Two difficulty tiers against each other, sides swapped every pairing.
     *
     * A tier is a promise about strength, and until the lookahead trust gate was fixed it could not
     * keep one: the search result was multiplied to zero before the opponent revealed anything, so
     * every tier fell back to the same flat heuristic no matter how many plies it was allowed.
     */
    fun tierDuel(
        label: String,
        challenger: BattleDifficultyProfile,
        defender: BattleDifficultyProfile,
        battles: Int,
        seed: Int,
        maximumTurns: Int = 30,
    ): LocalHeadToHeadTally {
        val pairs = definitions(battles, seed).map { definition ->
            val asCycle = LocalTacticalScenarioBattle.run(
                definition, maximumTurns,
                cycleDifficulty = challenger, offenseDifficulty = defender,
            )
            val asOffense = LocalTacticalScenarioBattle.run(
                definition, maximumTurns,
                cycleDifficulty = defender, offenseDifficulty = challenger,
            )
            LocalHeadToHeadPair.fromReports(asCycle, asOffense)
        }
        return LocalHeadToHeadTally(label, pairs)
    }

    /** Deterministic team pairings shared by every measurement so runs stay comparable. */
    fun definitions(
        count: Int,
        seed: Int,
        format: BattleFormat = BattleFormat.SINGLE,
    ): List<LocalTacticalScenarioDefinition> {
        val random = Random(seed)
        val roster = LocalTacticalSimulationRoster.loadAll()
        val teamSize = if (format == BattleFormat.DOUBLE) DOUBLE_TEAM_SIZE else SINGLE_TEAM_SIZE
        return List(count) { index ->
            LocalTacticalScenarioDefinition(
                name = "selfplay-${index + 1}",
                cycleSetIds = roster.randomTeam(random, teamSize).map { it.setId },
                offenseSetIds = roster.randomTeam(random, teamSize).map { it.setId },
                seed = random.nextInt(),
                format = format,
            )
        }
    }

    private const val SINGLE_TEAM_SIZE = 3

    /** Doubles starts two, so a third and fourth body are what make a knockout replaceable. */
    private const val DOUBLE_TEAM_SIZE = 4
}
