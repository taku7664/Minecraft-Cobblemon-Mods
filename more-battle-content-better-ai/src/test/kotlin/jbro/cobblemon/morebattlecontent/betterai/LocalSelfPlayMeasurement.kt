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

internal data class LocalHeadToHeadTally(
    val label: String,
    val battles: Int,
    val challengerWins: Int,
    val defenderWins: Int,
    val undecided: Int,
    /** Team pairings the challenger took from both sides; see [pairedShare]. */
    val pairedSweeps: Int = 0,
    /** Team pairings the defender took from both sides. */
    val pairedLosses: Int = 0,
    /** Team pairings where each side won its own orientation, so the pairing said nothing. */
    val pairedSplits: Int = 0,
) {
    /** Share of decided battles the challenger took; 0.5 means the change did nothing. */
    val challengerShare: Double get() = (challengerWins + defenderWins).let {
        if (it == 0) 0.5 else challengerWins.toDouble() / it
    }

    /**
     * The same question asked of pairings rather than battles.
     *
     * Built on the reasoning that the dominant variance is which two teams were drawn, so requiring
     * the challenger to take *both* orientations of a pairing would cancel it. Measured, it does the
     * opposite: about four pairings in five split, and discarding them costs far more precision than
     * the cancellation buys - one full-span foresight arm came back at 57.9% +-11.5 paired against
     * 51.4% +-3.8 unpaired. A split is not a pairing the teams decided, it is the ordinary case, and
     * throwing it away throws away the measurement.
     *
     * Kept as a secondary reading, because a paired number that disagrees with the unpaired one is
     * worth seeing. [challengerShare] is the one to judge on.
     */
    val pairedShare: Double get() = (pairedSweeps + pairedLosses).let {
        if (it == 0) 0.5 else pairedSweeps.toDouble() / it
    }

    /**
     * One standard error on [pairedShare], so a reported number carries the width it was measured at.
     *
     * The ladder was read for a session as non-monotonic on the strength of 56.1%, 49.1% and 46.4%
     * over sixty battles each. Every one of those sat inside one standard error of a coin. Numbers
     * printed without their width invite exactly that.
     */
    val pairedStandardError: Double get() = (pairedSweeps + pairedLosses).let {
        if (it <= 1) 0.5 else 0.5 / Math.sqrt(it.toDouble())
    }

    /**
     * One standard error on [challengerShare].
     *
     * The ladder was read for a session as non-monotonic on the strength of 56.1%, 49.1% and 46.4%
     * over sixty battles each. Every one of those sat inside one standard error of a coin, and three
     * coin flips are intransitive about as often as not. A share printed without the width it was
     * measured at invites exactly that reading, so the width is no longer optional here.
     */
    val standardError: Double get() = (challengerWins + defenderWins).let {
        if (it <= 1) 0.5 else 0.5 / Math.sqrt(it.toDouble())
    }

    fun row(): String = String.format(
        "%-26s n=%-4d challenger=%-4d defender=%-4d undecided=%-4d  share=%5.1f%% +-%4.1f",
        label, battles, challengerWins, defenderWins, undecided,
        challengerShare * 100, standardError * 100,
    )

    /** The paired reading, with the width it was measured at and the pairings it had to discard. */
    fun pairedRow(): String = String.format(
        "%-26s pairings=%-4d swept=%-4d lost=%-4d split=%-4d  paired=%5.1f%% +-%4.1f",
        label, pairedSweeps + pairedLosses + pairedSplits, pairedSweeps, pairedLosses, pairedSplits,
        pairedShare * 100, pairedStandardError * 100,
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
        var challengerWins = 0
        var defenderWins = 0
        var undecided = 0
        definitions(battles, seed).forEach { definition ->
            val asCycle = LocalTacticalScenarioBattle.run(definition, maximumTurns, challenger, defender)
            when (asCycle.winner) {
                "cycle" -> challengerWins++
                "offense" -> defenderWins++
                else -> undecided++
            }
            val asOffense = LocalTacticalScenarioBattle.run(definition, maximumTurns, defender, challenger)
            when (asOffense.winner) {
                "offense" -> challengerWins++
                "cycle" -> defenderWins++
                else -> undecided++
            }
        }
        return LocalHeadToHeadTally(
            label = label,
            battles = battles * 2,
            challengerWins = challengerWins,
            defenderWins = defenderWins,
            undecided = undecided,
        )
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
        var challengerWins = 0
        var defenderWins = 0
        var undecided = 0
        var pairedSweeps = 0
        var pairedLosses = 0
        var pairedSplits = 0
        definitions(battles, seed).forEach { definition ->
            val asCycle = LocalTacticalScenarioBattle.run(
                definition, maximumTurns,
                cycleDifficulty = challenger, offenseDifficulty = defender,
            )
            when (asCycle.winner) {
                "cycle" -> challengerWins++
                "offense" -> defenderWins++
                else -> undecided++
            }
            val asOffense = LocalTacticalScenarioBattle.run(
                definition, maximumTurns,
                cycleDifficulty = defender, offenseDifficulty = challenger,
            )
            when (asOffense.winner) {
                "offense" -> challengerWins++
                "cycle" -> defenderWins++
                else -> undecided++
            }
            // Both orientations of one pairing, judged together. A pairing only counts for the
            // challenger if the challenger took the side the defender could not.
            val tookFirst = asCycle.winner == "cycle"
            val tookSecond = asOffense.winner == "offense"
            val lostFirst = asCycle.winner == "offense"
            val lostSecond = asOffense.winner == "cycle"
            when {
                tookFirst && tookSecond -> pairedSweeps++
                lostFirst && lostSecond -> pairedLosses++
                else -> pairedSplits++
            }
        }
        return LocalHeadToHeadTally(
            label, battles * 2, challengerWins, defenderWins, undecided,
            pairedSweeps = pairedSweeps, pairedLosses = pairedLosses, pairedSplits = pairedSplits,
        )
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
