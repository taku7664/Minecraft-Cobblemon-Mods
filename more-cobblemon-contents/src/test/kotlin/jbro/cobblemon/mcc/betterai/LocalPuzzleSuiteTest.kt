package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.search.LocalLookaheadBudgetPolicy
import jbro.cobblemon.mcc.internal.ai.BattleDifficultyProfiles
import jbro.cobblemon.mcc.internal.ai.BattleSide
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test

/**
 * Singles positions with one clear answer: a defensive switch, a setup window or restraint, a priority finish, a
 * status move at the right target, healing at the right time. Each is a real rental set in a fixed position, both
 * sides' sets revealed, so a miss is a judgement and not missing information. The first set of each side is in
 * front; the Boss decides once.
 *
 * Opt-in: -Daiengine.puzzles=true, with -Daiengine.puzzleFlags naming tuning switches (see [tuning]).
 * -Daiengine.puzzleRollouts=<n> also plays each puzzle out from every accepted answer and from the Boss's choice.
 */
class LocalPuzzleSuiteTest {
    private class Puzzle(
        val name: String,
        val category: String,
        val ally: List<String>,
        val opponent: List<String>,
        val start: LocalScenarioStart = LocalScenarioStart(),
        /** Labels that pass: a move id, or "교체→<species>" (a prefix is enough). */
        val accepted: Set<String> = emptySet(),
        /** When set, every label but these passes. */
        val rejected: Set<String> = emptySet(),
    ) {
        fun passes(label: String): Boolean =
            if (rejected.isNotEmpty()) rejected.none { label.startsWith(it) } else accepted.any { label.startsWith(it) }
    }

