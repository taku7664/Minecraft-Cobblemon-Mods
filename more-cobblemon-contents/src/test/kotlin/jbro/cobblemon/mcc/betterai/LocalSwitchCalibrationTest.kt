package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.matchup.IntentKind
import jbro.cobblemon.mcc.betterai.matchup.LocalMatchupScoreCalculator
import jbro.cobblemon.mcc.betterai.matchup.LocalOpponentIntentPredictor
import jbro.cobblemon.mcc.betterai.search.LocalLookaheadBudgetPolicy
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleDifficultyProfiles
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleSide
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test

/**
 * How well the opponent intent predictor foresees switches (singles): in Boss self-play, each side's predicted
 * chance that the opponent in front switches out this turn, against whether it did. Prints the Brier score and a
 * reliability table for the plain and the scored switch values, and how often the predicted Pokemon was the one
 * that came in.
 *
 * Each position with some of the opponent's bench seen also prints a FEAT line (switched, whether the best free
 * matchup was the one that came in, then [LocalOpponentIntentPredictor.SwitchFeatures]); tools/fit_switch_model.py
 * fits the switch model on them.
 *
 * Opt-in: -Daiengine.calibrationGames=<n> [-Daiengine.calibrationSeed=<seed>].
 */
class LocalSwitchCalibrationTest {
    private class Sample(val predicted: Double, val switched: Boolean, val predictedIncoming: String?, val incoming: String?, val seenBench: Boolean)

    @Test
    fun `switch prediction calibration`() {
        val games = System.getProperty("aiengine.calibrationGames")?.toIntOrNull() ?: 0
        Assumptions.assumeTrue(games > 0)
        val boss = BattleDifficultyProfiles.BOSS
        val budget = { tier: jbro.cobblemon.mcc.internal.ai.BattleTrainerTier ->
            LocalLookaheadBudgetPolicy.forTier(tier).copy(timeMillis = Long.MAX_VALUE, nodeLimit = 50_000_000)
        }
        val tuning = LocalDecisionTuning.CURRENT
        val samples = mapOf(false to mutableListOf<Sample>(), true to mutableListOf())
        for (definition in LocalSelfPlayMeasurement.definitions(games, System.getProperty("aiengine.calibrationSeed")?.toIntOrNull() ?: 20261215, BattleFormat.SINGLE)) {
            val contexts = mutableListOf<BattleDecisionContext>()
            val decisions = mutableListOf<LocalScenarioDecisionTrace>()
            LocalTacticalScenarioBattle.run(definition, 30, tuning, tuning, boss, boss,
                recordedContexts = contexts, recordedDecisions = decisions, lookaheadBudget = budget)
            val chosen = contexts.indices.filter { index ->
                val context = contexts[index]
                context.candidates.size >= 2 && context.candidates.none { it.actionId.startsWith("forced:") } &&
                    context.candidates.any { it.kind == BattleActionKind.USE_MOVE }
            }
            for (index in chosen) {
                val context = contexts[index]
                val side = decisions[index].side
                // The other side's decision on the same turn, a free choice between moving and switching.
                val reply = chosen.firstOrNull { decisions[it].side != side && contexts[it].state.turn == context.state.turn } ?: continue
                val replyContext = contexts[reply]
                val replyAction = replyContext.candidates.first { it.actionId == decisions[reply].actionId }
                val switchedIn = replyAction.switchPokemonId?.takeIf { replyAction.kind == BattleActionKind.SWITCH }
                val calculated = PublicBattleTacticalCalculator.calculate(context)
                val scores = LocalMatchupScoreCalculator.calculate(calculated)
                // One line per position for fitting the switch model offline.
                val front = context.state.pokemon.singleOrNull { it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted }
                val features = LocalOpponentIntentPredictor.switchFeatures(calculated, scores)[front?.battlePokemonId]
                features?.let { f ->
                    println("FEAT,${if (switchedIn != null) 1 else 0},${if (switchedIn != null && switchedIn == f.freeIncoming) 1 else 0}," +
                        listOf(f.threatened, f.stay, f.bestGain, f.preserve, f.sweep, f.stop, f.bestAttack, f.hp, f.bench.toDouble(), f.freeGain)
                            .joinToString(",") { "%.4f".format(it) })
                }
                fun name(id: java.util.UUID?) = id?.let { found -> context.state.pokemon.firstOrNull { it.battlePokemonId == found }?.speciesId }
                for (scored in listOf(false, true)) {
                    val intent = LocalOpponentIntentPredictor.predict(calculated, scores, scored).singleOrNull() ?: continue
                    val switches = intent.options.filter { it.kind == IntentKind.SWITCH }
                    samples.getValue(scored) += Sample(
                        predicted = switches.sumOf { it.probability },
                        switched = switchedIn != null,
                        predictedIncoming = name(switches.maxByOrNull { it.probability }?.switchInId),
                        incoming = name(switchedIn),
                        seenBench = features != null,
                    )
                }
            }
        }
        // Every position, and those with some of the opponent's bench seen: a switch into one not yet seen cannot be
        // priced, so the model leaves it out.
        for ((label, list) in samples.flatMap { (scored, all) -> listOf("scored=$scored" to all, "scored=$scored seen" to all.filter { it.seenBench }) }) {
            val brier = list.sumOf { (it.predicted - if (it.switched) 1.0 else 0.0).let { e -> e * e } } / list.size.coerceAtLeast(1)
            val baseRate = list.count { it.switched }.toDouble() / list.size.coerceAtLeast(1)
            val switchedCases = list.filter { it.switched }
            val rightIncoming = switchedCases.count { it.predictedIncoming == it.incoming }
            println("CALIBRATION $label n=${list.size} switched=%.3f brier=%.4f (constant %.4f) predicted-mean=%.3f incoming-right=$rightIncoming/${switchedCases.size}".format(
                baseRate, brier, baseRate * (1 - baseRate), list.sumOf { it.predicted } / list.size.coerceAtLeast(1)))
            val bins = list.groupBy { (it.predicted * 10).toInt().coerceAtMost(9) }.toSortedMap()
            for ((bin, members) in bins) {
                println("   bin %.1f-%.1f n=%4d predicted=%.3f actual=%.3f".format(bin / 10.0, (bin + 1) / 10.0, members.size,
                    members.sumOf { it.predicted } / members.size, members.count { it.switched }.toDouble() / members.size))
            }
        }
    }
}
