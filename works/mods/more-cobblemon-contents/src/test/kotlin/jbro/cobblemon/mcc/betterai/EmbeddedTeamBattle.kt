package jbro.cobblemon.mcc.betterai

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.brain.LocalTacticalBrain
import jbro.cobblemon.mcc.betterai.search.NativeInitialProductDecisionEvaluation
import jbro.cobblemon.mcc.betterai.search.NativeInitialProductDecisionEvaluator
import jbro.cobblemon.mcc.betterai.search.NativeInitialProductDecisionStatus
import jbro.cobblemon.mcc.betterai.search.NativeProductSearchRunner
import jbro.cobblemon.mcc.betterai.search.NativeProductSessionReconciler
import jbro.cobblemon.mcc.betterai.search.NativeProductWorldSearchAggregator
import jbro.cobblemon.mcc.betterai.simulation.EngineBranchWorker
import jbro.cobblemon.mcc.betterai.brain.AiTestDecisionSnapshot
import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.matchup.LocalOwnStageValue
import jbro.cobblemon.mcc.internal.ai.BattleOpponentMoveInferenceLedger
import jbro.cobblemon.mcc.internal.ai.BattleTacticalMemoryLedger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object EmbeddedTeamBattle {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 3) { "Expected new output directory, pair count and sampling seed" }
        val directory = Path.of(args[0]).toAbsolutePath()
        Files.createDirectories(directory.parent)
        Files.createDirectory(directory)
        val audit = EmbeddedPresetAudit.run(directory.resolve("audit"), teamPairs = args[1].toInt(), teamSeed = args[2].toInt())
        Files.writeString(directory.resolve("audit.json"), audit.toString(), CREATE_NEW)
        val pairs = audit.getAsJsonObject("teamSampling").getAsJsonArray("pairs")
        pairs.forEachIndexed { index, pair ->
            val result = run(directory.resolve("audit/engine"), pair.asJsonObject, directory.resolve("battle-$index"))
            println("NATIVE_TEAM_BATTLE index=$index status=${result["status"]} turn=${result["turn"]} decisions=${result["decisions"]} forced=${result["forcedSwitchDecisions"]} winner=${result["winner"]}")
        }
        println("native team capture: $directory")
    }

    fun run(engine: Path, pair: JsonObject, directory: Path, maxTurns: Int = 200,
        p1Tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
        p2Tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
        trainerProfile: BattleTrainerProfile = BattleTrainerProfile.balanced(),
        p1TrainerProfile: BattleTrainerProfile = trainerProfile,
        p2TrainerProfile: BattleTrainerProfile = trainerProfile,
        /** Sides whose native session does not correct hidden stats from observed damage. */
        withoutStatFitting: Set<String> = emptySet(),
        /** Sides deciding without any search, and whether the quick decider runs for them. */
        searchlessSides: Map<String, Boolean> = emptyMap(),
        /** Sides that price unpriced status moves in the engine (LocalNativeStatusValue); null leaves the brain's default. */
        nativeStatusSides: Set<String>? = null,
        /** Choices played instead of the brain's, by turn then side ("move 1", "switch 2"); forced switches stay the brain's. */
        scripted: Map<Int, Map<String, String>> = emptyMap(),
        /** Trainer persona per side; an `ai_test:` one saves each decision as a replayable snapshot, as in the game. */
        trainerPersonaIds: Map<String, String> = emptyMap(),
        /**
         * A recorded game (its decisions.jsonl.gz) to follow: each side still decides, but the recorded action is played,
         * so every position is the recorded one and the two decisions can be compared on it (trace "ownActionId").
         */
        followed: Path? = null): JsonObject {
        val recorded = followed?.let { file ->
            java.util.zip.GZIPInputStream(Files.newInputStream(file)).bufferedReader().readLines()
                .map { JsonParser.parseString(it).asJsonObject }
                .groupBy({ it["side"].asString }, { it["turn"].asInt to it["actionId"].asString })
                .mapValues { ArrayDeque(it.value) }
        }
        val battleId = UUID.nameUUIDFromBytes(pair["battleSeed"].toString().toByteArray())
        val format = pair["battleFormat"]?.asString?.let(BattleFormat::valueOf) ?: BattleFormat.SINGLE
        val tunings = mapOf("p1" to p1Tuning, "p2" to p2Tuning)
        val profiles = mapOf("p1" to p1TrainerProfile, "p2" to p2TrainerProfile)
        // The last native evaluation per side, so the trace shows whether native search ran and why it did not.
        val nativeEvaluations = java.util.concurrent.ConcurrentHashMap<String, NativeInitialProductDecisionEvaluation>()
        val nativeWorkers = mutableListOf<EngineBranchWorker>()
        // Each side's last decision broken down by stage, written into its trace row.
        val stages = java.util.concurrent.ConcurrentHashMap<String, jbro.cobblemon.mcc.betterai.brain.LocalDecisionStages>()
        val brains = tunings.mapValues { (side, tuning) ->
            // The game leases a worker from the server's native runtime; a test has none, so each side owns one.
            val worker = EngineBranchWorker().also(nativeWorkers::add)
            val evaluator = NativeInitialProductDecisionEvaluator(
                searchWorlds = NativeProductWorldSearchAggregator(
                    runWorld = NativeProductSearchRunner(lease = { _, action -> action(worker) })::run)::search,
                reconcileSession = NativeProductSessionReconciler(lease = { _, action -> action(worker) },
                    fitHiddenStats = side !in withoutStatFitting)::reconcile,
            )
            LocalTacticalBrain(tuning = tuning, searchless = side in searchlessSides,
                quickDecider = searchlessSides[side] ?: true,
                // The engine itself is borrowed through LocalEngineStopProbe.lease, which the caller sets.
                nativeStatus = nativeStatusSides == null || side in nativeStatusSides,
                stageTrace = { stages[side] = it },
                nativeInitialDecision = { context, profile, localTuning, budget, sessionState, rules ->
                evaluator.evaluate(context, profile, localTuning, budget, sessionState, rules).also { evaluation ->
                    nativeEvaluations[side] = evaluation
                    // A failed native decision is kept whole, so the planner can be replayed on it.
                    if (evaluation.status != NativeInitialProductDecisionStatus.AVAILABLE &&
                        evaluation.status != NativeInitialProductDecisionStatus.NOT_APPLICABLE) {
                        Files.writeString(directory.resolve("native-$side-turn${context.state.turn}-${context.requestId.toString().take(8)}.json"),
                            AiTestDecisionSnapshot.toJson(AiTestDecisionSnapshot(battleId = battleId, turn = context.state.turn,
                                trainerPersonaId = null, trainerProfile = profile, strategy = null,
                                nativeContinuation = sessionState != null, capturedAtEpochMillis = System.currentTimeMillis(),
                                context = context)))
                    }
                }
            })
        }
        val openContexts = brains.keys.associateWith { side ->
            BattleBrainOpenContext(battleId, format, trainerProfile = profiles.getValue(side), trainerPersonaId = trainerPersonaIds[side])
        }
        val sessions = brains.mapValues { (side, brain) -> brain.openSession(openContexts.getValue(side)) }
        val memories = openContexts.mapValues { (_, context) -> BattleTacticalMemoryLedger(context) }
        val inferenceMoveDetails = brains.keys.associateWith { linkedMapOf<String, BattleMoveCandidateView>() }
        val inferenceLedgers = brains.keys.associateWith { side ->
            BattleOpponentMoveInferenceLedger(jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax::isMaxMove) { moveId ->
                inferenceMoveDetails.getValue(side)[canonical(moveId)] }
        }
        var final: JsonObject? = null
        val counts = mutableMapOf("p1" to 0, "p2" to 0)
        var forced = 0
        var effectAnnotatedCandidates = 0
        try {
            NativeSession(engine, pair, directory, maxTurns).use { native ->
                Files.newBufferedWriter(directory.resolve("decisions.jsonl"), CREATE_NEW).use { trace ->
                    repeat(maxTurns * 8 + 20) { round ->
                        val frame = native.read()
                        if (frame["status"].asString != "WAITING") {
                            check(frame["status"].asString in setOf("COMPLETE", "TURN_LIMIT"))
                            native.awaitExit()
                            final = frame
                            frame.addProperty("decisions", counts.values.sum())
                            frame.add("decisionsBySide", JsonObject().apply { counts.forEach { (side, count) -> addProperty(side, count) } })
                            frame.addProperty("forcedSwitchDecisions", forced)
                            frame.addProperty("illegalChoices", 0) // Any illegal/rejected choice aborts instead of producing a result.
                            frame.addProperty("effectAnnotatedCandidates", effectAnnotatedCandidates)
                            frame.add("policyTuning", com.google.gson.Gson().toJsonTree(tunings))
                            frame.add("trainerProfiles", com.google.gson.Gson().toJsonTree(profiles))
                            frame.addProperty("evidence", "NATIVE_LOCAL_BRAIN_TEAMS_PARTIAL_INPUT_ADAPTER_NOT_QUALITY_PROOF")
                            frame.addProperty("adapterLimits", "PARTIAL_DECLARATIVE_EFFECTS_NO_CALLBACK_EXECUTION;PARTIAL_PUBLIC_EVENTS_AND_VOLATILES;UNKNOWN_EFFECT_DURATIONS;MEGA_ONLY_GIMMICK_CANDIDATES;INCOMPLETE_FUTURE_PP;NO_RUNTIME_ADDONS")
                            Files.writeString(directory.resolve("result.json"), frame.toString(), CREATE_NEW)
                            return frame
                        }
                        val choices = JsonObject()
                        frame.getAsJsonArray("requests").forEach { value ->
                            val input = value.asJsonObject
                            val side = input["side"].asString
                            val memory = memories.getValue(side)
                            val baseContext = EmbeddedTeamInput.context(input, battleId, frame["turn"].asInt, round) { state ->
                                memory.observe(state)
                                memory.view(state.turn)
                            }
                            // Inference and decision together, as the game spends them on one request.
                            val decisionStarted = System.nanoTime()
                            registerMoveDetails(baseContext.publicActionCatalog, inferenceMoveDetails.getValue(side))
                            // As the game does: an attack's own stat changes read on this board, the trainer's own team exact.
                            val stageBoard = EmbeddedTeamInput.nativeInputs(input)?.let { (preview, own) ->
                                baseContext.copy(opponentTeamPreview = preview, exactOwnTeam = own,
                                    localOpponentStatSpreads = if (profiles.getValue(side).difficulty.tier in setOf(
                                            BattleTrainerTier.ADVANCED, BattleTrainerTier.BOSS)) {
                                        opponentSpreads(pair, if (side == "p1") "p2" else "p1", preview)
                                    } else emptyMap())
                            } ?: baseContext
                            val inferences = inferenceLedgers.getValue(side).update(
                                baseContext.state,
                                baseContext.publicActionCatalog,
                                profiles.getValue(side).difficulty.tier,
                                actualOpponentMoves(pair, side, baseContext.state),
                                ownStages = LocalOwnStageValue(stageBoard, profiles.getValue(side).difficulty.tier),
                            )
                            val context = BattleDecisionContext(
                                baseContext.requestId,
                                baseContext.state,
                                baseContext.candidates,
                                baseContext.deadlineEpochMillis,
                                baseContext.memory,
                                baseContext.publicActionCatalog.withOpponentMoveInferences(inferences),
                            ).let { built ->
                                EmbeddedTeamInput.nativeInputs(input)?.let { (preview, own) ->
                                    // As the game hands them over (Cobblemon173BrainTrainerBattleActor): the
                                    // opponent's real EVs and IVs for the advanced and boss tiers only.
                                    val spreads = if (profiles.getValue(side).difficulty.tier in setOf(
                                            BattleTrainerTier.ADVANCED, BattleTrainerTier.BOSS)) {
                                        opponentSpreads(pair, if (side == "p1") "p2" else "p1", preview)
                                    } else emptyMap()
                                    built.copy(opponentTeamPreview = preview, exactOwnTeam = own, localOpponentStatSpreads = spreads)
                                } ?: built
                            }
                            nativeEvaluations.remove(side)
                            val effects = context.candidates.asSequence().flatMap { candidate ->
                                if (candidate.kind == BattleActionKind.COMPOSITE) {
                                    candidate.componentActions.asSequence()
                                } else {
                                    sequenceOf(candidate)
                                }
                            }.filter { it.kind == BattleActionKind.USE_MOVE }
                                .associate { it.actionId to it.moveDetails?.effects }
                            effectAnnotatedCandidates += effects.values.count { it != null }
                            val script = scripted[frame["turn"].asInt]?.get(side)
                                ?.takeUnless { input.getAsJsonObject("request").has("forceSwitch") }
                            if (script != null) {
                                val chosen = context.candidates.singleOrNull { it.actionId == script }
                                    ?: error("Scripted $side '$script' not offered on turn ${frame["turn"]}: ${context.candidates.map { it.actionId }}")
                                memory.accept(context.state, chosen, null)
                                choices.addProperty(side, script)
                                return@forEach
                            }
                            stages.remove(side)
                            val decision = brains.getValue(side).decide(sessions.getValue(side), context)
                                .toCompletableFuture().get(25, TimeUnit.SECONDS)
                            val decidedAt = System.nanoTime()
                            check(decision.requestId == context.requestId && context.candidates.any { it.actionId == decision.actionId })
                            val played = recorded?.let { bySide ->
                                val (turn, actionId) = bySide[side]?.removeFirstOrNull()
                                    ?: error("The followed game has no more $side decisions on turn ${frame["turn"]}")
                                check(turn == frame["turn"].asInt) { "The game left the followed one: $side on turn ${frame["turn"]}, recorded $turn" }
                                context.candidates.singleOrNull { it.actionId == actionId }
                                    ?: error("Followed $side '$actionId' not offered on turn $turn: ${context.candidates.map { it.actionId }}")
                            }
                            val selected = played ?: context.candidates.single { it.actionId == decision.actionId }
                            memory.accept(context.state, selected, decision.advice.takeIf { selected.actionId == decision.actionId })
                            if (input.getAsJsonObject("request").has("forceSwitch")) forced++
                            counts[side] = counts.getValue(side) + 1
                            choices.addProperty(side, selected.actionId)
                            trace.append(JsonObject().apply {
                                addProperty("side", side); addProperty("turn", frame["turn"].asInt)
                                add("input", input); addProperty("actionId", selected.actionId)
                                if (played != null) addProperty("ownActionId", decision.actionId)
                                addProperty("decisionMillis", (decidedAt - decisionStarted) / 1_000_000)
                                // Each Pokemon's worth the decision priced material and knockouts with (LocalPokemonWorth).
                                add("worth", JsonObject().apply { jbro.cobblemon.mcc.betterai.evaluation.LocalPokemonWorth.last.forEach { (id, value) ->
                                    addProperty(context.state.pokemon.firstOrNull { it.battlePokemonId == id }?.let { "${it.side}:${it.speciesId}" } ?: id.toString(), value) } })
                                add("candidateEffects", com.google.gson.Gson().toJsonTree(effects))
                                add("memory", com.google.gson.Gson().toJsonTree(context.memory))
                                add("opponentMoveInferences", com.google.gson.Gson().toJsonTree(inferences))
                                add("decisionTags", com.google.gson.Gson().toJsonTree(decision.tags))
                                stages[side]?.let { add("stages", com.google.gson.Gson().toJsonTree(it)) }
                                // What each candidate is, so a reader of the stages need not decode action ids.
                                add("candidateMoves", JsonObject().apply { context.candidates.forEach { c ->
                                    addProperty(c.actionId, c.moveId ?: c.switchPokemonId?.let { id ->
                                        "switch:" + context.state.pokemon.firstOrNull { it.battlePokemonId == id }?.speciesId } ?: c.kind.name)
                                } })
                                // The Dynamax hold-back each Dynamax candidate pays and every ally's Dynamax weight behind it.
                                if (context.candidates.any { it.mechanic?.mechanicId == "dynamax" }) add("dynamax", JsonObject().apply {
                                    val profile = profiles.getValue(side)
                                    add("cost", JsonObject().apply { context.candidates.filter { it.mechanic?.mechanicId == "dynamax" }.forEach { c ->
                                        addProperty(c.actionId, jbro.cobblemon.mcc.betterai.evaluation.LocalMechanicOptionValue.cost(c, context, profile)) } })
                                    fun named(values: Map<java.util.UUID, Double>) = JsonObject().apply { values.forEach { (id, value) ->
                                        addProperty(context.state.pokemon.first { it.battlePokemonId == id }.speciesId, value) } }
                                    add("weights", named(jbro.cobblemon.mcc.betterai.evaluation.LocalMechanicOptionValue.dynamaxWeights(context)))
                                    add("ace", named(jbro.cobblemon.mcc.betterai.evaluation.LocalMechanicOptionValue.dynamaxAce(context)))
                                    add("share", named(jbro.cobblemon.mcc.betterai.evaluation.LocalMechanicOptionValue.dynamaxFits(context)))
                                    // The gimmick rule's two parts: the doubled HP guarding this turn, the reserve for later.
                                    add("guard", com.google.gson.Gson().toJsonTree(jbro.cobblemon.mcc.betterai.matchup.LocalDynamaxGuard.read(
                                        context.candidates, context, tunings.getValue(side)).adjustments))
                                    add("reserve", com.google.gson.Gson().toJsonTree(jbro.cobblemon.mcc.betterai.matchup.LocalGimmickReserve.adjustments(
                                        context.candidates, context, jbro.cobblemon.mcc.betterai.matchup.LocalAceScore.calculate(context))))
                                })
                                // What this side assumes of the foe in front (stats) and each move's damage both ways,
                                // read on the same assumed board the brain uses. Only on request (BETTERAI_MATCHUP_VIEW):
                                // it scores every pair again, which a long measurement does not need.
                                if (System.getenv("BETTERAI_MATCHUP_VIEW") != null) add("matchupView", JsonObject().apply {
                                    val tier = profiles.getValue(side).difficulty.tier
                                    val assumed = jbro.cobblemon.mcc.betterai.simulation.LocalOpponentStatAssumption.applyToPublicState(context, tier)
                                    val ally = assumed.state.pokemon.firstOrNull { it.side == BattleSide.ALLY && it.activeSlot != null && !it.fainted }
                                    val foe = assumed.state.pokemon.firstOrNull { it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted }
                                    if (ally != null && foe != null) {
                                        addProperty("foeStats", foe.combatStats.toString())
                                        addProperty("foeItem", foe.knownHeldItemId); addProperty("foeAbility", foe.knownAbilityId)
                                        val scores = runCatching { jbro.cobblemon.mcc.betterai.matchup.LocalMatchupScoreCalculator.calculate(assumed) }.getOrNull()
                                        fun moves(from: java.util.UUID, to: java.util.UUID) = com.google.gson.JsonArray().apply {
                                            scores?.moves(from, to)?.forEach { m -> add("${m.moveId} dmg=${"%.2f".format(m.minimumDamageFraction)}-${"%.2f".format(m.maximumDamageFraction)} ko1=${"%.2f".format(m.knockoutChanceWithin(1))} ko2=${"%.2f".format(m.knockoutChanceWithin(2))} acc=${"%.2f".format(m.accuracy)}") }
                                        }
                                        add("ours", moves(ally.battlePokemonId, foe.battlePokemonId))
                                        add("theirs", moves(foe.battlePokemonId, ally.battlePokemonId))
                                        scores?.pokemon(ally.battlePokemonId, foe.battlePokemonId)?.let { duel ->
                                            addProperty("win", duel.winProbability); addProperty("duelScore", duel.score)
                                            addProperty("firstMove", duel.subjectMovesFirstProbability)
                                        }
                                        // Each Pokemon behind: walking in on the foe's hit (switch-in) and after a knockout (free).
                                        add("switchIns", JsonObject().apply { assumed.state.pokemon.filter {
                                            it.side == BattleSide.ALLY && it.activeSlot == null && !it.fainted && it.hpFraction > 0.0 }.forEach { b ->
                                            scores?.switchIn(b.battlePokemonId, foe.battlePokemonId, ally.battlePokemonId)?.let { s ->
                                                addProperty(b.speciesId, "score=${"%.2f".format(s.score)} survival=${"%.2f".format(s.predictedSurvival)}") }
                                            scores?.pokemon(b.battlePokemonId, foe.battlePokemonId)?.let { addProperty("${b.speciesId}:free", it.score) }
                                            addProperty("${b.speciesId}:theirs", moves(foe.battlePokemonId, b.battlePokemonId).toString())
                                        } })
                                        add("preserves", JsonObject().apply { scores?.preserves?.forEach { (id, v) ->
                                            addProperty(assumed.state.pokemon.firstOrNull { it.battlePokemonId == id }?.speciesId ?: id.toString(), v.score) } })
                                    }
                                })
                                // The board as this side sees it: both actives' HP, status, stages.
                                add("board", com.google.gson.JsonArray().apply { context.state.pokemon.filter { it.activeSlot != null }.forEach { p ->
                                    add("${p.side}:${p.speciesId} hp=${"%.2f".format(p.hpFraction)} status=${p.statusId} stages=${p.statStages} moves=${p.knownMoveIds}")
                                } })
                                // Whether a pure self stat raise was on offer and taken (for the weight experiments).
                                addProperty("setupOffered", context.candidates.any { jbro.cobblemon.mcc.betterai.matchup.LocalSetupGate.raisesOwnStats(it) })
                                addProperty("setupChosen", jbro.cobblemon.mcc.betterai.matchup.LocalSetupGate.raisesOwnStats(selected))
                                // Why each offered stat raise passed or failed the gate (empty array: passed).
                                val setups = context.candidates.filter { jbro.cobblemon.mcc.betterai.matchup.LocalSetupGate.raisesOwnStats(it) }
                                if (setups.isNotEmpty()) {
                                    val scores = jbro.cobblemon.mcc.betterai.matchup.LocalMatchupScoreCalculator.calculate(context)
                                    add("setupGate", JsonObject().apply { setups.forEach { setup ->
                                        val verdict = jbro.cobblemon.mcc.betterai.matchup.LocalSetupGate.evaluate(setup, context, scores,
                                            tier = profiles.getValue(side).difficulty.tier)
                                        val user = context.state.pokemon.firstOrNull {
                                            it.side == jbro.cobblemon.mcc.internal.ai.BattleSide.ALLY && it.activeSlot == setup.actorSlot && !it.fainted
                                        }
                                        val sweep = user?.let { scores.sweeps[it.battlePokemonId] }
                                        add(setup.actionId, JsonObject().apply {
                                            add("failures", com.google.gson.JsonArray().apply { verdict?.failures?.forEach(::add) })
                                            addProperty("move", setup.moveId); addProperty("species", user?.speciesId)
                                            sweep?.let {
                                                addProperty("natural", it.naturalSweep); addProperty("boosted", it.boostedSweep)
                                                it.windowSweep?.let { w -> addProperty("window", w) }
                                                addProperty("setupMoveId", it.setupMoveId)
                                                // Every stopping tool the gate reads per opponent on the field, from the moves' data.
                                                if (user != null) add("stopTools", JsonObject().apply {
                                                    context.state.pokemon.filter { foe -> foe.side == jbro.cobblemon.mcc.internal.ai.BattleSide.OPPONENT && foe.activeSlot != null && !foe.fainted }.forEach { foe ->
                                                        val cache = jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache()
                                                        val declared = jbro.cobblemon.mcc.betterai.matchup.LocalStopScoreCalculator.score(context, foe, it, user, scores, cache)
                                                        val slots = context.publicActionCatalog.inferredMovesForPokemon(foe.battlePokemonId)?.slots.orEmpty()
                                                            .joinToString(",") { slot -> "${slot.moveId}:${slot.knowledge}" }
                                                        addProperty(foe.speciesId, "declared=${declared?.tools?.map { t -> "${t.kind}:${t.moveId}:${"%.2f".format(t.value)}" }} " +
                                                            "oneTime=${declared?.oneTimeSurvival} " +
                                                            "known=${foe.knownMoveIds} inferred=[$slots]")
                                                    }
                                                })
                                                jbro.cobblemon.mcc.betterai.matchup.LocalSetupGate.score(setup, context, scores, tunings.getValue(side).knockoutMaterialScore)?.let { r ->
                                                    addProperty("winAfterSetup", r.win); addProperty("sweepBonus", r.bonus); addProperty("setupValue", r.value)
                                                }
                                                // Why a boosted reading came out empty: per foe, surviving one setup turn and whether the pair could be set up.
                                                if (it.setupMoveId == null && user != null) addProperty("nullDiag", context.state.pokemon
                                                    .filter { foe -> foe.side == jbro.cobblemon.mcc.internal.ai.BattleSide.OPPONENT && !foe.fainted && foe.hpFraction > 0.0 }
                                                    .joinToString(" ") { foe ->
                                                        val pair = scores.pokemon(user.battlePokemonId, foe.battlePokemonId)
                                                        val faced = jbro.cobblemon.mcc.betterai.matchup.LocalMatchupPosition.face(context, user, foe,
                                                            jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache()) != null
                                                        val om = pair?.opponentMove
                                                        "${foe.speciesId}(hp=${"%.2f".format(foe.hpFraction)}):myHp=${"%.2f".format(user.hpFraction)}:move=${om?.moveId}:dmg=${om?.let { m -> "%.2f-%.2f".format(m.minimumDamageFraction, m.maximumDamageFraction) }}:acc=${om?.accuracy}:survive1=${om?.survivalByUses?.getOrNull(1)?.let { s -> "%.2f".format(s) }}:faced=$faced"
                                                    })
                                            }
                                        })
                                    } })
                                }
                                nativeEvaluations[side]?.let { add("native", nativeTrace(it)) }
                            }.toString()).appendLine()
                            trace.flush()
                        }
                        native.send(JsonObject().apply { add("choices", choices) })
                    }
                    error("Native request limit exceeded without terminal result")
                }
            }
        } finally {
            nativeWorkers.forEach { runCatching { it.close() } }
            brains.forEach { (side, brain) ->
                val winner = final?.get("winner")?.takeUnless { it.isJsonNull }?.asString
                val outcome = when {
                    final?.get("status")?.asString != "COMPLETE" -> BattleBrainCloseOutcome.CANCELLED
                    winner == null -> BattleBrainCloseOutcome.CANCELLED
                    winner == side -> BattleBrainCloseOutcome.VICTORY
                    else -> BattleBrainCloseOutcome.DEFEAT
                }
                brain.closeSession(sessions.getValue(side), BattleBrainCloseResult(outcome, counts.getValue(side)))
            }
        }
    }

    private fun nativeTrace(evaluation: NativeInitialProductDecisionEvaluation) = JsonObject().apply {
        addProperty("status", evaluation.status.name)
        addProperty("depth", evaluation.depthCompleted)
        addProperty("nodes", evaluation.nodesVisited)
        addProperty("planIssues", evaluation.planIssues.joinToString(",") { it.code.name + (it.detailCode?.let { code -> "/$code" } ?: "") })
        evaluation.reconciliationStatus?.let { addProperty("reconciliation", it.name) }
        evaluation.searchStatus?.let { addProperty("search", it.name) }
        evaluation.failedRunStatus?.let { addProperty("failedRun", it.name) }
        evaluation.failedRunDetail?.let { addProperty("detail", it) }
        evaluation.failedWorldId?.let { addProperty("failedWorld", it) }
        if (evaluation.rebuilt) addProperty("rebuilt", "true")
    }

    private fun registerMoveDetails(
        catalog: BattlePublicActionCatalogView,
        destination: MutableMap<String, BattleMoveCandidateView>,
    ) {
        catalog.entries.forEach { entry -> entry.moves.forEach { destination[canonical(it.moveId)] = it.details } }
        catalog.candidatePools.forEach { pool ->
            pool.moveDetails.forEach { (moveId, details) -> destination[canonical(moveId)] = details }
        }
    }

    private fun actualOpponentMoves(
        pair: JsonObject,
        requestingSide: String,
        state: BattleStateView,
    ): Map<UUID, Set<String>> {
        val opponentSide = if (requestingSide == "p1") "p2" else "p1"
        val movesBySpecies = pair.getAsJsonObject(opponentSide).getAsJsonArray("sets")
            .associate { setValue ->
                val set = setValue.asJsonObject
                canonical(set["species"].asString) to set.getAsJsonArray("moves").map { canonical(it.asString) }.toSet()
            }
        // The game matches the real set by the Pokemon itself; here only by species, so a battle forme (Mega Gengar,
        // Mimikyu-Busted) is matched to the set of the species it came from. Missed, a Boss read no attack at all for it.
        fun actual(species: String): Set<String>? = movesBySpecies[species]
            ?: movesBySpecies.entries.filter { (base, _) -> species.length > base.length && species.startsWith(base) }
                .maxByOrNull { it.key.length }?.value
        return state.pokemon.asSequence().filter { it.side == BattleSide.OPPONENT }.mapNotNull { pokemon ->
            actual(canonical(pokemon.speciesId))?.let { pokemon.battlePokemonId to it }
        }.toMap()
    }

    private fun canonical(value: String): String =
        value.substringAfter(':').lowercase(java.util.Locale.ROOT).filter(Char::isLetterOrDigit)

    internal class NativeSession(engine: Path, pair: JsonObject, directory: Path, maxTurns: Int) : AutoCloseable {
        private val readerExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "native-team-output").apply { isDaemon = true } }
        private val stderr: Path
        private val process: Process
        private val reader: java.io.BufferedReader
        private val writer: java.io.BufferedWriter
        init {
            require(maxTurns in 1..10000)
            require(pair["battleFormat"]?.asString.let { it == null || it in setOf("SINGLE", "DOUBLE") }) {
                "Native team bridge supports SINGLE and DOUBLE only"
            }
            Files.createDirectories(directory)
            val script = directory.resolve("bridge.cjs").toAbsolutePath()
            val input = directory.resolve("referee-input.json").toAbsolutePath()
            stderr = directory.resolve("stderr.txt").toAbsolutePath()
            Files.write(script, requireNotNull(javaClass.getResourceAsStream("/oracle/team-battle-bridge.cjs")).use { it.readBytes() }, CREATE_NEW)
            Files.writeString(input, pair.deepCopy().apply { addProperty("maxTurns", maxTurns) }.toString(), CREATE_NEW)
            val command = mutableListOf("node", "--unhandled-rejections=strict", script.toString(), engine.toAbsolutePath().toString(), input.toString())
            megaShowdownData?.let { command += it.toString() }
            val builder = ProcessBuilder(command)
                .redirectError(stderr.toFile())
            builder.environment().remove("NODE_OPTIONS"); builder.environment().remove("NODE_PATH")
            process = builder.start()
            reader = process.inputStream.bufferedReader(Charsets.UTF_8)
            writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
        }
        fun read(): JsonObject {
            val line = readerExecutor.submit<String?> { reader.readLine() }.get(10, TimeUnit.SECONDS)
            check(line != null) { "Native bridge ended unexpectedly: ${Files.readString(stderr).take(4000)}" }
            return JsonParser.parseString(line).asJsonObject
        }
        fun send(value: JsonObject) { writer.append(value.toString()).appendLine(); writer.flush() }
        fun awaitExit() {
            check(process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) {
                "Native bridge failed or did not exit: ${Files.readString(stderr).take(4000)}"
            }
        }
        override fun close() {
            if (process.isAlive) process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(2, TimeUnit.SECONDS)
            readerExecutor.shutdownNow()
            reader.close(); writer.close()
        }
    }

    /**
     * What Mega Showdown registers on top of Showdown when the game starts, unpacked from its jar beside the dev
     * server's Showdown (`aiengine.showdown`/../mods): its held items and abilities (`showdown/`) and the Cobblemon
     * forms it adds (`species_additions/`). Without them a Z-A Mega Stone (Lucarionite Z) never offers Mega Evolution.
     * Null when no jar is there; the bridge then plays on Showdown alone.
     */
    private val megaShowdownData: Path? by lazy {
        val mods = System.getProperty("aiengine.showdown")?.let { Path.of(it).parent?.resolve("mods") }
            ?.takeIf { Files.isDirectory(it) } ?: return@lazy null
        val jar = Files.list(mods).use { files -> files.filter { it.fileName.toString().startsWith("mega_showdown-") }.findFirst().orElse(null) }
            ?: return@lazy null
        val out = Files.createTempDirectory("mega-showdown-data")
        Runtime.getRuntime().addShutdownHook(Thread { out.toFile().deleteRecursively() })
        val prefixes = mapOf("data/mega_showdown/mega_showdown/showdown/" to "showdown/", "data/cobblemon/species_additions/" to "species_additions/")
        java.util.zip.ZipFile(jar.toFile()).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                val (prefix, folder) = prefixes.entries.firstOrNull { entry.name.startsWith(it.key) } ?: continue
                val target = out.resolve(folder + entry.name.removePrefix(prefix))
                Files.createDirectories(target.parent)
                zip.getInputStream(entry).use { Files.copy(it, target) }
            }
        }
        out
    }

    /** The opposing side's real EVs and IVs by preview slot, matched by species; a repeated species is left out. */
    private fun opponentSpreads(
        pair: JsonObject,
        opponentSide: String,
        preview: jbro.cobblemon.mcc.internal.ai.BattleOpponentTeamPreviewView,
    ): Map<Int, jbro.cobblemon.mcc.internal.ai.BattleLocalOpponentStatSpreadView> {
        val stats = listOf("hp", "atk", "def", "spa", "spd", "spe")
        fun id(value: String) = value.substringAfter(':').lowercase().filter(Char::isLetterOrDigit)
        val sets = pair.getAsJsonObject(opponentSide)?.getAsJsonArray("sets")?.map { it.asJsonObject }.orEmpty()
        return preview.pokemon.mapNotNull { slot ->
            val matching = sets.filter { id(it["species"].asString) == id(slot.speciesId) }
            val set = matching.singleOrNull() ?: return@mapNotNull null
            fun spread(name: String, default: Int) = stats.associateWith { stat ->
                set.getAsJsonObject(name)?.get(stat)?.asInt ?: default
            }
            slot.previewSlotId to jbro.cobblemon.mcc.internal.ai.BattleLocalOpponentStatSpreadView(spread("ivs", 31), spread("evs", 0))
        }.toMap()
    }
}
