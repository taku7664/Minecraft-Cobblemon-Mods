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

/** Fresh-session first-request replay only; does not claim to restore later plan/session history. */
internal object EmbeddedFirstDecisionReplay {
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
        require(args.size == 5) { "Expected trace, side, battle UUID, skill level, CURRENT or LEGACY" }
        val row = Files.newBufferedReader(Path.of(args[0])).use { firstRequest(it.lineSequence(), args[1]) }
        val battleId = UUID.fromString(args[2])
        val profile = EmbeddedPolicyComparison.profileForSkill(args[3].toInt())
        val tuning = when (args[4]) {
            "CURRENT" -> LocalDecisionTuning.CURRENT
            "LEGACY" -> LocalDecisionTuning.LEGACY
            else -> error("Unsupported tuning")
        }
        val context = EmbeddedTeamInput.context(row.getAsJsonObject("input"), battleId, 1, 0)
        val observer = LocalDecisionTraceSelector()
        val brain = LocalTacticalBrain(actionSelector = observer, tuning = tuning)
        val session = brain.openSession(BattleBrainOpenContext(battleId, context.state.format, trainerProfile = profile))
        try {
            val decision = brain.decide(session, context).toCompletableFuture().get(25, TimeUnit.SECONDS)
            val trace = observer.latest
            val report = mapOf(
                "scope" to "FIRST_REQUEST_CURRENT_CODE_REPLAY_NOT_HISTORICAL_BINARY",
                "battleId" to battleId.toString(), "profile" to profile, "tuning" to tuning.id,
                "recordedAction" to row["actionId"].asString, "replayedAction" to decision.actionId,
                "tags" to decision.tags, "seed" to trace?.seed, "riskBudget" to trace?.mixing?.riskBudget,
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
