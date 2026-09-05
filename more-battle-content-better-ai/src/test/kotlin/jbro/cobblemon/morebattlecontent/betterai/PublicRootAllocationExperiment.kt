package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.GsonBuilder
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicFutureActionFactory
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalLookaheadStateEvaluator
import jbro.cobblemon.morebattlecontent.betterai.outcome.ChanceEffectProjectionMode
import jbro.cobblemon.morebattlecontent.betterai.outcome.PublicSingleTurnProjector
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.Random

internal data class ProbeOutcome(val weight: Double, val value: Double)

internal class ProbeDistribution(val outcomes: List<ProbeOutcome>) {
    private val total = outcomes.sumOf { it.weight }
    init {
        require(outcomes.isNotEmpty() && total.isFinite() && total > 0.0)
        require(outcomes.all { it.weight.isFinite() && it.weight >= 0.0 && it.value.isFinite() })
    }
    val mean: Double get() = outcomes.sumOf { it.weight / total * it.value }
    fun sample(unit: Double): Double {
        require(unit.isFinite() && unit >= 0.0 && unit < 1.0)
        var remaining = unit * total
        for (outcome in outcomes) {
            remaining -= outcome.weight
            if (remaining < 0.0) return outcome.value
        }
        return outcomes.last { it.weight > 0.0 }.value
    }
}

internal data class ProbeComparison(
    val policy: RootAllocationPolicy,
    val seed: Int,
    val samples: Int,
    val result: RootAllocationResult,
    /** Exact expected board-value loss within this model, not battle win-rate loss. */
    val regret: Double,
)

/** Precomputed public one-turn distributions isolate allocation; preprocessing is NOT free search. */
internal object PublicRootAllocationExperiment {
    fun exclusion(context: BattleDecisionContext): String? {
        if (context.state.format != BattleFormat.SINGLE) return "SINGLES_ONLY"
        if (context.candidates.size < 2) return "NO_CHOICE"
        val actives = context.state.pokemon.filter { it.activeSlot != null && !it.fainted }
        if (actives.any { it.level == null || it.combatStats == null }) return "MISSING_PUBLIC_STATS"
        val replies = PublicFutureActionFactory.actions(context.state, BattleSide.OPPONENT, context.publicActionCatalog)
        if (replies.none { it.kind == BattleActionKind.USE_MOVE &&
                it.moveDetails?.damageCategory != BattleMoveDamageCategory.STATUS &&
                (it.moveDetails?.power ?: 0.0) > 0.0 }) return "NO_REVEALED_DAMAGING_RESPONSE"
        return null
    }

    fun compare(table: Map<String, ProbeDistribution>, samples: Int, seed: Int): List<ProbeComparison> {
        val reference = table.values.maxOf { it.mean }
        return RootAllocationPolicy.entries.map { policy ->
            // Matching per-action streams prevent allocation order from changing an action's kth draw.
            val streams = table.keys.sorted().mapIndexed { index, id ->
                id to Random(seed.toLong() * 1_000_003L + index)
            }.toMap()
            val result = RootAllocationProbe.run(table.keys.toList(), samples, policy) { id ->
                table.getValue(id).sample(streams.getValue(id).nextDouble())
            }
            ProbeComparison(policy, seed, samples, result, (reference - table.getValue(result.chosen).mean).coerceAtLeast(0.0))
        }
    }

