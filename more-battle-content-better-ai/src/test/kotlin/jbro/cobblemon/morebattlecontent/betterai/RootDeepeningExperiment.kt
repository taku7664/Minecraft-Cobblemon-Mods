package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.GsonBuilder
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import kotlin.math.abs

internal object RootDeepeningExperiment {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1 || (args.size == 2 && args[1] == "tactical"))
        val tactical = args.size == 2
        val directory = Path.of(args[0]).toAbsolutePath().normalize()
        Files.createDirectories(directory.parent)
        Files.createDirectory(directory)
        val fixtures = if (tactical) RootTacticalFixtures.all() else emptyList()
        val definitions = if (tactical) emptyList() else LocalSelfPlayMeasurement.definitions(2, 20260906)
        val contexts = fixtures.map { it.context }.toMutableList()
        definitions.forEach { LocalTacticalScenarioBattle.run(it, maximumTurns = 4,
            cycleDifficulty = BattleDifficultyProfiles.INTRODUCTORY, recordedContexts = contexts) }
        val positions = contexts.withIndex().filter { PublicRootAllocationExperiment.exclusion(it.value) == null }
        val rows = mutableListOf<Map<String, Any?>>()
        val references = mutableListOf<Map<String, Any?>>()
        val mismatches = mutableListOf<String>()
        var horizonChanges = 0
        for ((position, original) in positions) {
            val context = PublicBattleTacticalCalculator.calculate(original)
            val reference = (1..2).associateWith { RootObjectiveReference.evaluate(context, it) }
            check(reference.values.all { it.matches })
            val oneBest = reference.getValue(1).isolatedRanking.first()
            val twoBest = reference.getValue(2).isolatedRanking.first()
            if (oneBest != twoBest) horizonChanges++
            val fixture = fixtures.getOrNull(position)
            if (fixture?.expectedAction != null && (oneBest != fixture.expectedAction || twoBest != fixture.expectedAction)) {
                mismatches += "${fixture.id}:control-expectation"
            }
            references += mapOf("position" to position, "scoresByDepth" to reference.mapValues { it.value.scores },
                "fixtureId" to fixture?.id, "depthOneBest" to oneBest, "depthTwoBest" to twoBest,
                "horizonChangesBest" to (oneBest != twoBest), "targetRanking" to reference.getValue(2).isolatedRanking)
            val ids = context.candidates.map { it.actionId }
            // Warm-up is excluded. Acceptance must not change the actual recursive work.
            for (policy in RootDeepeningPolicy.entries) for (acceptance in RootDepthAcceptance.entries) {
                RootDeepeningAllocator.run(ids, policy, 500, acceptance, LiveRecursiveRootEvaluator(context)::evaluate)
            }
            val budgets = if (tactical) listOf(25, 50, 100, 200, 400, 800, 1_600)
                else listOf(250, 500, 1_000, 2_000, 4_000, 8_000, 20_000)
            for ((budgetIndex, budget) in budgets.withIndex()) {
                val policies = RootDeepeningPolicy.entries.let { if ((position + budgetIndex) % 2 == 0) it else it.reversed() }
                for (policy in policies) {
                    var pairedResult: RootDeepeningResult? = null
                    val acceptances = RootDepthAcceptance.entries.let {
                        if ((position + budgetIndex + policy.ordinal) % 2 == 0) it else it.reversed()
                    }
                    for (acceptance in acceptances) {
                        val start = System.nanoTime()
                        val evaluator = LiveRecursiveRootEvaluator(context)
                        val result = RootDeepeningAllocator.run(ids, policy, budget, acceptance, evaluator::evaluate)
                        val elapsed = (System.nanoTime() - start) / 1_000_000.0
                        pairedResult?.let { paired ->
                            check(result.attempts == paired.attempts && result.nodes == paired.nodes &&
                                result.scores == paired.scores && result.depths == paired.depths) {
                                "Acceptance changed work: $position:$budget:$policy"
                            }
                        }
                        pairedResult = result
                        if (acceptance == RootDepthAcceptance.COMMON_DEPTH && result.chosen != null) {
                            val accepted = reference.getValue(requireNotNull(result.selectionDepth)).scores
                            check(ids.all { abs(result.selectionScores.getValue(it) - accepted.getValue(it)) <= 1e-9 })
                            check(result.chosen == reference.getValue(result.selectionDepth).isolatedRanking.first())
                        }
                        for (attempt in result.attempts) {
                            val score = attempt.reading.score ?: continue
                            if (abs(score - reference.getValue(attempt.depth).scores.getValue(attempt.actionId)) > 1e-9) {
                                mismatches += "$position:$budget:$policy:${attempt.actionId}:${attempt.depth}"
                            }
                        }
                        val loss = RootObjectiveReference.loss(reference.getValue(2).scores, result.chosen)
                        if (result.targetDepthComplete && (loss == null || loss > 1e-9)) {
                            mismatches += "$position:$budget:$policy:completed-target-choice"
                        }
                        rows += mapOf("position" to position, "fixtureId" to fixture?.id,
                            "policy" to policy.name, "acceptance" to acceptance.name, "nodeBudget" to budget,
                            "result" to result, "unusedNodes" to budget - result.nodes, "elapsedMillis" to elapsed,
                            "mixedDepths" to (result.depths.values.distinct().size > 1),
                            "selectionUsesMixedDepths" to (result.chosen != null && result.selectionDepth == null),
                            "targetScoreLoss" to loss)
                    }
                }
            }
        }
        val report = mapOf("scope" to "SAME_RECURSIVE_OBJECTIVE_ROOT_ORDER_PROBE_NOT_PRODUCT_ADOPTION",
            "provenance" to LocalBaselineProvenance.capture(Path.of("").toAbsolutePath().normalize()),
            "javaVersion" to System.getProperty("java.version"), "definitions" to definitions,
            "fixtureMode" to tactical, "fixtures" to fixtures.map { mapOf("id" to it.id,
                "family" to it.family, "expectedAction" to it.expectedAction) },
            "horizonChangesBestCount" to horizonChanges,
            "contexts" to positions.associate { it.index to it.value },
            "excluded" to contexts.mapIndexedNotNull { i, c -> PublicRootAllocationExperiment.exclusion(c)?.let {
                mapOf("position" to i, "reason" to it) } },
            "referenceNodeLimitPerDepth" to 200_000, "chanceWidth" to 64, "targetDepth" to 2,
            "clock" to "OFFLINE_CONSTANT_NOT_LATENCY_LIMIT", "mixedDepthChoicesAreProvisional" to true,
            "acceptanceModes" to RootDepthAcceptance.entries.map { it.name },
            "defaultAcceptance" to RootDepthAcceptance.COMMON_DEPTH.name,
            "countsExcludeBasePreparation" to true, "references" to references,
            "scoreMismatches" to mismatches, "rows" to rows)
        Files.writeString(directory.resolve("comparison.json"), GsonBuilder().setPrettyPrinting().serializeNulls()
            .create().toJson(report), CREATE_NEW)
        println("root deepening positions=${positions.size} rows=${rows.size} scoreMismatches=${mismatches.size} horizonChanges=$horizonChanges")
        check(positions.isNotEmpty() && mismatches.isEmpty() && (!tactical || horizonChanges > 0))
    }
}
