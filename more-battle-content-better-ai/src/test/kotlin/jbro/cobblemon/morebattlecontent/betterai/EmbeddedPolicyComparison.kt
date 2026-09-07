package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalHypothesisPriorityReservation
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import kotlin.math.ln
import kotlin.math.sqrt

internal object EmbeddedPolicyComparison {
    fun profileForSkill(skill: Int): BattleTrainerProfile = BattleTrainerProfile.balanced(skill)

    data class Assignment(val reverseTeams: Boolean, val challengerP1: Boolean)

    fun schedule(pairIndex: Int): List<Assignment> {
        require(pairIndex >= 0)
        val all = listOf(false, true).flatMap { reverse -> listOf(false, true).map { Assignment(reverse, it) } }
        val offset = pairIndex % all.size
        return all.drop(offset) + all.take(offset)
    }

    fun score(outcomes: List<String>): Pair<Double, Double> {
        require(outcomes.size == 4 && outcomes.all { it in setOf("WIN", "LOSS", "DRAW", "INCOMPLETE") })
        val lower = outcomes.sumOf { when (it) { "WIN" -> 1.0; "DRAW" -> 0.5; else -> 0.0 } } / 4
        return lower to lower + outcomes.count { it == "INCOMPLETE" } / 4.0
    }

    fun tuning(name: String) = when (name) {
        "CURRENT" -> LocalDecisionTuning.CURRENT
        "LEGACY" -> LocalDecisionTuning.LEGACY
        "CURRENT_UNCAPPED_LEAF" -> LocalDecisionTuning.CURRENT.copy(
            id = "current_uncapped_leaf", capLeafDamageToRemainingHp = false)
        "CURRENT_TEAM_COVERAGE" -> LocalDecisionTuning.CURRENT.copy(
            id = "current_team_coverage", leafTeamCoverageWeight = 0.25)
        "CURRENT_PUBLIC_MOVE_HYPOTHESES" -> LocalDecisionTuning.CURRENT.copy(
            id = "current_public_move_hypotheses", lookaheadMoveHypotheses = true)
        "CURRENT_PUBLIC_MOVE_HYPOTHESES_CAP3" -> LocalDecisionTuning.CURRENT.copy(
            id = "current_public_move_hypotheses_cap3", lookaheadMoveHypotheses = true,
            hypotheticalMoveLimitPerSlot = 3)
        "CURRENT_PUBLIC_MOVE_HYPOTHESES_CAP3_PRIORITY" -> LocalDecisionTuning.CURRENT.copy(
            id = "current_public_move_hypotheses_cap3_priority", lookaheadMoveHypotheses = true,
            hypotheticalMoveLimitPerSlot = 3, hypotheticalPriorityReservation = LocalHypothesisPriorityReservation.SINGLE)
        "CURRENT_PUBLIC_MOVE_HYPOTHESES_CAP3_PRIORITY_CONDITIONS" -> LocalDecisionTuning.CURRENT.copy(
            id = "current_public_move_hypotheses_cap3_priority_conditions", lookaheadMoveHypotheses = true,
            hypotheticalMoveLimitPerSlot = 3, hypotheticalPriorityReservation = LocalHypothesisPriorityReservation.CONDITION_GROUPS)
        else -> error("Supported arms: CURRENT, LEGACY, CURRENT_UNCAPPED_LEAF, CURRENT_TEAM_COVERAGE, CURRENT_PUBLIC_MOVE_HYPOTHESES, CURRENT_PUBLIC_MOVE_HYPOTHESES_CAP3, CURRENT_PUBLIC_MOVE_HYPOTHESES_CAP3_PRIORITY or CURRENT_PUBLIC_MOVE_HYPOTHESES_CAP3_PRIORITY_CONDITIONS")
    }

