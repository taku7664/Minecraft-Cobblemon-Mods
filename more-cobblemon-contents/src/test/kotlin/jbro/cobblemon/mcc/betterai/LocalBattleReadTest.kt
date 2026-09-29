package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.search.LocalLookaheadBudgetPolicy
import jbro.cobblemon.mcc.internal.ai.BattleDifficultyProfiles
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import jbro.cobblemon.mcc.betterai.policy.LocalBattleActionPolicy
import jbro.cobblemon.mcc.betterai.search.LocalRecursiveLookaheadEvaluator
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleTrainerProfile

/**
 * Prints whole Boss battles, each side's top candidates included, for reading them back as a player would, and
 * one position's full ranking. Opt-in: -Daiengine.readGames=<n> [-Daiengine.readDoubles=true], or
 * -Daiengine.probeGame=<n> -Daiengine.probeTurn=<t> [-Daiengine.probeSide=offense].
 */
class LocalBattleReadTest {
    @Test
    fun `print battles`() {
        val count = System.getProperty("aiengine.readGames")?.toIntOrNull() ?: 0
        Assumptions.assumeTrue(count > 0)
        val format = if (System.getProperty("aiengine.readDoubles") == "true") BattleFormat.DOUBLE else BattleFormat.SINGLE
        val boss = BattleDifficultyProfiles.BOSS
        for (definition in LocalSelfPlayMeasurement.definitions(count, 20260930, format)) {
            val decisions = mutableListOf<LocalScenarioDecisionTrace>()
            val report = LocalTacticalScenarioBattle.run(definition, 20, readTuning(), readTuning(),
                boss, boss, recordedDecisions = decisions, lookaheadBudget = { LocalLookaheadBudgetPolicy.forTier(it).copy(timeMillis = Long.MAX_VALUE, nodeLimit = System.getProperty("aiengine.readNodes")?.toIntOrNull() ?: 50_000_000) })
            println("GAME ${definition.name} cycle=${definition.cycleSetIds} offense=${definition.offenseSetIds} winner=${report.winner}")
            for (turn in report.turns) {
                println("T${turn.turn} A:${turn.cycleActual} B:${turn.offenseActual} => ${turn.result}")
                println("   A top: ${turn.cycleTop}")
                println("   B top: ${turn.offenseTop}")
                if (System.getProperty("aiengine.readTags") == "true") {
                    decisions.filter { it.turn == turn.turn }.forEach { decision ->
                        println("   ${decision.side} tags: " + decision.tags.filter { tag -> tag.startsWith("lookahead") || tag.startsWith("search") }.joinToString(","))
                    }
                }
            }
        }
    }

    @Test
    fun `probe one position`() {
        val game = System.getProperty("aiengine.probeGame")?.toIntOrNull() ?: 0
        Assumptions.assumeTrue(game > 0)
        val turn = System.getProperty("aiengine.probeTurn")!!.toInt()
        val side = System.getProperty("aiengine.probeSide") ?: "cycle"
        val format = if (System.getProperty("aiengine.readDoubles") == "true") BattleFormat.DOUBLE else BattleFormat.SINGLE
        val boss = BattleDifficultyProfiles.BOSS
        val seed = System.getProperty("aiengine.probeSeed")?.toIntOrNull() ?: 20260930
        val definition = LocalSelfPlayMeasurement.definitions(game, seed, format)[game - 1]
        val contexts = mutableListOf<BattleDecisionContext>()
        LocalTacticalScenarioBattle.run(definition, turn, readTuning(), readTuning(), boss, boss,
            recordedContexts = contexts,
            lookaheadBudget = { LocalLookaheadBudgetPolicy.forTier(it).copy(timeMillis = Long.MAX_VALUE, nodeLimit = System.getProperty("aiengine.readNodes")?.toIntOrNull() ?: 50_000_000) })
        // Contexts alternate cycle then offense each turn; replacements add more. Take the last ones at the turn.
        // The turn's move decisions, not the replacements before them.
        val atTurn = contexts.filter { context -> context.state.turn == turn && context.candidates.none { it.actionId.startsWith("forced:") } }
        val context = if (side == "cycle") atTurn.first() else atTurn.last()
        val depth = System.getProperty("aiengine.probeDepth")?.toIntOrNull() ?: boss.lookaheadPlies
        val profile = BattleTrainerProfile(skillLevel = 2, personality = BattleTrainerProfile.champion().personality,
            difficulty = boss.copy(lookaheadPlies = depth))
        val base = LocalBattleActionPolicy.rank(context, null, profile)
        val result = LocalRecursiveLookaheadEvaluator.evaluate(base, context, profile, readTuning(), clockMillis = { 0L },
            budget = LocalLookaheadBudgetPolicy.forTier(boss.tier).copy(timeMillis = Long.MAX_VALUE, nodeLimit = System.getProperty("aiengine.readNodes")?.toIntOrNull() ?: 50_000_000))
        println("PROBE depth=${result.depthCompleted} nodes=${result.nodesVisited}")
        context.state.pokemon.forEach { println("PROBE mon ${it.side} ${it.speciesId} slot=${it.activeSlot} hp=%.2f st=${it.statusId} stages=${it.statStages}".format(it.hpFraction)) }
        val scores = jbro.cobblemon.mcc.betterai.matchup.LocalMatchupScoreCalculator.calculate(context)
        for (candidate in context.candidates.filter { jbro.cobblemon.mcc.betterai.matchup.LocalSetupGate.raisesOwnStats(it) }) {
            val verdict = jbro.cobblemon.mcc.betterai.matchup.LocalSetupGate.evaluate(candidate, context, scores)
            val user = context.state.pokemon.firstOrNull { it.side == jbro.cobblemon.mcc.internal.ai.BattleSide.ALLY && it.activeSlot == candidate.actorSlot }
            val sweep = user?.let { scores.sweeps[it.battlePokemonId] }
            println("PROBE gate ${candidate.actionId} passes=${verdict?.passes} failures=${verdict?.failures} sweep=%.2f natural=%.2f boosted=%.2f byOpp=${sweep?.boostedByOpponent}".format(
                sweep?.score ?: -1.0, sweep?.naturalSweep ?: -1.0, sweep?.boostedSweep ?: -1.0))
        }
        for (rank in result.ranked) {
            val b = base.first { it.outcome.candidate.actionId == rank.outcome.candidate.actionId }
            println("PROBE searched=${rank.outcome.candidate.actionId in result.responseCoverageByAction} ${rank.outcome.candidate.actionId} cmp=%.1f look=%.1f base=%.1f exec=%.2f worstHp=%.2f".format(
                rank.comparisonValue, rank.lookaheadUtility, b.comparisonValue, rank.executionProbability, rank.worstResponseHpRetention))
        }
    }

