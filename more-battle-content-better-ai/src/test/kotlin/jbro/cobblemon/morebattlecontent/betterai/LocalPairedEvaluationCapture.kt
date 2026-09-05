package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW

/** Local-only evaluation. Both arms use the same difficulty, information and execution budget. */
internal object LocalPairedEvaluationCapture {
    private val json = GsonBuilder().setPrettyPrinting().serializeNulls().create()

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 11) { "Expected root, output, pairs, seed, turns, format, tier, split, challenger, defender, allowHoldout" }
        val root = Path.of(args[0]).toAbsolutePath().normalize()
        val output = Path.of(args[1]).toAbsolutePath().normalize()
        val count = args[2].toInt()
        val seed = args[3].toInt()
        val turns = args[4].toInt()
        val format = BattleFormat.valueOf(args[5])
        val difficulty = LocalBaselineCapture.difficulty(args[6])
        val split = EvaluationSplit.valueOf(args[7])
        require(split != EvaluationSplit.HOLDOUT || args[10] == "true") {
            "Holdout requires -PallowHoldout; if used for tuning, retire this corpus as holdout"
        }
        val challenger = tuning(args[8])
        val defender = tuning(args[9])
        require(turns in 1..1000)
        val definitions = LocalEvaluationCorpus.sample(count, seed, format, split)
        val scenarios = LocalEvaluationCorpus.scenarios(split, format)
        val identity = LocalBaselineProvenance.capture(root)
        val manifest = LocalBaselineCapture.manifest(
            LocalBaselineOptions(count + scenarios.size, seed, turns, format, difficulty), identity, definitions + scenarios,
        ).apply {
            addProperty("schemaVersion", "paired-evaluation-v1")
            addProperty("split", split.name)
            addProperty("partitionRule", "UNORDERED_COMPLETE_SET_PAIRS_SHA256_MOD5_HOLDOUT0_WITH_RESERVED_REGRESSIONS_V1")
            addProperty("requestedRandomPairs", count)
            addProperty("battlesPerPair", 2)
            addProperty("challenger", args[8])
            addProperty("defender", args[9])
            add("tuning", JsonObject().apply {
                add("challenger", json.toJsonTree(challenger))
                add("defender", json.toJsonTree(defender))
            })
            add("randomPairIds", json.toJsonTree(definitions.map(LocalEvaluationCorpus::key)))
            add("regressionPairIds", json.toJsonTree(scenarios.map(LocalEvaluationCorpus::key)))
            addProperty("regressionObjective", "Turn progression, public move evidence and completion; not tactical optimality")
            add("regressionFailureConditions", json.toJsonTree(listOf("NO_TURNS", "NO_MOVE_EVIDENCE", "STALLED")))
            addProperty("drawMeaning", "No winner, including turn-limit truncation; scored 0.5, not proof of a natural draw")
            addProperty("samplingCaveat", "Distinct pseudo-random pairs; confidence bound assumes independent representative pair outcomes")
            addProperty("holdoutPolicy", "Explicit opt-in only; results used to tune must be retired from held-out evidence")
        }
        LocalBaselineCapture.createRun(output, manifest)
        val outcomes = mutableListOf<LocalPairOutcome>()
        var regressionFailures = 0
        Files.newBufferedWriter(output.resolve("pairs.jsonl"), CREATE_NEW).use { writer ->
            (definitions + scenarios).forEachIndexed { index, definition ->
                val records = JsonObject()
                val winners = mutableMapOf<Boolean, String?>()
                // Alternate execution order to avoid always warming up on the same challenger side.
                val order = if (index % 2 == 0) listOf(true, false) else listOf(false, true)
                var failures = 0
                for (asCycle in order) {
                    val trace = mutableListOf<LocalScenarioDecisionTrace>()
                    val report = LocalTacticalScenarioBattle.run(definition, turns,
                        cycleTuning = if (asCycle) challenger else defender,
                        offenseTuning = if (asCycle) defender else challenger,
                        cycleDifficulty = difficulty, offenseDifficulty = difficulty, recordedDecisions = trace)
                    winners[asCycle] = report.winner
                    val flags = failureConditions(report)
                    failures += flags.size
                    records.add(if (asCycle) "challengerAsCycle" else "challengerAsOffense", JsonObject().apply {
                        add("report", json.toJsonTree(report))
                        add("decisions", json.toJsonTree(trace))
                        add("regressionFailures", json.toJsonTree(flags))
                        addProperty("unresolved", report.winner == null)
                    })
                }
                val outcome = LocalPairOutcome(LocalEvaluationCorpus.key(definition), winners[true], winners[false])
                val isRegression = index >= definitions.size
                if (isRegression) regressionFailures += failures else outcomes.add(outcome)
                val record = JsonObject().apply {
                    addProperty("kind", if (isRegression) "FIXED_REGRESSION" else "RANDOM_PAIR")
                    addProperty("split", split.name)
                    add("outcome", json.toJsonTree(outcome))
                    addProperty("challengerPairScore", outcome.scores.average())
                    add("executionOrder", json.toJsonTree(order.map { if (it) "CYCLE" else "OFFENSE" }))
                    add("orientations", records)
                }
                writer.append(record.toString()).appendLine()
                writer.flush()
                println("evaluation pair=${index + 1}/${definitions.size + scenarios.size} kind=${if (isRegression) "regression" else "random"}")
            }
        }
        check(LocalBaselineProvenance.capture(root) == identity) { "Inputs changed during paired evaluation" }
        val summary = JsonObject().apply {
            addProperty("status", "COMPLETE")
            addProperty("split", split.name)
            add("randomPairs", json.toJsonTree(LocalPairedSummary.from(outcomes)))
            addProperty("regressionPairs", scenarios.size)
            addProperty("regressionFailureCount", regressionFailures)
            addProperty("qualityVerdict", "NOT_AUTOMATICALLY_INFERRED_FROM_HARNESS")
            addProperty("javaVersion", System.getProperty("java.version"))
        }
        Files.writeString(output.resolve("summary.json"), json.toJson(summary), CREATE_NEW)
        println("paired evaluation complete: $output")
    }

    internal fun failureConditions(report: LocalTacticalScenarioReport): List<String> = buildList {
        if (report.turns.isEmpty()) add("NO_TURNS")
        if (report.publicEvidenceCounts.getOrDefault(BattleObservedEventKind.MOVE_USED, 0) == 0) add("NO_MOVE_EVIDENCE")
        if (report.stalled) add("STALLED")
    }

    private fun tuning(name: String): LocalDecisionTuning = when (name) {
        "CURRENT" -> LocalDecisionTuning.CURRENT
        "LEGACY" -> LocalDecisionTuning.LEGACY
        else -> error("Supported evaluation arms: CURRENT or LEGACY")
    }
}