    fun runPair(engine: Path, pair: JsonObject, directory: Path, pairIndex: Int,
        challenger: LocalDecisionTuning, defender: LocalDecisionTuning, maxTurns: Int = 200,
        trainerProfile: BattleTrainerProfile = profileForSkill(0)): JsonObject {
        Files.createDirectory(directory)
        val games = JsonArray()
        val outcomes = mutableListOf<String>()
        schedule(pairIndex).forEachIndexed { index, assignment ->
            val p1 = if (assignment.challengerP1) challenger else defender
            val p2 = if (assignment.challengerP1) defender else challenger
            val result = EmbeddedTeamBattle.run(engine, EmbeddedNativePairs.orient(pair, assignment.reverseTeams),
                directory.resolve("game-$index"), maxTurns, p1, p2, trainerProfile)
            val outcome = EmbeddedNativePairs.outcome(result, reverse = !assignment.challengerP1)
            outcomes += outcome
            games.add(JsonObject().apply {
                addProperty("reverseTeams", assignment.reverseTeams)
                addProperty("challengerSeat", if (assignment.challengerP1) "p1" else "p2")
                addProperty("outcome", outcome); addProperty("result", "game-$index/result.json")
            })
        }
        val bounds = score(outcomes)
        val record = JsonObject().apply {
            addProperty("corpusKey", EmbeddedNativeCorpus.key(pair))
            addProperty("partition", EmbeddedNativeCorpus.split(pair).name)
            addProperty("scoreLower", bounds.first); addProperty("scoreUpper", bounds.second)
            add("games", games)
        }
        Files.writeString(directory.resolve("comparison.json"), record.toString(), CREATE_NEW)
        return record
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 9..10) { "Expected root, output, pairs, seed, challenger, defender, split, allowHoldout, maxTurns, optional skillLevel" }
        val root = Path.of(args[0]).toAbsolutePath()
        val directory = Path.of(args[1]).toAbsolutePath()
        val count = args[2].toInt().also { require(it in 1..1000) }
        val seed = args[3].toInt()
        val challenger = tuning(args[4]); val defender = tuning(args[5])
        val split = EvaluationSplit.valueOf(args[6])
        require(split != EvaluationSplit.HOLDOUT || args[7] == "true") { "Holdout requires explicit allowHoldout" }
        val maxTurns = args[8].toInt().also { require(it in 1..10000) }
        val trainerProfile = profileForSkill(args.getOrNull(9)?.toInt() ?: 0)
        val json = GsonBuilder().serializeNulls().create()
        val identity = LocalBaselineProvenance.capture(root)
        Files.createDirectories(directory.parent)
        Files.createDirectory(directory)
        val audit = EmbeddedPresetAudit.run(directory.resolve("audit"), teamPairs = count, teamSeed = seed, teamSplit = split)
        Files.writeString(directory.resolve("audit.json"), audit.toString(), CREATE_NEW)
        val manifest = JsonObject().apply {
            add("identity", json.toJsonTree(identity)); add("challenger", json.toJsonTree(challenger))
            add("defender", json.toJsonTree(defender)); addProperty("partition", split.name)
            addProperty("pairs", count); addProperty("samplingSeed", seed); addProperty("maxTurns", maxTurns)
            addProperty("gamesPerPair", 4); add("catalogSha256", audit["catalogSha256"].deepCopy())
            add("trainerProfile", json.toJsonTree(trainerProfile))
            addProperty("scope", "NATIVE_PARTIAL_ADAPTER_CURRENT_CODE_TUNING_COMPARISON_NOT_HISTORICAL_BINARY")
        }
        Files.writeString(directory.resolve("manifest.json"), manifest.toString(), CREATE_NEW)
        val records = JsonArray()
        audit.getAsJsonObject("teamSampling").getAsJsonArray("pairs").forEachIndexed { index, pair ->
            val record = runPair(directory.resolve("audit/engine"), pair.asJsonObject, directory.resolve("pair-$index"),
                index, challenger, defender, maxTurns, trainerProfile)
            record.addProperty("pairDirectory", "pair-$index"); records.add(record)
            println("NATIVE_POLICY_PAIR index=$index lower=${record["scoreLower"]} upper=${record["scoreUpper"]}")
        }
        check(LocalBaselineProvenance.capture(root) == identity) { "Inputs changed during native comparison" }
        val lower = records.map { it.asJsonObject["scoreLower"].asDouble }.average()
        val upper = records.map { it.asJsonObject["scoreUpper"].asDouble }.average()
        val outcomes = records.flatMap { it.asJsonObject.getAsJsonArray("games").map { game -> game.asJsonObject["outcome"].asString } }
        val summary = JsonObject().apply {
            addProperty("status", "COMPLETE"); addProperty("pairs", count); addProperty("games", outcomes.size)
            for (outcome in listOf("WIN", "LOSS", "DRAW", "INCOMPLETE")) addProperty(outcome.lowercase(), outcomes.count { it == outcome })
            addProperty("meanPairScoreLower", lower); addProperty("meanPairScoreUpper", upper)
            val interval = if (lower == upper) JsonObject().apply {
                val radius = sqrt(ln(40.0) / (2 * count))
                addProperty("lower", (lower - radius).coerceAtLeast(0.0))
                addProperty("upper", (upper + radius).coerceAtMost(1.0))
                addProperty("method", "HOEFFDING_95_PAIR_LEVEL_FIXED_SAMPLE")
            } else JsonNull.INSTANCE
            add("interval95", interval)
            addProperty("intervalAssumption", "INDEPENDENT_REPRESENTATIVE_PAIR_OUTCOMES_FIXED_SAMPLE_NOT_GUARANTEED_BY_PRNG")
            addProperty("qualityVerdict", "NO_AUTOMATIC_ADOPTION_PARTIAL_ADAPTER_AND_LIMITED_CORPUS")
            add("pairsDetail", records)
        }
        Files.writeString(directory.resolve("summary.json"), summary.toString(), CREATE_NEW)
        println("native policy comparison: $directory")
    }
}
