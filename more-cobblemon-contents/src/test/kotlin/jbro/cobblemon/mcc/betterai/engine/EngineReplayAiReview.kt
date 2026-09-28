package jbro.cobblemon.mcc.betterai.engine

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.file.Path
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import jbro.cobblemon.mcc.betterai.EmbeddedTeamBattle
import jbro.cobblemon.mcc.betterai.EmbeddedTeamInput
import jbro.cobblemon.mcc.betterai.brain.LocalTacticalBrain
import jbro.cobblemon.mcc.betterai.policy.LocalActionSelector
import jbro.cobblemon.mcc.betterai.policy.LocalBattleActionRank
import jbro.cobblemon.mcc.betterai.policy.LocalWeightedActionSelector
import jbro.cobblemon.mcc.betterai.simulation.NativeOpeningStateRules
import jbro.cobblemon.mcc.internal.ai.BattleBrainCloseOutcome
import jbro.cobblemon.mcc.internal.ai.BattleBrainCloseResult
import jbro.cobblemon.mcc.internal.ai.BattleBrainOpenContext
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleMoveCandidateView
import jbro.cobblemon.mcc.internal.ai.BattleOpponentMoveInferenceLedger
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.BattleTacticalMemoryLedger
import jbro.cobblemon.mcc.internal.ai.BattleTrainerProfile

/**
 * Our AI's view of a real battle. The real players' choices are played on Showdown (the team bridge) under
 * a seed whose battle follows the replay; at every request each side's AI is asked what it would do, with
 * the same inputs the embedded team battles give it. Nothing here makes the AI agree with the players: the
 * rows are for reading odd choices and failures.
 */
internal object EngineReplayAiReview {
    class Row(
        val step: Int,
        val turn: Int,
        val side: String,
        val forced: Boolean,
        val board: String,
        val real: String,
        val ai: String?,
        val aiRaw: String?,
        val ranked: List<String>,
        val tags: Set<String>,
        val millis: Long,
        val openingAccepted: Boolean?,
        val failure: String?,
    )

    class Result(val rows: List<Row>, val publicLog: List<String>, val stoppedAt: String?)

    fun run(
        teams: Map<String, List<RefSet>>,
        gameType: String,
        seed: IntArray,
        choices: List<Pair<String, String>>,
        engine: Path,
        directory: Path,
    ): Result {
        val format = if (gameType == "doubles") BattleFormat.DOUBLE else BattleFormat.SINGLE
        val pair = JsonObject().apply {
            addProperty("battleFormat", format.name)
            add("battleSeed", JsonArray().also { a -> seed.forEach { a.add(it) } })
            addProperty("scriptedChoices", true)
            for (side in listOf("p1", "p2")) add(side, JsonObject().apply {
                add("sets", JsonArray().also { a -> teams[side].orEmpty().forEachIndexed { i, s -> a.add(s.toJson(side, i)) } })
            })
        }
        val battleId = UUID.nameUUIDFromBytes(pair.toString().toByteArray())
        // The search also calls the selector on tentative rankings, from its own threads and sometimes after it
        // stopped; the final choice is the last call made on the thread that asked for the decision.
        val captured = java.util.concurrent.ConcurrentHashMap<String, Pair<List<LocalBattleActionRank>, jbro.cobblemon.mcc.betterai.policy.LocalActionSelection>>()
        val deciding = java.util.concurrent.ConcurrentHashMap<String, Thread>()
        val weighted = LocalWeightedActionSelector()
        val brains = listOf("p1", "p2").associateWith { side ->
            LocalTacticalBrain(actionSelector = LocalActionSelector { ranked, s, mixing ->
                weighted.choose(ranked, s, mixing).also { selection ->
                    if (Thread.currentThread() === deciding[side]) captured[side] = ranked to selection
                }
            })
        }
        // The strongest tier the game offers, so odd choices are not the difficulty's deliberate mistakes.
        val profile = BattleTrainerProfile.boss()
        val opens = brains.keys.associateWith { BattleBrainOpenContext(battleId, format, trainerProfile = profile) }
        val sessions = brains.mapValues { (side, brain) -> brain.openSession(opens.getValue(side)) }
        val memories = opens.mapValues { (_, open) -> BattleTacticalMemoryLedger(open) }
        val moveDetails = brains.keys.associateWith { linkedMapOf<String, BattleMoveCandidateView>() }
        val inference = brains.keys.associateWith { side ->
            BattleOpponentMoveInferenceLedger { id -> moveDetails.getValue(side)[canonical(id)] }
        }
        val rows = ArrayList<Row>()
        var publicLog: List<String> = emptyList()
        var stopped: String? = null
        EmbeddedTeamBattle.NativeSession(engine, pair, directory, 60).use { native ->
            for (step in 0 until choices.size + 1) {
                val frame = native.read()
                publicLog = frame.getAsJsonArray("publicLog")?.map { it.asString } ?: publicLog
                if (frame["status"].asString != "WAITING") break
                val real = choices.getOrNull(step) ?: run { stopped = "the real battle has no choice for step $step"; null } ?: break
                val send = JsonObject()
                for (value in frame.getAsJsonArray("requests")) {
                    val input = value.asJsonObject
                    val side = input["side"].asString
                    val realChoice = if (side == "p1") real.first else real.second
                    if (realChoice.isEmpty()) {
                        stopped = "step $step: Showdown asks $side, the replay does not"
                        break
                    }
                    send.addProperty(side, realChoice)
                    rows += decide(step, frame["turn"].asInt, side, input, realChoice, battleId, brains.getValue(side),
                        sessions.getValue(side), memories.getValue(side), moveDetails.getValue(side), inference.getValue(side),
                        profile, teams, captured, deciding)
                }
                if (stopped != null) break
                native.send(JsonObject().apply { add("choices", send) })
            }
        }
        brains.forEach { (side, brain) ->
            brain.closeSession(sessions.getValue(side), BattleBrainCloseResult(BattleBrainCloseOutcome.CANCELLED, rows.count { it.side == side }))
        }
        return Result(rows, publicLog, stopped)
    }

