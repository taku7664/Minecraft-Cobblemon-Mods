package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.brain.LocalTacticalBrain
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

/** First-request replay by default; explicit snapshots use the native adapter's default memory. */
internal object EmbeddedFirstDecisionReplay {
    fun replayProfile(skill: Int, depth: Int?): BattleTrainerProfile {
        val profile = EmbeddedPolicyComparison.profileForSkill(skill)
        if (depth == null) return profile
        require(depth in 1..4)
        return profile.copy(difficulty = profile.difficulty.copy(lookaheadPlies = depth))
    }

    fun repetitionCount(raw: String?): Int = (raw?.toInt() ?: 1).also { require(it in 1..20) }

    fun choiceSeedOverride(raw: String?): Long? = raw?.toLong()

    fun snapshotRequest(lines: Sequence<String>, side: String, index: Int): JsonObject {
        require(side == "p1" || side == "p2")
        require(index >= 0)
        return lines.map { JsonParser.parseString(it).asJsonObject }
            .filter { it["side"].asString == side }.drop(index).first()
    }

    /** Diagnostic ablation only; never exposed as an adopted production tuning. */
    fun tuningFor(name: String): LocalDecisionTuning = when (name) {
        "CURRENT" -> LocalDecisionTuning.CURRENT
        "LEGACY" -> LocalDecisionTuning.LEGACY
        "CURRENT_TEAM_COVERAGE",
        "CURRENT_PUBLIC_MOVE_HYPOTHESES",
        "CURRENT_PUBLIC_MOVE_HYPOTHESES_CAP3",
        "CURRENT_PUBLIC_MOVE_HYPOTHESES_CAP3_PRIORITY",
        "CURRENT_PUBLIC_MOVE_HYPOTHESES_CAP3_PRIORITY_CONDITIONS" -> EmbeddedPolicyComparison.tuning(name)
        "CURRENT_NO_COVERAGE_FLOOR" -> LocalDecisionTuning.CURRENT.copy(
            id = "current_no_coverage_floor", lookaheadCoverageFloor = 0.0)
        "CURRENT_NO_KO_CREDIT" -> LocalDecisionTuning.CURRENT.copy(
            id = "current_no_ko_credit", knockoutMaterialScore = 0.0)
        else -> throw IllegalArgumentException("Unsupported replay tuning: $name")
    }

    fun firstRequest(lines: Sequence<String>, side: String): JsonObject {
        require(side == "p1" || side == "p2")
        val row = lines.map { JsonParser.parseString(it).asJsonObject }
            .first { it["side"].asString == side }
        require(row["turn"].asInt == 1 && !row.getAsJsonObject("input")
            .getAsJsonObject("request").has("forceSwitch")) {
            "Only the first ordinary request on turn one can be replayed without session history"
        }
        return row
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 5..9) { "Expected trace, side, battle UUID, skill level, replay tuning, optional snapshot index, repetitions, depth and choice seed" }
        val repetitions = repetitionCount(args.getOrNull(6))
        repeat(repetitions) { replay(args, it, repetitions) }
    }

    private fun replay(args: Array<String>, repetition: Int, repetitions: Int) {
        val snapshotIndex = args.getOrNull(5)?.toInt()
        val row = Files.newBufferedReader(Path.of(args[0])).use {
            if (snapshotIndex == null) firstRequest(it.lineSequence(), args[1])
            else snapshotRequest(it.lineSequence(), args[1], snapshotIndex)
        }
        val battleId = UUID.fromString(args[2])
        val profile = replayProfile(args[3].toInt(), args.getOrNull(7)?.toInt())
        val tuning = tuningFor(args[4])
        // Native input reconstructs public observations from the snapshot and uses default tactical
        // memory. This is NOT a production-session replay. The request UUID nonce is synthetic here;
        // LocalTacticalBrain's choice seed uses battle ID, turn and ranks, not that nonce.
        val turn = row["turn"].asInt
        val context = EmbeddedTeamInput.context(row.getAsJsonObject("input"), battleId, turn, snapshotIndex ?: 0)
        val choiceSeedOverride = choiceSeedOverride(args.getOrNull(8))
        val observer = LocalDecisionTraceSelector(choiceSeedOverride)
        val brain = LocalTacticalBrain(actionSelector = observer, tuning = tuning)
        val session = brain.openSession(BattleBrainOpenContext(battleId, context.state.format, trainerProfile = profile))
        try {
            val started = System.nanoTime()
            val decision = brain.decide(session, context).toCompletableFuture().get(25, TimeUnit.SECONDS)
            val elapsedNanos = System.nanoTime() - started
            val trace = observer.latest
            val report = mapOf(
                "scope" to if (snapshotIndex == null) "FIRST_REQUEST_CURRENT_CODE_REPLAY_NOT_HISTORICAL_BINARY"
                    else "NATIVE_INPUT_SNAPSHOT_DEFAULT_MEMORY_NOT_PRODUCTION_SESSION_REPLAY",
                "snapshotIndex" to snapshotIndex,
                "repetition" to repetition, "repetitions" to repetitions,
                "decisionElapsedNanos" to elapsedNanos,
                "battleId" to battleId.toString(), "profile" to profile, "tuning" to tuning.id,
                "recordedAction" to row["actionId"].asString, "replayedAction" to decision.actionId,
                "tags" to decision.tags, "seed" to trace?.selection?.seed,
                "choiceSeedMode" to if (choiceSeedOverride == null) "PRODUCTION_DERIVED" else "FIXED_DIAGNOSTIC",
                // Decimal strings preserve all 64 bits in consumers with floating-point JSON numbers.
                "derivedChoiceSeed" to trace?.seed?.toString(),
                "choiceSeedOverride" to choiceSeedOverride?.toString(),
                "riskBudget" to trace?.mixing?.riskBudget,
                "shortlistSize" to trace?.selection?.shortlistSize,
                "selectionProbability" to trace?.selection?.probability,
                "publicOpponentMoves" to context.state.pokemon.filter { it.side == BattleSide.OPPONENT }
                    .associate { it.battlePokemonId.toString() to it.knownMoveIds },
                "candidates" to trace?.ranked?.map { rank -> mapOf(
                    "actionId" to rank.outcome.candidate.actionId,
                    "publicCalculationFacts" to rank.outcome.candidate.facts,
                    "tier" to rank.decisionTier, "total" to rank.comparisonValue,
                    "tactical" to rank.outcome.tacticalUtility, "knockout" to rank.outcome.knockoutUtility,
                    "lookahead" to rank.lookaheadUtility,
                    "survivalImprovement" to rank.outcome.survivalPositionImprovement,
                    "executionProbability" to rank.executionProbability,
                    "worstResponseHpRetention" to rank.worstResponseHpRetention,
                ) },
            )
            println(GsonBuilder().serializeNulls().create().toJson(report))
        } finally {
            brain.closeSession(session, BattleBrainCloseResult(BattleBrainCloseOutcome.CANCELLED, 1))
        }
    }
}
