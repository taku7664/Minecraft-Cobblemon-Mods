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
 * Opt-in: -Daiengine.calibrationGames=<n> [-Daiengine.calibrationSeed=<seed>] [-Daiengine.calibrationFrom=<skip>]
 * [-Daiengine.calibrationFormat=double]. In doubles each opposing slot is a sample of its own.
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
        // "plain": the one softmax; "scored": what the predictor ships for the format; "model": the fitted switch
        // model read straight from the features (in doubles, the singles weights carried over).
        val samples = linkedMapOf("plain" to mutableListOf<Sample>(), "scored" to mutableListOf(), "model" to mutableListOf())
        val format = if (System.getProperty("aiengine.calibrationFormat") == "double") BattleFormat.DOUBLE else BattleFormat.SINGLE
        val from = System.getProperty("aiengine.calibrationFrom")?.toIntOrNull() ?: 0
        val seed = System.getProperty("aiengine.calibrationSeed")?.toIntOrNull() ?: 20261215
        for (definition in LocalSelfPlayMeasurement.definitions(from + games, seed, format).drop(from)) {
            val contexts = mutableListOf<BattleDecisionContext>()
            val decisions = mutableListOf<LocalScenarioDecisionTrace>()
            LocalTacticalScenarioBattle.run(definition, 30, tuning, tuning, boss, boss,
                recordedContexts = contexts, recordedDecisions = decisions, lookaheadBudget = budget)
            val chosen = contexts.indices.filter { index ->
                val context = contexts[index]
                context.candidates.size >= 2 && context.candidates.none { it.actionId.startsWith("forced:") } &&
                    context.candidates.any { candidate -> parts(candidate).any { it.kind == BattleActionKind.USE_MOVE } }
            }
            for (index in chosen) {
                val context = contexts[index]
                val side = decisions[index].side
                // The other side's decision on the same turn, a free choice between moving and switching.
                val reply = chosen.firstOrNull { decisions[it].side != side && contexts[it].state.turn == context.state.turn } ?: continue
                val replyAction = contexts[reply].candidates.first { it.actionId == decisions[reply].actionId }
                val calculated = PublicBattleTacticalCalculator.calculate(context)
                val scores = LocalMatchupScoreCalculator.calculate(calculated)
                val allFeatures = LocalOpponentIntentPredictor.switchFeatures(calculated, scores)
                val intents = mapOf(
                    "plain" to LocalOpponentIntentPredictor.predict(calculated, scores, false),
                    "scored" to LocalOpponentIntentPredictor.predict(calculated, scores, true),
                )
                fun name(id: java.util.UUID?) = id?.let { found -> context.state.pokemon.firstOrNull { it.battlePokemonId == found }?.speciesId }
                // Each opposing Pokemon in front, against what its own slot did in the reply.
                for (front in context.state.pokemon.filter { it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }) {
                    val slotAction = parts(replyAction).firstOrNull { it.actorSlot == front.activeSlot } ?: continue
                    val switchedIn = slotAction.switchPokemonId?.takeIf { slotAction.kind == BattleActionKind.SWITCH }
                    val features = allFeatures[front.battlePokemonId]
                    // One line per position for fitting the switch model offline.
                    features?.let { f ->
                        println("FEAT,${if (switchedIn != null) 1 else 0},${if (switchedIn != null && switchedIn == f.freeIncoming) 1 else 0}," +
                            listOf(f.threatened, f.stay, f.bestGain, f.preserve, f.sweep, f.stop, f.bestAttack, f.hp, f.bench.toDouble(), f.freeGain)
                                .joinToString(",") { "%.4f".format(it) })
                    }
                    for ((kind, list) in intents) {
                        val intent = list.firstOrNull { it.pokemonId == front.battlePokemonId } ?: continue
                        val switches = intent.options.filter { it.kind == IntentKind.SWITCH }
                        samples.getValue(kind) += Sample(
                            predicted = switches.sumOf { it.probability },
                            switched = switchedIn != null,
                            predictedIncoming = name(switches.maxByOrNull { it.probability }?.switchInId),
                            incoming = name(switchedIn),
                            seenBench = features != null,
                        )
                    }
                    samples.getValue("model") += Sample(
                        predicted = features?.let(LocalOpponentIntentPredictor::switchChance) ?: 0.0,
                        switched = switchedIn != null,
                        predictedIncoming = name(features?.freeIncoming),
                        incoming = name(switchedIn),
                        seenBench = features != null,
                    )
                }
            }
        }
        // Every position, and those with some of the opponent's bench seen: a switch into one not yet seen cannot be
        // priced, so the model leaves it out.
        for ((label, list) in samples.flatMap { (kind, all) -> listOf(kind to all, "$kind seen" to all.filter { it.seenBench }) }) {
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

    private fun parts(candidate: jbro.cobblemon.mcc.internal.ai.BattleActionCandidate) =
        if (candidate.kind == BattleActionKind.COMPOSITE) candidate.componentActions else listOf(candidate)
}