    fun table(context: BattleDecisionContext): Map<String, ProbeDistribution> {
        require(exclusion(context) == null) { exclusion(context) ?: "Unsupported context" }
        val replies = PublicFutureActionFactory.actions(context.state, BattleSide.OPPONENT, context.publicActionCatalog)
        require(replies.isNotEmpty()) { "No public opponent responses; cannot label this as covered" }
        return context.candidates.associate { action ->
            val outcomes = replies.flatMap { reply ->
                val projections = PublicSingleTurnProjector.project(
                    context.state, action, reply, context,
                    maxChanceBranchesPerMove = 64,
                    chanceEffectMode = ChanceEffectProjectionMode.BRANCH_STATE,
                )
                require(projections.isNotEmpty())
                // Probability is conditional within an action order. Do not over-weight orders
                // merely because they produced more chance branches (e.g. early KO cancellation).
                val orders = projections.groupBy { it.order }.values
                val orderTotal = orders.sumOf { it.first().orderProbability }
                require(orderTotal > 0.0)
                orders.flatMap { group ->
                    val chanceTotal = group.sumOf { it.probability }
                    require(chanceTotal > 0.0)
                    group.map { outcome ->
                        ProbeOutcome(
                            outcome.probability / chanceTotal * outcome.orderProbability / orderTotal / replies.size,
                            LocalLookaheadStateEvaluator.evaluate(outcome.state, context),
                        )
                    }
                }
            }
            action.actionId to ProbeDistribution(outcomes)
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1)
        val directory = Path.of(args[0]).toAbsolutePath().normalize()
        Files.createDirectories(directory.parent)
        Files.createDirectory(directory)
        val gson = GsonBuilder().setPrettyPrinting().create()
        val definitions = LocalSelfPlayMeasurement.definitions(2, 20260906)
        val contexts = mutableListOf<BattleDecisionContext>()
        definitions.forEach {
            LocalTacticalScenarioBattle.run(it, maximumTurns = 4,
                cycleDifficulty = BattleDifficultyProfiles.INTRODUCTORY, recordedContexts = contexts)
        }
        val excluded = contexts.mapIndexedNotNull { index, context ->
            exclusion(context)?.let { mapOf("position" to index, "reason" to it) }
        }
        val allRows = mutableListOf<ProbeComparison>()
        val reports = contexts.mapIndexedNotNull { index, context ->
            if (exclusion(context) != null) return@mapIndexedNotNull null
            val started = System.nanoTime()
            val table = table(context)
            val preprocessingMillis = (System.nanoTime() - started) / 1_000_000.0
            val rows = listOf(4, 16, 64).flatMap { perAction ->
                (1..32).flatMap { seed -> compare(table, table.size * perAction, seed) }
            }
            allRows += rows
            mapOf("position" to index, "context" to context, "table" to table,
                "flatExpectations" to (table.values.maxOf { it.mean } - table.values.minOf { it.mean } < 1e-9),
                "preprocessingMillis" to preprocessingMillis, "comparisons" to rows)
        }
        val classes = listOf(RootAllocationProbe::class.java, PublicRootAllocationExperiment::class.java,
            PublicSingleTurnProjector::class.java, LocalLookaheadStateEvaluator::class.java)
        val hashes = classes.associate { type ->
            type.name to LocalBaselineCapture.digest(requireNotNull(type.getResourceAsStream(
                "/${type.name.replace('.', '/')}.class")).use { it.readBytes() })
        }
        val report = mapOf("scope" to "ROOT_ALLOCATION_PRECOMPUTED_PUBLIC_ONE_TURN",
            "referenceCommit" to "4909360fddc827942b18d840dd796385b46eb9e4",
            "opponentPolicy" to "UNIFORM_PUBLIC_RESPONSES_NOT_PRODUCT_OPPONENT_MODEL",
            "budgetUnit" to "PRECOMPUTED_OUTCOME_DRAWS_NOT_NODES_OR_TIME",
            "explorationBoardUnits" to 1.0, "definitions" to definitions,
            "classHashes" to hashes, "excluded" to excluded, "positions" to reports,
            "summary" to allRows.groupBy { it.policy }.mapValues { (_, rows) ->
                mapOf("runs" to rows.size, "meanModelRegret" to rows.map { it.regret }.average(),
                    "modelBestMisses" to rows.count { it.regret > 1e-9 })
            })
        Files.writeString(directory.resolve("comparison.json"), gson.toJson(report), CREATE_NEW)
        println("root allocation positions=${reports.size}; report=${directory.resolve("comparison.json")}")
        check(reports.isNotEmpty()) { "No supported positions; inspect exclusions" }
    }
}
