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
import jbro.cobblemon.mcc.betterai.simulation.LocalOpponentStatAssumption
import jbro.cobblemon.mcc.betterai.matchup.LocalMatchupScoreCalculator
import jbro.cobblemon.mcc.betterai.matchup.LocalOpponentIntentPredictor
import jbro.cobblemon.mcc.betterai.matchup.MatchupScores
import jbro.cobblemon.mcc.betterai.matchup.LocalSetupGate
import jbro.cobblemon.mcc.betterai.matchup.MatchupSpeedField
import jbro.cobblemon.mcc.betterai.matchup.MoveMatchupScore
import jbro.cobblemon.mcc.betterai.state.LocalOpponentMoveUsage
import jbro.cobblemon.mcc.betterai.state.LocalStatusMoveBinder
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleBrainCloseOutcome
import jbro.cobblemon.mcc.internal.ai.BattleBrainCloseResult
import jbro.cobblemon.mcc.internal.ai.BattleBrainOpenContext
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleMoveCandidateView
import jbro.cobblemon.mcc.internal.ai.BattlePublicMoveKnowledge
import jbro.cobblemon.mcc.internal.ai.BattlePublicMoveOptionView
import jbro.cobblemon.mcc.internal.ai.BattlePokemonActionCatalogView
import jbro.cobblemon.mcc.internal.ai.BattlePublicActionCatalogView
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
        /** The decision's matchup scores, one line per pair (see [matchupLines]). */
        val matchups: List<String> = emptyList(),
        /** Per opposing active slot, the predicted chance of each action key (see [actionKey]). */
        val intents: Map<Int, Map<String, Double>> = emptyMap(),
        /** Per opposing active slot, the plain guess: its strongest attack. */
        val strongest: Map<Int, String> = emptyMap(),
        /** Per own active slot, the real choice as an action key. */
        val realKeys: Map<Int, String> = emptyMap(),
        /** CPU time the whole JVM spent on the decision: unlike [millis], other processes do not count. */
        val cpuMillis: Long = 0,
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
            // Open team sheets: every opponent move is public from team preview on, so the sheet goes into the
            // public catalog as revealed moves. Passed only as the tier's hidden-set read, most of it was
            // dropped for usage-rate guesses (Focus Punch on a Rillaboom whose sheet says Fake Out).
            val sheet = sheetMoves(teams, side, base.state)
            val catalog = openSheet(base.publicActionCatalog, sheet, moveDetails)
            val inferences = inference.update(base.state, catalog, profile.difficulty.tier, sheet)
            val built = BattleDecisionContext(base.requestId, base.state, base.candidates, base.deadlineEpochMillis, base.memory,
                catalog.withOpponentMoveInferences(inferences))
            context = built
            captured.remove(side)
            deciding[side] = Thread.currentThread()
            val cpu = java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
            val cpuStarted = cpu.processCpuTime
            val started = System.nanoTime()
            val decision = brain.decide(session, built).toCompletableFuture().get(60, TimeUnit.SECONDS)
            val millis = (System.nanoTime() - started) / 1_000_000
            val cpuMillis = (cpu.processCpuTime - cpuStarted) / 1_000_000
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
            val view = ruleView(built, profile)
            val intents = LocalOpponentIntentPredictor.predict(view.context, view.scores)
            val species = view.context.state.pokemon.associate { it.battlePokemonId to canonical(it.speciesId) }
            Row(step, turn, side, forced, board(built.state), describe(input, built.state, realChoice),
                describe(input, built.state, decision.actionId) + (if (position >= 0) " [${position + 1}위/${finalRanked.size}]" else "") +
                    (if (mismatch) " **최종 결정이 선택기와 다름**" else ""), decision.actionId, ranked, decision.tags, millis,
                if (turn <= 1) NativeOpeningStateRules.acceptsObservations(built.state) else null, null,
                matchupLines(view) + intentLines(view, intents),
                intents = intents.associate { intent ->
                    intent.activeSlot to intent.options.groupBy { o -> o.moveId?.let { "move:$it" } ?: "switch:${species[o.switchInId]}" }
                        .mapValues { (_, same) -> same.sumOf { it.probability } }
                },
                strongest = view.context.state.pokemon.filter { it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted }
                    .mapNotNull { user ->
                        view.context.state.pokemon.filter { it.side == BattleSide.ALLY && it.activeSlot != null && !it.fainted }
                            .flatMap { view.scores.moves(user.battlePokemonId, it.battlePokemonId) }
                            .maxByOrNull { it.score }?.let { requireNotNull(user.activeSlot) to "move:${it.moveId}" }
                    }.toMap(),
                realKeys = actionKeys(input, realChoice), cpuMillis = cpuMillis)
        } catch (failure: Throwable) {
            Row(step, turn, side, forced, context?.state?.let(::board) ?: "", realChoice, null, null, emptyList(), emptySet(), 0,
                null, failure.toString().lines().first().take(300))
        }
    }

    private class RuleView(val context: BattleDecisionContext, val scores: MatchupScores, val millis: Long)

    /** The matchup scores the brain would see: the same catalog binding and opponent stat assumption. */
    private fun ruleView(context: BattleDecisionContext, profile: BattleTrainerProfile): RuleView {
        val tier = profile.difficulty.tier
        val bound = context.copy(publicActionCatalog = LocalStatusMoveBinder.bindCatalog(context.state, context.publicActionCatalog,
            tier, LocalOpponentMoveUsage.forFormat(context.state.format)))
        val assumed = LocalOpponentStatAssumption.applyToPublicState(bound, tier)
        val started = System.nanoTime()
        val scores = LocalMatchupScoreCalculator.calculate(assumed)
        return RuleView(assumed, scores, (System.nanoTime() - started) / 1_000_000)
    }

    private fun intentLines(view: RuleView, intents: List<jbro.cobblemon.mcc.betterai.matchup.OpponentIntent>): List<String> {
        val species = view.context.state.pokemon.associate { it.battlePokemonId to name(it.speciesId) }
        return intents.map { intent ->
            "의도 ${species[intent.pokemonId]}: " + intent.options.take(5).joinToString(" / ") { o ->
                val what = o.moveId?.let { m -> m + (o.targetId?.let { " → ${species[it]}" } ?: "") } ?: "교체 → ${species[o.switchInId]}"
                "$what ${"%.0f".format(Locale.ROOT, o.probability * 100)}% (${"%+.2f".format(Locale.ROOT, o.value)})"
            }
        }
    }

    /** A Showdown choice as one key per slot: `move:<id>` or `switch:<species>`. */
    fun actionKeys(input: JsonObject, choice: String): Map<Int, String> {
        val request = input.getAsJsonObject("request")
        val active = request.getAsJsonArray("active")
        val bench = request.getAsJsonObject("side").getAsJsonArray("pokemon")
        return choice.split(",").map { it.trim() }.mapIndexedNotNull { slot, part ->
            val words = part.split(" ")
            val index = words.getOrNull(1)?.toIntOrNull() ?: return@mapIndexedNotNull null
            when (words[0]) {
                "move" -> active?.get(slot)?.asJsonObject?.getAsJsonArray("moves")?.get(index - 1)?.asJsonObject
                    ?.let { m -> slot to "move:" + canonical((m["id"] ?: m["move"]).asString) }
                "switch" -> slot to "switch:" + canonical(bench[index - 1].asJsonObject["details"].asString.substringBefore(","))
                else -> null
            }
        }.toMap()
    }

    private fun matchupLines(view: RuleView): List<String> {
        val assumed = view.context
        val scores = view.scores
        val millis = view.millis
        val species = assumed.state.pokemon.associate { it.battlePokemonId to name(it.speciesId) }
        fun pct(value: Double) = "%.0f%%".format(Locale.ROOT, value * 100)
        fun move(score: MoveMatchupScore?) = score?.let {
            "${it.moveId} ${pct(it.minimumDamageFraction)}~${pct(it.maximumDamageFraction)} " +
                "1타 ${pct(it.knockoutChanceWithin(1))}/2타 ${pct(it.knockoutChanceWithin(2))}/3타 ${pct(it.knockoutChanceWithin(3))}"
        } ?: "공격 없음"
        val sweeps = scores.sweeps.values.sortedByDescending { it.score }.map { a ->
            "스위핑 ${species[a.subjectId]} ${"%.2f".format(Locale.ROOT, a.score)}: 그대로 ${pct(a.naturalSweep)}" +
                (a.setupMoveId?.let { " / ${it}×${a.setupUses} ${pct(a.boostedSweep)} (설치 턴 생존 ${pct(a.setupSafety)})" } ?: "")
        }
        val stops = scores.stops.values.sortedByDescending { it.score }.map { a ->
            "스토핑 ${species[a.subjectId]} → ${species[a.sweeperId]} ${"%.2f".format(Locale.ROOT, a.score)}: " +
                "먼저 행동 ${pct(a.actsBeforeKnockout)}" + (a.oneTimeSurvival?.let { ", 1회 생존 $it" } ?: "") + ", 도구 " +
                a.tools.take(3).joinToString(" / ") { "${it.kind.name.lowercase()}${it.moveId?.let { m -> "($m)" } ?: ""} ${pct(it.value)}" }
        }
        val gates = assumed.candidates.flatMap { c -> if (c.kind == BattleActionKind.COMPOSITE) c.componentActions else listOf(c) }
            .distinctBy { it.actionId }
            .mapNotNull { part -> LocalSetupGate.evaluate(part, assumed, scores)?.let { part to it } }
            .map { (part, verdict) ->
                "랭크업 게이트 ${species[assumed.state.pokemon.firstOrNull { it.side == BattleSide.ALLY && it.activeSlot == part.actorSlot }?.battlePokemonId]} " +
                    "${part.moveId}: ${if (verdict.passes) "통과" else "탈락 ${verdict.failures}"}"
            }
        fun signed(value: Double) = "%+.2f".format(Locale.ROOT, value)
        val active = assumed.state.pokemon.filter { it.activeSlot != null && !it.fainted }
        val statusLines = active.flatMap { user -> active.filter { it.side != user.side }.flatMap { target ->
            scores.statusMoves(user.battlePokemonId, target.battlePokemonId).map { m ->
                "변화기 ${species[m.userId]} ${m.moveId} → ${species[m.targetId]} ${signed(m.score)} (전 ${signed(m.before)} / 성공 ${signed(m.afterLanding)}, 턴 생존 ${pct(m.survivesTurn)})"
            } } }
        val switchLines = scores.switchIns.sortedByDescending { it.score }.map { w ->
            "교체 투입 ${species[w.incomingId]} ← ${species[w.replacedId]} vs ${species[w.opponentId]} ${signed(w.score)}: " +
                "예상 ${w.predictedMoveId} 생존 ${pct(w.predictedSurvival)} 남는 HP ${if (w.predictedSurvival > 0.0) pct(w.hpAfterEntry) else "-"}, 최악 ${w.worstMoveId} 생존 ${pct(w.worstSurvival)}"
        }
        val preserveLines = scores.preserves.values.filter { p -> assumed.state.pokemon.any { it.battlePokemonId == p.subjectId && it.side == BattleSide.ALLY } }
            .sortedByDescending { it.score }.map { p ->
                "보존 ${species[p.subjectId]} ${"%.2f".format(Locale.ROOT, p.score)}: 팀 커버 ${pct(p.coverageWith)} → 빠지면 ${pct(p.coverageWithout)}" +
                    (if (p.soleAnswerTo.isEmpty()) "" else ", 유일한 답 ${p.soleAnswerTo.map { species[it] }}")
            }
        return listOf("계산 ${millis}ms") + gates + sweeps + stops + preserveLines + statusLines + switchLines + scores.pokemonMatchups.filter { it.speedField == MatchupSpeedField.CURRENT }.map { m ->
            val reversed = scores.pokemon(m.subjectId, m.opponentId, MatchupSpeedField.TRICK_ROOM_TOGGLED)
            "${species[m.subjectId]} vs ${species[m.opponentId]}: 점수 ${"%+.2f".format(Locale.ROOT, m.score)}" +
                " (트릭룸 반전 ${reversed?.let { "%+.2f".format(Locale.ROOT, it.score) } ?: "-"}), 승 ${pct(m.winProbability)}" +
                " 이기면 내 HP ${pct(m.subjectRemainingHpOnWin)} / 지면 상대 HP ${pct(m.opponentRemainingHpOnLoss)}, 선공 ${pct(m.subjectMovesFirstProbability)}" +
                "; 나: ${move(m.subjectMove)}; 상대: ${move(m.opponentMove)}" +
                "; 내 기술 ${scores.moves(m.subjectId, m.opponentId).joinToString(", ") { "${it.moveId} ${"%.2f".format(Locale.ROOT, it.score)}" }}" +
                "; 상대 기술 ${scores.moves(m.opponentId, m.subjectId).joinToString(", ") { "${it.moveId} ${"%.2f".format(Locale.ROOT, it.score)}" }}"
        }
    }

    /** [catalog] with each opponent's sheet moves added as revealed, and its move set complete. */
    private fun openSheet(
        catalog: BattlePublicActionCatalogView,
        sheet: Map<UUID, Set<String>>,
        moveDetails: Map<String, BattleMoveCandidateView>,
    ): BattlePublicActionCatalogView {
        if (sheet.isEmpty()) return catalog
        val existing = catalog.entries.associateBy { it.battlePokemonId }
        val opened = sheet.map { (pokemonId, moves) ->
            val known = existing[pokemonId]?.moves.orEmpty()
            val added = moves.filter { id -> known.none { canonical(it.moveId) == id } }
                .mapNotNull { id -> moveDetails[id]?.let { BattlePublicMoveOptionView(id, it, BattlePublicMoveKnowledge.PUBLICLY_REVEALED) } }
            BattlePokemonActionCatalogView(pokemonId, known + added, moveSetComplete = true)
        }
        val openedIds = opened.mapTo(hashSetOf()) { it.battlePokemonId }
        return BattlePublicActionCatalogView(catalog.entries.filterNot { it.battlePokemonId in openedIds } + opened,
            catalog.originalEntries, catalog.candidatePools)
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
