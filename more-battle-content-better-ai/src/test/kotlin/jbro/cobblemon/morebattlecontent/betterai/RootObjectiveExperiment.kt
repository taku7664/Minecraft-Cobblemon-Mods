package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.GsonBuilder
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicFutureActionFactory
import jbro.cobblemon.morebattlecontent.betterai.search.LocalRecursiveLookaheadEvaluator
import jbro.cobblemon.morebattlecontent.betterai.search.LocalSearchResponseObjective
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW

/** Common referee, not yet a common optimization objective for the sampling policies. */
internal object RootObjectiveExperiment {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1)
        val directory = Path.of(args[0]).toAbsolutePath().normalize()
        Files.createDirectories(directory.parent)
        Files.createDirectory(directory)
        val contexts = mutableListOf<BattleDecisionContext>()
        val definitions = LocalSelfPlayMeasurement.definitions(2, 20260906)
        definitions.forEach { LocalTacticalScenarioBattle.run(it, maximumTurns = 4,
            cycleDifficulty = BattleDifficultyProfiles.INTRODUCTORY, recordedContexts = contexts) }
        val accepted = contexts.withIndex().filter { PublicRootAllocationExperiment.exclusion(it.value) == null }
        val references = mutableListOf<Map<String, Any?>>()
        val rows = mutableListOf<Map<String, Any?>>()
        var parityFailures = 0
        for ((position, original) in accepted) {
            val context = PublicBattleTacticalCalculator.calculate(original)
            val byDepth = (1..2).associateWith { depth -> RootObjectiveReference.evaluate(context, depth) }
            byDepth.forEach { (depth, reference) ->
                if (!reference.matches) parityFailures++
                references += mapOf("position" to position, "depth" to depth, "matches" to reference.matches,
                    "mismatches" to reference.mismatches, "scores" to reference.scores,
                    "wholeRanking" to reference.whole.ranked.map { it.outcome.candidate.actionId },
                    "isolatedRanking" to reference.isolatedRanking,
                    "wholeNodes" to reference.whole.nodesVisited,
                    "publicResponseIncomplete" to reference.whole.publicResponseIncomplete,
                    "publicResponseCoverage" to reference.whole.publicResponseCoverage,
                    "individual" to reference.individual.map { entry ->
                        val rank = entry.evaluation.ranked.single()
                        mapOf("actionId" to entry.actionId, "score" to rank.comparisonValue,
                            "lookaheadUtility" to rank.lookaheadUtility, "executionProbability" to rank.executionProbability,
                            "worstResponseHpRetention" to rank.worstResponseHpRetention,
                            "nodes" to entry.evaluation.nodesVisited,
                            "publicResponseIncomplete" to entry.evaluation.publicResponseIncomplete,
                            "publicResponseCoverage" to entry.evaluation.publicResponseCoverage)
                    })
            }
            if (byDepth.values.any { !it.matches }) continue
            val replies = PublicFutureActionFactory.actions(context.state, BattleSide.OPPONENT, context.publicActionCatalog)
            // Allocators never receive reference scores. Each arm/seed starts fresh matching streams.
            for (samples in listOf(24, 96)) for (seed in 1..4) for (policy in RootAllocationPolicy.entries) {
                val sampler = LiveProjectedRootSampler(context, replies, seed)
                val result = LiveRootAllocator.run(context.candidates.map { it.actionId }, replies.map { it.actionId },
                    policy, samples, Long.MAX_VALUE, seed, sample = sampler::sample)
                for ((depth, reference) in byDepth) {
                    rows += mapOf("position" to position, "depth" to depth, "policy" to policy.name,
                        "samples" to samples, "seed" to seed, "chosen" to result.chosen,
                        "coverageComplete" to result.coverageComplete,
                        "scoreLoss" to RootObjectiveReference.loss(reference.scores, result.chosen),
                        "chosenScore" to result.chosen?.let(reference.scores::getValue),
                        "referenceBestScore" to reference.scores.values.max(),
                        "visits" to result.visits, "sampleMeans" to result.means,
                        "worstObservedKoProbability" to result.worstObservedKoProbability,
                        "projectionCalls" to sampler.projectionCalls)
                }
            }
        }
        val classes = listOf(RootObjectiveReference::class.java, RootObjectiveExperiment::class.java,
            LocalRecursiveLookaheadEvaluator::class.java, LocalSearchResponseObjective::class.java,
            LiveRootAllocator::class.java, LiveProjectedRootSampler::class.java)
        val hashes = classes.associate { type -> type.name to LocalBaselineCapture.digest(
            requireNotNull(type.getResourceAsStream("/${type.name.replace('.', '/')}.class")).use { it.readBytes() }) }
        val report = mapOf("scope" to "COMMON_RECURSIVE_REFEREE_NOT_ALLOCATION_ONLY_QUALITY",
            "definitions" to definitions, "contexts" to accepted.associate { it.index to it.value },
            "excluded" to contexts.mapIndexedNotNull { i, c -> PublicRootAllocationExperiment.exclusion(c)?.let {
                mapOf("position" to i, "reason" to it) } },
            "referenceClock" to "CONSTANT_OFFLINE_NOT_LATENCY", "referenceNodeLimitPerDepth" to 200_000,
            "referenceChanceWidth" to 64, "referenceTier" to "BOSS", "referenceDepths" to listOf(1, 2),
            "scoreUnit" to "RECURSIVE_COMPARISON_VALUE_NOT_WIN_RATE", "parityTolerance" to 1e-9,
            "missingRecommendationsAreNotZeroLoss" to true, "javaVersion" to System.getProperty("java.version"),
            "classHashes" to hashes, "references" to references, "rows" to rows)
        Files.writeString(directory.resolve("comparison.json"), GsonBuilder().setPrettyPrinting().serializeNulls()
            .create().toJson(report), CREATE_NEW)
        println("root objective positions=${accepted.size} references=${references.size} parityFailures=$parityFailures rows=${rows.size}")
        check(accepted.isNotEmpty() && parityFailures == 0)
    }
}