    private fun decide(
        step: Int, turn: Int, side: String, input: JsonObject, realChoice: String, battleId: UUID,
        brain: LocalTacticalBrain, session: jbro.cobblemon.mcc.internal.ai.BattleBrainSession, memory: BattleTacticalMemoryLedger,
        moveDetails: MutableMap<String, BattleMoveCandidateView>, inference: BattleOpponentMoveInferenceLedger,
        profile: BattleTrainerProfile, teams: Map<String, List<RefSet>>,
        captured: MutableMap<String, Pair<List<LocalBattleActionRank>, jbro.cobblemon.mcc.betterai.policy.LocalActionSelection>>, deciding: MutableMap<String, Thread>,
    ): Row {
        val forced = input.getAsJsonObject("request").has("forceSwitch")
        var context: BattleDecisionContext? = null
        return try {
            val base = EmbeddedTeamInput.context(input, battleId, turn, step) { state ->
                memory.observe(state)
                memory.view(state.turn)
            }
            base.publicActionCatalog.entries.forEach { entry -> entry.moves.forEach { moveDetails[canonical(it.moveId)] = it.details } }
            base.publicActionCatalog.candidatePools.forEach { pool -> pool.moveDetails.forEach { (id, d) -> moveDetails[canonical(id)] = d } }
            // Open team sheets: every opponent move is public from team preview on.
            val inferences = inference.update(base.state, base.publicActionCatalog, profile.difficulty.tier, sheetMoves(teams, side, base.state))
            val built = BattleDecisionContext(base.requestId, base.state, base.candidates, base.deadlineEpochMillis, base.memory,
                base.publicActionCatalog.withOpponentMoveInferences(inferences))
            context = built
            captured.remove(side)
            deciding[side] = Thread.currentThread()
            val started = System.nanoTime()
            val decision = brain.decide(session, built).toCompletableFuture().get(60, TimeUnit.SECONDS)
            val millis = (System.nanoTime() - started) / 1_000_000
            val selected = built.candidates.single { it.actionId == decision.actionId }
            memory.accept(built.state, selected, decision.advice)
            val finalRanked = captured[side]?.first.orEmpty()
            val selection = captured[side]?.second
            val mismatch = selection != null && selection.rank.outcome.candidate.actionId != decision.actionId
            val ranked = finalRanked.map { rank ->
                val id = rank.outcome.candidate.actionId
                val weight = selection?.probabilitiesByActionId?.get(id)?.let { "확률 ${"%.0f".format(Locale.ROOT, it * 100)}%" }
                val excluded = selection?.exclusionsByActionId?.get(id)?.let { "제외: $it" }
                val damage = rank.outcome.componentOutcomes.ifEmpty { listOf(rank.outcome) }
                    .joinToString("/") { "%.2f".format(Locale.ROOT, it.expectedDamageFraction) }
                "${describe(input, built.state, id)} (값 ${"%.1f".format(Locale.ROOT, rank.comparisonValue)}, 예상 피해 $damage, ${weight ?: excluded ?: "-"})"
            }
            val position = finalRanked.indexOfFirst { it.outcome.candidate.actionId == decision.actionId }
            Row(step, turn, side, forced, board(built.state), describe(input, built.state, realChoice),
                describe(input, built.state, decision.actionId) + (if (position >= 0) " [${position + 1}위/${finalRanked.size}]" else "") +
                    (if (mismatch) " **최종 결정이 선택기와 다름**" else ""), decision.actionId, ranked, decision.tags, millis,
                if (turn <= 1) NativeOpeningStateRules.acceptsObservations(built.state) else null, null)
        } catch (failure: Throwable) {
            Row(step, turn, side, forced, context?.state?.let(::board) ?: "", realChoice, null, null, emptyList(), emptySet(), 0,
                null, failure.toString().lines().first().take(300))
        }
    }