    /**
     * Where one more turn of search changes the answer: every move decision of -Daiengine.depthGames battles, ranked
     * at depth 1 and at depth 2 with the same inputs, and the positions where the leaders differ printed.
     */
    @Test
    fun `depth disagreements`() {
        val games = System.getProperty("aiengine.depthGames")?.toIntOrNull() ?: 0
        Assumptions.assumeTrue(games > 0)
        val boss = BattleDifficultyProfiles.BOSS
        val profile = BattleTrainerProfile(skillLevel = 2, personality = BattleTrainerProfile.champion().personality, difficulty = boss)
        val budget = LocalLookaheadBudgetPolicy.forTier(boss.tier).copy(timeMillis = Long.MAX_VALUE, nodeLimit = 50_000_000)
        var decisions = 0
        var differ = 0
        for (definition in LocalSelfPlayMeasurement.definitions(games, 20261101, BattleFormat.SINGLE)) {
            val contexts = mutableListOf<BattleDecisionContext>()
            LocalTacticalScenarioBattle.run(definition, 20, readTuning(), readTuning(), boss, boss,
                recordedContexts = contexts, lookaheadBudget = { LocalLookaheadBudgetPolicy.forTier(it).copy(timeMillis = Long.MAX_VALUE, nodeLimit = 50_000_000) })
            for (context in contexts) {
                if (context.candidates.size < 2 || context.candidates.any { it.actionId.startsWith("forced:") }) continue
                val base = LocalBattleActionPolicy.rank(context, null, profile)
                fun at(depth: Int) = LocalRecursiveLookaheadEvaluator.evaluate(base, context,
                    profile.copy(difficulty = boss.copy(lookaheadPlies = depth)), readTuning(), clockMillis = { 0L }, budget = budget)
                val one = at(1)
                val two = at(2)
                decisions++
                if (one.ranked.first().outcome.candidate.actionId == two.ranked.first().outcome.candidate.actionId) continue
                differ++
                fun label(id: String) = id.substringAfterLast("move:").substringAfterLast("switch:").take(28)
                val active = context.state.pokemon.filter { it.activeSlot != null }.joinToString(" ") {
                    "${it.side.name.take(1)}:${it.speciesId.substringAfter(':')}@%.0f%%".format(it.hpFraction * 100) + (if (it.statStages.isNotEmpty()) it.statStages.toString() else "")
                }
                println("DEPTH ${definition.name} T${context.state.turn} $active")
                for ((name, result) in listOf("d1" to one, "d2" to two)) {
                    println("   $name(depth ${result.depthCompleted}): " + result.ranked.take(3).joinToString(" | ") {
                        "${label(it.outcome.candidate.actionId)} %.1f(look %.1f)".format(it.comparisonValue, it.lookaheadUtility)
                    })
                }
            }
        }
        println("DEPTH summary decisions=$decisions differ=$differ")
    }

