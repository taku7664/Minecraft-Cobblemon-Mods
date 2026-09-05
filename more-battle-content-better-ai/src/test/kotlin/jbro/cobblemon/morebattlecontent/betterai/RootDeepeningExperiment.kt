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
        require(args.size == 1)
        val directory = Path.of(args[0]).toAbsolutePath().normalize()
        Files.createDirectories(directory.parent)
        Files.createDirectory(directory)
        val definitions = LocalSelfPlayMeasurement.definitions(2, 20260906)
        val contexts = mutableListOf<BattleDecisionContext>()
        definitions.forEach { LocalTacticalScenarioBattle.run(it, maximumTurns = 4,
            cycleDifficulty = BattleDifficultyProfiles.INTRODUCTORY, recordedContexts = contexts) }
        val positions = contexts.withIndex().filter { PublicRootAllocationExperiment.exclusion(it.value) == null }
        val rows = mutableListOf<Map<String, Any?>>()
        val references = mutableListOf<Map<String, Any?>>()
        val mismatches = mutableListOf<String>()
        for ((position, original) in positions) {
            val context = PublicBattleTacticalCalculator.calculate(original)
            val reference = (1..2).associateWith { RootObjectiveReference.evaluate(context, it) }
            check(reference.values.all { it.matches })
            references += mapOf("position" to position, "scoresByDepth" to reference.mapValues { it.value.scores },
                "targetRanking" to reference.getValue(2).isolatedRanking)
            val ids = context.candidates.map { it.actionId }
            // Warm-up is excluded. Both arms get identical evaluator setup and acceptance rules.
            for (policy in RootDeepeningPolicy.entries) {
                RootDeepeningAllocator.run(ids, policy, 500, LiveRecursiveRootEvaluator(context)::evaluate)
            }
            for ((budgetIndex, budget) in listOf(250, 500, 1_000, 2_000, 4_000, 8_000, 20_000).withIndex()) {
                val policies = RootDeepeningPolicy.entries.let { if ((position + budgetIndex) % 2 == 0) it else it.reversed() }
                for (policy in policies) {
                    val start = System.nanoTime()
                    val evaluator = LiveRecursiveRootEvaluator(context)
                    val result = RootDeepeningAllocator.run(ids, policy, budget, evaluator::evaluate)
                    val elapsed = (System.nanoTime() - start) / 1_000_000.0
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
                    rows += mapOf("position" to position, "policy" to policy.name, "nodeBudget" to budget,
                        "result" to result, "unusedNodes" to budget - result.nodes, "elapsedMillis" to elapsed,
                        "mixedDepths" to (result.depths.values.distinct().size > 1), "targetScoreLoss" to loss)
                }
            }
        }
        val report = mapOf("scope" to "SAME_RECURSIVE_OBJECTIVE_ROOT_ORDER_PROBE_NOT_PRODUCT_ADOPTION",
            "provenance" to LocalBaselineProvenance.capture(Path.of("").toAbsolutePath().normalize()),
            "javaVersion" to System.getProperty("java.version"), "definitions" to definitions,
            "contexts" to positions.associate { it.index to it.value },
            "excluded" to contexts.mapIndexedNotNull { i, c -> PublicRootAllocationExperiment.exclusion(c)?.let {
                mapOf("position" to i, "reason" to it) } },
            "referenceNodeLimitPerDepth" to 200_000, "chanceWidth" to 64, "targetDepth" to 2,
            "clock" to "OFFLINE_CONSTANT_NOT_LATENCY_LIMIT", "mixedDepthChoicesAreProvisional" to true,
            "countsExcludeBasePreparation" to true, "references" to references,
            "scoreMismatches" to mismatches, "rows" to rows)
        Files.writeString(directory.resolve("comparison.json"), GsonBuilder().setPrettyPrinting().serializeNulls()
            .create().toJson(report), CREATE_NEW)
        println("root deepening positions=${positions.size} rows=${rows.size} scoreMismatches=${mismatches.size}")
        check(positions.isNotEmpty() && mismatches.isEmpty())
    }
}
