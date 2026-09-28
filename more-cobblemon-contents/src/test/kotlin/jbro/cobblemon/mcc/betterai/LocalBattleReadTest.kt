package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.search.LocalLookaheadBudgetPolicy
import jbro.cobblemon.mcc.internal.ai.BattleDifficultyProfiles
import jbro.cobblemon.mcc.internal.ai.BattleFormat
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
            val report = LocalTacticalScenarioBattle.run(definition, 20, LocalDecisionTuning.CURRENT, LocalDecisionTuning.CURRENT,
                boss, boss, lookaheadBudget = { LocalLookaheadBudgetPolicy.forTier(it).copy(timeMillis = Long.MAX_VALUE, nodeLimit = 50_000_000) })
            println("GAME ${definition.name} cycle=${definition.cycleSetIds} offense=${definition.offenseSetIds} winner=${report.winner}")
            for (turn in report.turns) {
                println("T${turn.turn} A:${turn.cycleActual} B:${turn.offenseActual} => ${turn.result}")
                println("   A top: ${turn.cycleTop}")
                println("   B top: ${turn.offenseTop}")
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
        val definition = LocalSelfPlayMeasurement.definitions(game, 20260930, format)[game - 1]
        val contexts = mutableListOf<BattleDecisionContext>()
        LocalTacticalScenarioBattle.run(definition, turn, LocalDecisionTuning.CURRENT, LocalDecisionTuning.CURRENT, boss, boss,
            recordedContexts = contexts,
            lookaheadBudget = { LocalLookaheadBudgetPolicy.forTier(it).copy(timeMillis = Long.MAX_VALUE, nodeLimit = 50_000_000) })
        // Contexts alternate cycle then offense each turn; replacements add more. Take the last ones at the turn.
        val atTurn = contexts.filter { it.state.turn == turn }
        val context = if (side == "cycle") atTurn.first() else atTurn.last()
        val profile = BattleTrainerProfile(skillLevel = 2, personality = BattleTrainerProfile.champion().personality, difficulty = boss)
        val base = LocalBattleActionPolicy.rank(context, null, profile)
        val result = LocalRecursiveLookaheadEvaluator.evaluate(base, context, profile, LocalDecisionTuning.CURRENT, clockMillis = { 0L },
            budget = LocalLookaheadBudgetPolicy.forTier(boss.tier).copy(timeMillis = Long.MAX_VALUE, nodeLimit = 50_000_000))
        println("PROBE depth=${result.depthCompleted} nodes=${result.nodesVisited}")
        context.state.pokemon.forEach { println("PROBE mon ${it.side} ${it.speciesId} slot=${it.activeSlot} hp=%.2f st=${it.statusId} stages=${it.statStages}".format(it.hpFraction)) }
        for (rank in result.ranked) {
            val b = base.first { it.outcome.candidate.actionId == rank.outcome.candidate.actionId }
            println("PROBE ${rank.outcome.candidate.actionId} cmp=%.1f look=%.1f base=%.1f exec=%.2f worstHp=%.2f".format(
                rank.comparisonValue, rank.lookaheadUtility, b.comparisonValue, rank.executionProbability, rank.worstResponseHpRetention))
        }
    }
}