    private fun sheetMoves(teams: Map<String, List<RefSet>>, side: String, state: BattleStateView): Map<UUID, Set<String>> {
        val opponent = if (side == "p1") "p2" else "p1"
        val bySpecies = teams[opponent].orEmpty().associate { canonical(it.species) to it.moves.map(::canonical).toSet() }
        return state.pokemon.filter { it.side == BattleSide.OPPONENT }
            .mapNotNull { p -> bySpecies[canonical(p.speciesId)]?.let { p.battlePokemonId to it } }.toMap()
    }

    /** Active Pokemon with HP, as the deciding side sees them. */
    private fun board(state: BattleStateView): String = listOf(BattleSide.ALLY, BattleSide.OPPONENT).joinToString(" / ") { side ->
        (if (side == BattleSide.ALLY) "우리 " else "상대 ") + state.pokemon.filter { it.side == side && it.activeSlot != null && !it.fainted }
            .sortedBy { it.activeSlot }.joinToString(", ") { p ->
                "${name(p.speciesId)} ${(p.hpFraction * 100).toInt()}%" + (p.statusId?.let { " $it" } ?: "")
            }
    }

    /** A Showdown choice ("move 1 2 terastallize, switch 3") as move names and target species. */
    fun describe(input: JsonObject, state: BattleStateView, choice: String): String {
        val request = input.getAsJsonObject("request")
        val active = request.getAsJsonArray("active")
        val bench = request.getAsJsonObject("side").getAsJsonArray("pokemon")
        return choice.split(",").map { it.trim() }.mapIndexed { slot, part ->
            val words = part.split(" ")
            when (words[0]) {
                "move" -> {
                    val moves = active?.get(slot)?.asJsonObject?.getAsJsonArray("moves")
                    val move = moves?.get(words[1].toInt() - 1)?.asJsonObject?.get("move")?.asString ?: "기술 ${words[1]}"
                    val loc = words.getOrNull(2)?.toIntOrNull()
                    val target = loc?.let {
                        val targetSide = if (it > 0) BattleSide.OPPONENT else BattleSide.ALLY
                        state.pokemon.firstOrNull { p -> p.side == targetSide && p.activeSlot == kotlin.math.abs(it) - 1 && !p.fainted }
                            ?.let { p -> (if (it > 0) "" else "아군 ") + name(p.speciesId) } ?: "빈 자리($it)"
                    }
                    move + (target?.let { " → $it" } ?: "") + if ("terastallize" in words) " (테라스탈)" else ""
                }
                "switch" -> "교체 → " + (bench[words[1].toInt() - 1].asJsonObject["details"].asString.substringBefore(","))
                "pass" -> "대기"
                else -> part
            }
        }.joinToString(" + ")
    }

    private fun name(speciesId: String) = speciesId.substringAfter(':').replaceFirstChar { it.uppercase() }

    private fun canonical(value: String): String = value.substringAfter(':').lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
}