    /**
     * Whether a deeper search's different answer is the better one, judged by playing the battle out: at every
     * move decision where the depth 1, 2 and 3 leaders (and the Brain's own pick) are not all the same, each
     * distinct choice is forced there and the rest of the battle played -Daiengine.oracleRollouts times with fresh
     * draws, both sides the current Boss. The value of a choice is the mean final HP lead of the side that made it.
     * Games -Daiengine.oracleFrom until +oracleGames of seed 20261201, so runs can split the work.
     */
    @Test
    fun `depth oracle`() {
        val games = System.getProperty("aiengine.oracleGames")?.toIntOrNull() ?: 0
        Assumptions.assumeTrue(games > 0)
        val from = System.getProperty("aiengine.oracleFrom")?.toIntOrNull() ?: 0
        val rollouts = System.getProperty("aiengine.oracleRollouts")?.toIntOrNull() ?: 12
        val boss = BattleDifficultyProfiles.BOSS
        val profile = BattleTrainerProfile(skillLevel = 2, personality = BattleTrainerProfile.champion().personality, difficulty = boss)
        val budget: (BattleTrainerTier) -> jbro.cobblemon.mcc.betterai.search.LocalLookaheadBudget = {
            LocalLookaheadBudgetPolicy.forTier(it).copy(timeMillis = Long.MAX_VALUE, nodeLimit = 50_000_000)
        }
        val arms = listOf("d1", "d2", "d3", "brain")
        val sums = arms.associateWith { 0.0 }.toMutableMap()
        val diffs = mutableMapOf<String, MutableList<Double>>()
        var positions = 0
        for (definition in LocalSelfPlayMeasurement.definitions(from + games, 20261201, BattleFormat.SINGLE).drop(from)) {
            val contexts = mutableListOf<BattleDecisionContext>()
            val decisions = mutableListOf<LocalScenarioDecisionTrace>()
            LocalTacticalScenarioBattle.run(definition, 20, readTuning(), readTuning(), boss, boss,
                recordedContexts = contexts, recordedDecisions = decisions, lookaheadBudget = budget)
            for ((index, context) in contexts.withIndex()) {
                if (context.candidates.size < 2 || context.candidates.any { it.actionId.startsWith("forced:") }) continue
                val side = BattleSide.valueOf(decisions[index].side)
                val base = LocalBattleActionPolicy.rank(context, null, profile)
                fun top(depth: Int) = LocalRecursiveLookaheadEvaluator.evaluate(base, context,
                    profile.copy(difficulty = boss.copy(lookaheadPlies = depth)), readTuning(), clockMillis = { 0L },
                    budget = budget(boss.tier)).ranked.first().outcome.candidate.actionId
                val choice = mapOf("d1" to top(1), "d2" to top(2), "d3" to top(3), "brain" to decisions[index].actionId)
                if (choice.values.distinct().size < 2) continue
                val value = choice.values.distinct().associateWith { actionId ->
                    (0 until rollouts).map { k ->
                        val report = LocalTacticalScenarioBattle.run(definition, 20, readTuning(), readTuning(), boss, boss,
                            lookaheadBudget = budget, fork = LocalScenarioFork(context.state.turn, side, actionId, 7_000L + k))
                        val lead = report.cycleRemainingHp - report.offenseRemainingHp
                        if (side == BattleSide.ALLY) lead else -lead
                    }.average()
                }
                positions++
                arms.forEach { sums[it] = sums.getValue(it) + value.getValue(choice.getValue(it)) }
                for ((a, b) in listOf("d2" to "d1", "d3" to "d2", "d3" to "d1", "brain" to "d1")) {
                    diffs.getOrPut("$b->$a", ::mutableListOf) += value.getValue(choice.getValue(a)) - value.getValue(choice.getValue(b))
                }
                fun label(id: String) = id.substringAfterLast("move:").substringAfterLast("switch:").substringBefore(":target").take(22)
                val active = context.state.pokemon.filter { it.activeSlot != null }.joinToString(" ") {
                    "${it.side.name.take(1)}:${it.speciesId.substringAfter(':')}@%.0f%%".format(it.hpFraction * 100)
                }
                println("ORACLE ${definition.name} T${context.state.turn} $side $active :: " + arms.joinToString(" | ") {
                    "$it=${label(choice.getValue(it))} %+.3f".format(value.getValue(choice.getValue(it)))
                })
            }
        }
        println("ORACLE positions=$positions mean " + arms.joinToString(" ") { "$it=%+.3f".format(sums.getValue(it) / positions.coerceAtLeast(1)) })
        for ((name, list) in diffs) {
            val mean = list.average()
            val se = kotlin.math.sqrt(list.sumOf { (it - mean) * (it - mean) } / (list.size - 1).coerceAtLeast(1) / list.size)
            println("ORACLE diff $name mean=%+.3f se=%.3f n=${list.size}".format(mean, se))
        }
    }

    /** CURRENT with the switches named in -Daiengine.readFlags (comma separated) turned on. */
    private fun readTuning(): LocalDecisionTuning {
        var tuning = LocalDecisionTuning.CURRENT
        for (flag in System.getProperty("aiengine.readFlags").orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)) {
            tuning = when (flag) {
                "repeats" -> tuning.copy(readOpponentRepeats = true)
                "median" -> tuning.copy(unsearchedTakeMedianAdjustment = true)
                "simultaneous" -> tuning.copy(simultaneousResponseWeight = 1.0)
                "stages" -> tuning.copy(leafPersistentStageValue = 0.2)
                "positional" -> tuning.copy(positionalTurnDeltas = true)
                else -> error("unknown read flag $flag")
            }
        }
        return tuning
    }
}
