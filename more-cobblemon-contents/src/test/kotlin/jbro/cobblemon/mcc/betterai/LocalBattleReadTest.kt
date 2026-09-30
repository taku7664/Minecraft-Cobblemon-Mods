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
        // Optional focus: "selfplay-5:4:OPPONENT,selfplay-9:9:OPPONENT" plays out only those decisions.
        val focus = System.getProperty("aiengine.oracleFocus")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
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
            if (focus != null && focus.none { it.startsWith("${definition.name}:") }) continue
            val contexts = mutableListOf<BattleDecisionContext>()
            val decisions = mutableListOf<LocalScenarioDecisionTrace>()
            LocalTacticalScenarioBattle.run(definition, 20, readTuning(), readTuning(), boss, boss,
                recordedContexts = contexts, recordedDecisions = decisions, lookaheadBudget = budget)
            for ((index, context) in contexts.withIndex()) {
                if (context.candidates.size < 2 || context.candidates.any { it.actionId.startsWith("forced:") }) continue
                val side = BattleSide.valueOf(decisions[index].side)
                if (focus != null && "${definition.name}:${context.state.turn}:$side" !in focus) continue
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

    /**
     * Whether the knockout credit misleads the root: at each self-play decision (singles) where the Boss chose a move
     * the root credits with a knockout, that move and the best-ranked candidate without one are both played out
     * (-Daiengine.koOracleRollouts, 8) and compared by the final HP lead, with how often the opponent switched out
     * of the credited move on that turn. Opt-in: -Daiengine.koOracleGames=<n>.
     */
    @Test
    fun `knockout oracle`() {
        val games = System.getProperty("aiengine.koOracleGames")?.toIntOrNull() ?: 0
        Assumptions.assumeTrue(games > 0)
        val rollouts = System.getProperty("aiengine.koOracleRollouts")?.toIntOrNull() ?: 8
        val boss = BattleDifficultyProfiles.BOSS
        val profile = BattleTrainerProfile(skillLevel = 2, personality = BattleTrainerProfile.champion().personality, difficulty = boss)
        val budget: (BattleTrainerTier) -> jbro.cobblemon.mcc.betterai.search.LocalLookaheadBudget = {
            LocalLookaheadBudgetPolicy.forTier(it).copy(timeMillis = Long.MAX_VALUE, nodeLimit = 50_000_000)
        }
        val diffs = mutableListOf<Double>()
        var switchedOut = 0
        var replies = 0
        val from = System.getProperty("aiengine.koOracleFrom")?.toIntOrNull() ?: 0
        for (definition in LocalSelfPlayMeasurement.definitions(from + games, 20261301, BattleFormat.SINGLE).drop(from)) {
            val contexts = mutableListOf<BattleDecisionContext>()
            val decisions = mutableListOf<LocalScenarioDecisionTrace>()
            LocalTacticalScenarioBattle.run(definition, 20, readTuning(), readTuning(), boss, boss,
                recordedContexts = contexts, recordedDecisions = decisions, lookaheadBudget = budget)
            for ((index, context) in contexts.withIndex()) {
                if (context.candidates.size < 2 || context.candidates.any { it.actionId.startsWith("forced:") }) continue
                val side = BattleSide.valueOf(decisions[index].side)
                // The knockout facts come from the tactical calculation, as in the brain.
                val calculated = jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator.calculate(context)
                val ranked = LocalBattleActionPolicy.rank(calculated, null, profile, readTuning())
                val pick = ranked.firstOrNull { it.outcome.candidate.actionId == decisions[index].actionId } ?: continue
                if (pick.outcome.knockoutUtility <= 0.0) continue
                val alternative = ranked.firstOrNull {
                    it.outcome.knockoutUtility <= 0.0 && it.outcome.candidate.actionId != pick.outcome.candidate.actionId &&
                        it.outcome.candidate.kind in setOf(jbro.cobblemon.mcc.internal.ai.BattleActionKind.USE_MOVE, jbro.cobblemon.mcc.internal.ai.BattleActionKind.SWITCH)
                } ?: continue
                fun playOut(actionId: String, onReply: (String) -> Unit = {}) = (0 until rollouts).map { k ->
                    val report = LocalTacticalScenarioBattle.run(definition, 20, readTuning(), readTuning(), boss, boss,
                        lookaheadBudget = budget, fork = LocalScenarioFork(context.state.turn, side, actionId, 11_000L + k))
                    report.turns.firstOrNull { it.turn == context.state.turn }?.let { onReply(if (side == BattleSide.ALLY) it.offenseActual else it.cycleActual) }
                    val lead = report.cycleRemainingHp - report.offenseRemainingHp
                    if (side == BattleSide.ALLY) lead else -lead
                }.average()
                // The setup gate's verdict on the alternative, when it is a stat raise: why the Boss could not have it.
                val gate = if (!jbro.cobblemon.mcc.betterai.matchup.LocalSetupGate.raisesOwnStats(alternative.outcome.candidate)) "" else {
                    val scores = jbro.cobblemon.mcc.betterai.matchup.LocalMatchupScoreCalculator.calculate(calculated)
                    val verdict = jbro.cobblemon.mcc.betterai.matchup.LocalSetupGate.evaluate(alternative.outcome.candidate, calculated, scores)
                    val sweep = scores.sweeps[calculated.state.pokemon.first { it.side == BattleSide.ALLY && it.activeSlot == alternative.outcome.candidate.actorSlot }.battlePokemonId]
                    " gate=${verdict?.failures} sweep=%.2f/%.2f/%s".format(sweep?.naturalSweep ?: -1.0, sweep?.boostedSweep ?: -1.0,
                        sweep?.windowSweep?.let { "%.2f".format(it) })
                }
                var pickSwitched = 0
                val pickValue = playOut(pick.outcome.candidate.actionId) { reply -> replies++; if (reply.startsWith("교체")) { switchedOut++; pickSwitched++ } }
                val alternativeValue = playOut(alternative.outcome.candidate.actionId)
                diffs += alternativeValue - pickValue
                fun label(id: String) = id.substringAfterLast("move:").substringAfterLast("switch:").substringBefore(":target").take(22)
                val active = context.state.pokemon.filter { it.activeSlot != null }.joinToString(" ") {
                    "${it.side.name.take(1)}:${it.speciesId.substringAfter(':')}@%.0f%%".format(it.hpFraction * 100)
                }
                println("KO-ORACLE ${definition.name} T${context.state.turn} $side $active :: pick=${label(pick.outcome.candidate.actionId)} " +
                    "ko=%.0f cmp=%.0f %+.3f (switched out $pickSwitched/$rollouts) | alt=${label(alternative.outcome.candidate.actionId)} cmp=%.0f %+.3f".format(
                        pick.outcome.knockoutUtility, pick.comparisonValue, pickValue, alternative.comparisonValue, alternativeValue) + gate)
            }
        }
        val mean = diffs.average()
        val se = kotlin.math.sqrt(diffs.sumOf { (it - mean) * (it - mean) } / (diffs.size - 1).coerceAtLeast(1) / diffs.size.coerceAtLeast(1))
        println("KO-ORACLE positions=${diffs.size} alternative-minus-pick mean=%+.3f se=%.3f alternative-better=${diffs.count { it > 0.0 }} switched-out=$switchedOut/$replies".format(mean, se))
    }

    /**
     * How often a losing recovery loop shows up (singles self-play) and what the penalty does to the root: each
     * decision with a heal on offer and a streak, the heal's rank value with and without the penalty and the top
     * candidate. Opt-in: -Daiengine.loopCensusGames=<n>.
     */
    @Test
    fun `recovery loop census`() {
        val games = System.getProperty("aiengine.loopCensusGames")?.toIntOrNull() ?: 0
        Assumptions.assumeTrue(games > 0)
        val boss = BattleDifficultyProfiles.BOSS
        val profile = BattleTrainerProfile(skillLevel = 2, personality = BattleTrainerProfile.champion().personality, difficulty = boss)
        val budget: (BattleTrainerTier) -> jbro.cobblemon.mcc.betterai.search.LocalLookaheadBudget = {
            LocalLookaheadBudgetPolicy.forTier(it).copy(timeMillis = Long.MAX_VALUE, nodeLimit = 50_000_000)
        }
        var healDecisions = 0
        var healChosen = 0
        var streaks = 0
        for (definition in LocalSelfPlayMeasurement.definitions(games, 20261401, BattleFormat.SINGLE)) {
            val contexts = mutableListOf<BattleDecisionContext>()
            val decisions = mutableListOf<LocalScenarioDecisionTrace>()
            LocalTacticalScenarioBattle.run(definition, 30, readTuning(), readTuning(), boss, boss,
                recordedContexts = contexts, recordedDecisions = decisions, lookaheadBudget = budget)
            for ((index, context) in contexts.withIndex()) {
                val heal = context.candidates.firstOrNull { c ->
                    c.moveDetails?.effects?.effects.orEmpty().any {
                        it.kind == jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind.HEAL_FRACTION &&
                            it.target == jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget.USER
                    }
                } ?: continue
                healDecisions++
                if (decisions[index].actionId == heal.actionId) healChosen++
                val user = context.state.pokemon.first { it.side == BattleSide.ALLY && it.activeSlot == heal.actorSlot }
                val streak = jbro.cobblemon.mcc.betterai.evaluation.LocalRecoveryLoop.failedStreak(user.battlePokemonId, context)
                if (streak == 0) continue
                streaks++
                val calculated = jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator.calculate(context)
                fun rank(tuning: LocalDecisionTuning) = LocalBattleActionPolicy.rank(calculated, null, profile, tuning)
                val on = rank(readTuning())
                val off = rank(readTuning().copy(recoveryLoopPenalty = 0.0))
                fun value(ranked: List<jbro.cobblemon.mcc.betterai.policy.LocalBattleActionRank>) =
                    ranked.first { it.outcome.candidate.actionId == heal.actionId }.comparisonValue
                fun label(id: String) = id.substringAfterLast("move:").substringBefore(":target").take(22)
                println("LOOP ${definition.name} T${context.state.turn} ${user.speciesId.substringAfter(':')}@%.0f%% streak=$streak heal on=%.1f off=%.1f top on=${label(on.first().outcome.candidate.actionId)} off=${label(off.first().outcome.candidate.actionId)} chose=${label(decisions[index].actionId)}".format(
                    user.hpFraction * 100, value(on), value(off)))
            }
        }
        println("LOOP census heal-decisions=$healDecisions heal-chosen=$healChosen with-streak=$streaks")
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
                "recovery" -> tuning.copy(matchupRecovery = true)
                "setupCredit" -> tuning.copy(setupSweepCredit = 1.0)
                else -> error("unknown read flag $flag")
            }
        }
        return tuning
    }
}