    @Test
    fun `puzzle suite`() {
        Assumptions.assumeTrue(System.getProperty("aiengine.puzzles") == "true")
        val boss = BattleDifficultyProfiles.BOSS
        val tuning = tuning()
        val budget = { tier: jbro.cobblemon.mcc.internal.ai.BattleTrainerTier ->
            LocalLookaheadBudgetPolicy.forTier(tier).copy(timeMillis = Long.MAX_VALUE, nodeLimit = 50_000_000)
        }
        var passed = 0
        val byCategory = linkedMapOf<String, Pair<Int, Int>>()
        for ((index, puzzle) in PUZZLES.withIndex()) {
            val only = System.getProperty("aiengine.puzzleOnly")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)
            if (only != null && puzzle.name !in only) continue
            val definition = LocalTacticalScenarioDefinition(puzzle.name, puzzle.ally, puzzle.opponent, 30_000 + index)
            val contexts = mutableListOf<jbro.cobblemon.mcc.internal.ai.BattleDecisionContext>()
            val report = LocalTacticalScenarioBattle.run(definition, 1, tuning, tuning, boss, boss,
                recordedContexts = contexts, lookaheadBudget = budget, start = puzzle.start)
            val turn = report.turns.single()
            if (System.getProperty("aiengine.puzzleProbe") == puzzle.name) probe(contexts.first())
            val rollouts = System.getProperty("aiengine.puzzleRollouts")?.toIntOrNull() ?: 0
            if (rollouts > 0) {
                // Every candidate played out from the puzzle, both sides the Boss: the mean final HP lead it leads to.
                // What the opponent answered on the first turn, per candidate: the read the position turns on.
                val replies = linkedMapOf<String, MutableMap<String, Int>>()
                val samples = contexts.first().candidates.associate { candidate ->
                    candidate.actionId to (0 until rollouts).map { k ->
                        val played = LocalTacticalScenarioBattle.run(definition, 30, tuning, tuning, boss, boss, lookaheadBudget = budget,
                            start = puzzle.start, fork = LocalScenarioFork(1, BattleSide.ALLY, candidate.actionId, 9_000L + k))
                        played.turns.firstOrNull()?.let { replies.getOrPut(candidate.actionId, ::linkedMapOf).merge(it.offenseActual, 1, Int::plus) }
                        if (k < (System.getProperty("aiengine.puzzleTrace")?.toIntOrNull() ?: 0)) {
                            println("   trace ${candidate.actionId.takeLast(30)} #$k lead=%+.2f: ".format(played.cycleRemainingHp - played.offenseRemainingHp) +
                                played.turns.joinToString(" / ") { "T${it.turn} ${it.cycleActual} vs ${it.offenseActual} ${it.result}" })
                        }
                        played.cycleRemainingHp - played.offenseRemainingHp
                    }
                }
                val values = samples.mapValues { it.value.average() }
                fun error(actionId: String): Double {
                    val leads = samples.getValue(actionId)
                    val mean = leads.average()
                    return kotlin.math.sqrt(leads.sumOf { (it - mean) * (it - mean) } / (leads.size - 1).coerceAtLeast(1) / leads.size)
                }
                val state = contexts.first().state
                fun label(actionId: String): String {
                    val candidate = contexts.first().candidates.first { it.actionId == actionId }
                    val incoming = candidate.switchPokemonId?.let { id -> state.pokemon.firstOrNull { it.battlePokemonId == id } }
                    return incoming?.let { "교체→" + it.speciesId.substringAfter(':') } ?: candidate.moveId?.substringAfter(':') ?: actionId.takeLast(24)
                }
                println("   rollouts: " + values.entries.sortedByDescending { it.value }.joinToString(" | ") {
                    "${label(it.key)} %+.2f±%.2f".format(it.value, error(it.key))
                })
                replies.forEach { (actionId, counts) -> println("   replies to ${label(actionId)}: $counts") }
            }
            val ok = puzzle.passes(turn.cycleActual)
            if (ok) passed++
            val (p, n) = byCategory[puzzle.category] ?: (0 to 0)
            byCategory[puzzle.category] = (p + if (ok) 1 else 0) to (n + 1)
            println("PUZZLE ${if (ok) "PASS" else "FAIL"} ${puzzle.name} [${puzzle.category}] chose=${turn.cycleActual} " +
                "want=${if (puzzle.rejected.isNotEmpty()) "not ${puzzle.rejected}" else puzzle.accepted.toString()}")
            println("   top: ${turn.cycleTop}")
        }
        println("PUZZLE summary $passed/${PUZZLES.size} " + byCategory.entries.joinToString(" ") { "${it.key}=${it.value.first}/${it.value.second}" })
    }

    /** The setup gate and status move verdicts of one puzzle's decision. */
    private fun probe(context: jbro.cobblemon.mcc.internal.ai.BattleDecisionContext) {
        val calculated = jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator.calculate(context)
        val scores = jbro.cobblemon.mcc.betterai.matchup.LocalMatchupScoreCalculator.calculate(calculated)
        for (candidate in calculated.candidates) {
            val gate = jbro.cobblemon.mcc.betterai.matchup.LocalSetupGate.evaluate(candidate, calculated, scores)
            val wasted = jbro.cobblemon.mcc.betterai.matchup.LocalStatusMoveTriage.judgeable(candidate, calculated) &&
                jbro.cobblemon.mcc.betterai.matchup.LocalStatusMoveTriage.wasted(candidate, calculated, scores)
            if (gate != null || wasted) println("   probe ${candidate.actionId.takeLast(40)} gate=${gate?.failures} wasted=$wasted")
        }
        for (scored in listOf(false, true)) {
            jbro.cobblemon.mcc.betterai.matchup.LocalOpponentIntentPredictor.predict(calculated, scores, scored).forEach { intent ->
                println("   intent scored=$scored " + intent.options.take(6).joinToString(" | ") { option ->
                    val name = option.moveId ?: ("교체→" + calculated.state.pokemon.first { it.battlePokemonId == option.switchInId }.speciesId.substringAfter(':'))
                    "$name v=%.2f p=%.2f".format(option.value, option.probability)
                })
            }
        }
        calculated.state.pokemon.filter { it.side == BattleSide.ALLY && it.activeSlot != null }.forEach { ally ->
            calculated.state.pokemon.filter { it.side == BattleSide.OPPONENT && it.activeSlot != null }.forEach { foe ->
                scores.statusMoves(ally.battlePokemonId, foe.battlePokemonId).forEach {
                    println("   status ${it.moveId} score=%.3f before=%.3f after=%.3f survives=%.2f".format(it.score, it.before, it.afterLanding, it.survivesTurn))
                }
                println("   matchup ${scores.pokemon(ally.battlePokemonId, foe.battlePokemonId)?.let { "win=%.2f score=%.2f".format(it.winProbability, it.score) }}")
            }
            scores.switchIns.filter { it.replacedId == ally.battlePokemonId }.forEach {
                val name = calculated.state.pokemon.first { p -> p.battlePokemonId == it.incomingId }.speciesId
                println("   switchIn $name move=${it.predictedMoveId} survival=%.2f hpAfter=%.2f worst=${it.worstMoveId}/%.2f score=%.2f".format(
                    it.predictedSurvival, it.hpAfterEntry, it.worstSurvival, it.score))
            }
            scores.sweeps[ally.battlePokemonId]?.let { println("   sweep score=%.2f natural=%.2f boosted=%.2f byOpp=${it.boostedByOpponent.values.map { v -> "%.2f".format(v) }}".format(it.score, it.naturalSweep, it.boostedSweep)) }
        }
    }

    /** CURRENT with -Daiengine.puzzleFlags: comma-separated names, or name=value for the weights. */
    private fun tuning(): LocalDecisionTuning {
        var tuning = LocalDecisionTuning.CURRENT
        for (flag in System.getProperty("aiengine.puzzleFlags").orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)) {
            val name = flag.substringBefore('=')
            val value = flag.substringAfter('=', "").toDoubleOrNull()
            tuning = when (name) {
                "plainSwitch" -> tuning.copy(scoredSwitchIntent = false)
                "recovery" -> tuning.copy(matchupRecovery = true)
                "noLoop" -> tuning.copy(recoveryLoopPenalty = 0.0)
                "setupCredit" -> tuning.copy(setupSweepCredit = value ?: 1.0)
                "predicted" -> tuning.copy(predictedSwitchShare = value ?: 1.0)
                "repeats" -> tuning.copy(readOpponentRepeats = true)
                "positional" -> tuning.copy(positionalTurnDeltas = true)
                "leafTeam" -> tuning.copy(leafMatchupTeamWeight = value ?: 1.0)
                "leafField" -> tuning.copy(leafMatchupFieldWeight = value ?: 0.5)
                "authority" -> tuning.copy(searchAuthority = value ?: 1.0)
                else -> error("unknown puzzle flag $flag")
            }
        }
        return tuning
    }

    private companion object {
        val A = BattleSide.ALLY
        val O = BattleSide.OPPONENT
        fun hp(vararg values: Pair<Pair<BattleSide, Int>, Double>) = LocalScenarioStart(hp = values.toMap())

        val PUZZLES = listOf(
            // Defensive switches: the Pokemon in front is knocked out by the obvious move, one in the back takes it.
            Puzzle("heatran-vs-garchomp", "switch_defend",
                listOf("heatran_preset_1", "skarmory_preset_2", "toxapex_preset_1"),
                listOf("garchomp_preset_1", "blissey_preset_1", "corviknight_preset_1"),
                accepted = setOf("교체→skarmory")),
            Puzzle("gyarados-vs-rotomwash", "switch_defend",
                listOf("gyarados_preset_1", "landorustherian_preset_1", "blissey_preset_1"),
                listOf("rotomwash_preset_1", "toxapex_preset_2", "garchomp_preset_2"),
                // Blissey walls both of its attacks too.
            accepted = setOf("교체→landorus", "교체→blissey")),
            Puzzle("scizor-vs-heatran", "switch_defend",
                listOf("scizor_preset_1", "swampert_preset_1", "blissey_preset_1"),
                listOf("heatran_preset_1", "garchomp_preset_2", "toxapex_preset_2"),
                accepted = setOf("교체→swampert", "교체→blissey")),
            // Levitate takes the Earthquake meant for Heatran and threatens a quadruple Thunderbolt back.
            Puzzle("heatran-vs-boosted-gyarados", "switch_stop",
                listOf("heatran_preset_1", "rotomwash_preset_4", "blissey_preset_1"),
                listOf("gyarados_preset_1", "toxapex_preset_1", "garchomp_preset_2"),
                start = LocalScenarioStart(stages = mapOf((O to 0) to mapOf("attack" to 1, "speed" to 1))),
                accepted = setOf("교체→rotom")),
            // Switch prediction: the one in front falls to the obvious move and its trainer has the answer behind it.
            // Played out, Heatran stays often enough that the Earthquake beats the read (+1.67 against +0.27).
            Puzzle("garchomp-reads-skarmory", "predict_restraint",
                listOf("garchomp_preset_1", "clefable_preset_1", "toxapex_preset_2"),
                listOf("heatran_preset_1", "skarmory_preset_1", "blissey_preset_1"),
                accepted = setOf("earthquake")),
            // The read is Hydro Pump into the Garchomp behind, but played out Will-O-Wisp wins by a distance (+2.47
            // against +1.56 for Thunderbolt and +0.98 for Hydro Pump): the burn cripples either physical attacker.
            Puzzle("rotom-burns-either", "status",
                listOf("rotomwash_preset_4", "clefable_preset_1", "skarmory_preset_2"),
                listOf("gyarados_preset_1", "garchomp_preset_1", "blissey_preset_1"),
                accepted = setOf("willowisp")),
            // Setup: a free window, and a window that is not one.
            // Not a window: Seismic Toss ignores the Special Defense boost and Toxapex behind hazes the rest away.
            // Played out, every attack beats Quiver Dance.
            Puzzle("volcarona-vs-blissey", "setup_restraint",
                listOf("volcarona_preset_1", "salamence_preset_1", "azumarill_preset_2"),
                listOf("blissey_preset_1", "toxapex_preset_1", "corviknight_preset_1"),
                rejected = setOf("quiverdance")),
            Puzzle("low-volcarona-vs-tyranitar", "setup_restraint",
                listOf("volcarona_preset_1", "swampert_preset_2", "skarmory_preset_2"),
                listOf("tyranitar_preset_2", "blissey_preset_1", "gengar_preset_1"),
                start = hp((A to 0) to 0.4),
                rejected = setOf("quiverdance")),
            Puzzle("garchomp-takes-the-knockout", "knockout",
                listOf("garchomp_preset_2", "skarmory_preset_2", "toxapex_preset_1"),
                listOf("heatran_preset_1", "blissey_preset_1", "gyarados_preset_1"),
                start = hp((O to 0) to 0.4),
                // Played out, Toxapex (+1.59) beats the Earthquake (+0.86): Heatran's trainer keeps it in to die, and
                // Gyarados walks in free on the knockout. Either passes; anything else is a miss.
                accepted = setOf("earthquake", "교체→toxapex")),
            // Priority finishes against a faster opponent.
            Puzzle("scizor-bullet-punch-finish", "priority",
                listOf("scizor_preset_1", "toxapex_preset_2", "clefable_preset_1"),
                listOf("dragapult_preset_1", "garchomp_preset_1", "blissey_preset_1"),
                start = hp((A to 0) to 0.3, (O to 0) to 0.1),
                accepted = setOf("bulletpunch")),
            Puzzle("mamoswine-ice-shard-finish", "priority",
                listOf("mamoswine_preset_1", "heatran_preset_2", "toxapex_preset_1"),
                listOf("dragonite_preset_2", "gyarados_preset_2", "blissey_preset_1"),
                // Its last Pokemon, after a Dragon Dance: it outspeeds and cannot switch, so only the priority move
                // lands first. With a bench behind it, it switches out of the Icicle Crash and that plays out better.
                start = LocalScenarioStart(hp = mapOf((A to 0) to 0.6, (O to 0) to 0.45, (O to 1) to 0.0, (O to 2) to 0.0),
                    stages = mapOf((O to 0) to mapOf("attack" to 1, "speed" to 1))),
                accepted = setOf("iceshard")),
            // Status at the right target.
            Puzzle("umbreon-toxics-hippowdon", "status",
                listOf("umbreon_preset_2", "toxapex_preset_2", "skarmory_preset_1"),
                listOf("hippowdon_preset_1", "blissey_preset_2", "garchomp_preset_2"),
                accepted = setOf("toxic")),
            Puzzle("umbreon-spares-skarmory", "status_restraint",
                listOf("umbreon_preset_2", "toxapex_preset_2", "heatran_preset_1"),
                listOf("skarmory_preset_2", "blissey_preset_2", "garchomp_preset_2"),
                rejected = setOf("toxic")),
            Puzzle("rotomfan-burns-dragonite", "status",
                listOf("rotomfan_preset_2", "toxapex_preset_1", "clefable_preset_1"),
                listOf("dragonite_preset_2", "blissey_preset_1", "heatran_preset_1"),
                accepted = setOf("willowisp")),
            Puzzle("blissey-paralyzes-weavile", "status",
                listOf("blissey_preset_2", "toxapex_preset_2", "corviknight_preset_1"),
                listOf("weavile_preset_2", "garchomp_preset_1", "gengar_preset_1"),
                accepted = setOf("thunderwave", "교체→corviknight")),
            // Healing: when it keeps the wall alive, and not when it has nothing to heal.
            Puzzle("toxapex-recovers", "heal",
                listOf("toxapex_preset_1", "skarmory_preset_2", "swampert_preset_1"),
                listOf("clefable_preset_2", "garchomp_preset_2", "heatran_preset_1"),
                start = hp((A to 0) to 0.35),
                accepted = setOf("recover")),
            Puzzle("toxapex-toxics-at-full", "heal_restraint",
                listOf("toxapex_preset_1", "skarmory_preset_2", "swampert_preset_1"),
                listOf("clefable_preset_1", "garchomp_preset_2", "heatran_preset_1"),
                accepted = setOf("toxic")),
        )
    }
}
