package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.GsonBuilder
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicFutureActionFactory
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionPolicy
import jbro.cobblemon.morebattlecontent.betterai.search.LocalLookaheadBudget
import jbro.cobblemon.morebattlecontent.betterai.search.LocalRecursiveLookaheadEvaluator
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW

/** Cost comparison, not a controlled strength comparison: recursive ranking has a different objective. */
internal object LiveRootSearchExperiment {
    // Test-only introspection avoids silently duplicating a private production deadline constant.
    // If its representation changes, fail the probe instead of claiming equal active windows.
    val recursiveSafetyMarginMillis: Long = LocalRecursiveLookaheadEvaluator::class.java
        .getDeclaredField("DEADLINE_MARGIN_MILLIS").apply { isAccessible = true }.getLong(null)
        .also { require(it in 0L..10_000L) }

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
        val rows = mutableListOf<Map<String, Any?>>()
        val profile = BattleTrainerProfile.balanced().copy(difficulty = BattleDifficultyProfiles.BOSS)
        val accepted = contexts.withIndex().filter { PublicRootAllocationExperiment.exclusion(it.value) == null }
        for ((index, original) in accepted) {
            // Common public input preparation is excluded from all search-core timings.
            require(original.deadlineEpochMillis == Long.MAX_VALUE)
            val context = PublicBattleTacticalCalculator.calculate(original)
            val base = LocalBattleActionPolicy.rank(context, null, profile)
            val replies = PublicFutureActionFactory.actions(context.state, BattleSide.OPPONENT, context.publicActionCatalog)
            fun live(policy: RootAllocationPolicy, time: Long, samples: Int, seed: Int): Map<String, Any?> {
                val start = System.nanoTime()
                val sampler = LiveProjectedRootSampler(context, replies, seed)
                val setupNanos = System.nanoTime() - start
                // Charge sampler preparation against the same deadline as sampling.
                val result = LiveRootAllocator.run(context.candidates.map { it.actionId }, replies.map { it.actionId },
                    policy, samples, time, seed,
                    startedAtNanos = start, sample = sampler::sample)
                return mapOf("arm" to policy.name, "chosen" to result.chosen,
                    "elapsedMillis" to (System.nanoTime() - start) / 1_000_000.0,
                    "setupMillis" to setupNanos / 1_000_000.0, "allocation" to result,
                    "projectionCalls" to sampler.projectionCalls, "projectedBranches" to sampler.projectedBranches,
                    "leafEvaluations" to sampler.leafEvaluations)
            }
            fun recursive(time: Long): Map<String, Any?> {
                val start = System.nanoTime()
                val result = LocalRecursiveLookaheadEvaluator.evaluate(base, context, profile,
                    budget = LocalLookaheadBudget(time + recursiveSafetyMarginMillis, Int.MAX_VALUE, 64))
                return mapOf("arm" to "RECURSIVE", "chosen" to result.ranked.firstOrNull()?.outcome?.candidate?.actionId,
                    "configuredBudgetMillis" to time + recursiveSafetyMarginMillis,
                    "elapsedMillis" to (System.nanoTime() - start) / 1_000_000.0,
                    "nodes" to result.nodesVisited, "leafWorkUnits" to result.leafWorkUnits,
                    "depthCompleted" to result.depthCompleted, "truncated" to result.truncated,
                    "publicResponseIncomplete" to result.publicResponseIncomplete)
            }
            // Warm each path on this context; never count warm-up as timed evidence.
            live(RootAllocationPolicy.UNIFORM, 50, 24, 0)
            live(RootAllocationPolicy.UCB, 50, 24, 0)
            recursive(50)
            for (samples in listOf(24, 96)) for (seed in 1..4) for (policy in RootAllocationPolicy.entries) {
                rows += live(policy, Long.MAX_VALUE, samples, seed) + mapOf("position" to index,
                    "mode" to "FIXED_DRAWS", "sampleBudget" to samples, "seed" to seed)
            }
            for (time in listOf(50L, 150L)) for (repeat in 0..2) {
                val arms = listOf("UNIFORM", "UCB", "RECURSIVE")
                for (offset in arms.indices) {
                    val arm = arms[(repeat + offset) % arms.size]
                    val row = if (arm == "RECURSIVE") recursive(time)
                        else live(RootAllocationPolicy.valueOf(arm), time, 20_000, repeat + 1)
                    rows += row + mapOf("position" to index, "mode" to "TIME_CAP", "budgetMillis" to time, "repeat" to repeat)
                }
            }
        }
        val gson = GsonBuilder().setPrettyPrinting().create()
        val hashes = listOf(LiveRootAllocator::class.java, LiveProjectedRootSampler::class.java,
            LiveRootSearchExperiment::class.java, LocalRecursiveLookaheadEvaluator::class.java).associate { type ->
            type.name to LocalBaselineCapture.digest(requireNotNull(type.getResourceAsStream(
                "/${type.name.replace('.', '/')}.class")).use { it.readBytes() })
        }
        val report = mapOf("scope" to "LIVE_PUBLIC_PROJECTOR_COST_PROBE_NOT_STRENGTH",
            "javaVersion" to System.getProperty("java.version"), "classHashes" to hashes,
            "recursiveSafetyMarginMillis" to recursiveSafetyMarginMillis,
            "definitions" to definitions, "contexts" to accepted.associate { it.index to it.value },
            "excluded" to contexts.mapIndexedNotNull { i, c -> PublicRootAllocationExperiment.exclusion(c)?.let {
                mapOf("position" to i, "reason" to it) } },
            "recursiveDepthRequested" to profile.difficulty.lookaheadPlies,
            "nodeCountsAreNotDrawCounts" to true, "rows" to rows)
        Files.writeString(directory.resolve("comparison.json"), gson.toJson(report), CREATE_NEW)
        println("live root cost positions=${accepted.size} rows=${rows.size}")
        check(accepted.isNotEmpty())
    }
}
