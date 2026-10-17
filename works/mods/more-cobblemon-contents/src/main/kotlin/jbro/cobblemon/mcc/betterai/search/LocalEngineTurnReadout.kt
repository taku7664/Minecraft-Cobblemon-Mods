package jbro.cobblemon.mcc.betterai.search

import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.evaluation.LocalImmediateTurnScorer
import jbro.cobblemon.mcc.betterai.evaluation.LocalLookaheadStateEvaluator
import jbro.cobblemon.mcc.betterai.matchup.OpponentIntent
import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.mcc.betterai.mechanics.LocalStallingProtectionRules
import jbro.cobblemon.mcc.betterai.simulation.EngineRuntimeDex
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleStateAdapter
import jbro.cobblemon.mcc.betterai.simulation.NativeBranchWorker
import jbro.cobblemon.mcc.betterai.simulation.NativeInitialProductWorld
import jbro.cobblemon.mcc.betterai.simulation.NativeInitialProductWorldPlanner
import jbro.cobblemon.mcc.betterai.simulation.NativeOpeningStateRules
import jbro.cobblemon.mcc.betterai.simulation.NativeRootActionMatcher
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownChoiceEncoder
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownRequestActionFactory
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import jbro.cobblemon.mcc.internal.ai.PublicIds
import java.util.UUID

/**
 * Status moves and the attacks they displace, played against the opponent's replies in the engine and read off its
 * board. Nothing is read from a move's name.
 *
 * Each action is played against every public reply the opponent has, in its three likeliest worlds, and the board
 * after it is valued by the search's leaf ([LocalLookaheadStateEvaluator]). An unrevealed status move is not a public
 * reply; inferred attacks, switches and moves whose category is unknown remain. The replies are weighed by how likely
 * the opponent is to make them ([OpponentIntent], the same prediction the switching rules use), with a share of the
 * worst one mixed in ([LocalDecisionTuning.statusReplyExpectation] is the likely part). Taking only the worst read
 * every reply as the one that hurts most, often the opponent switching out, which drops its own stages and so made
 * wiping them worth nothing. Each first turn is drawn on three random streams. A move whose data declares a lasting
 * effect is played a second turn instead (on one stream): this side's best attack against the opponent's three
 * likeliest public replies, weighed the same way.
 *
 * The same boards, compared side by side against the reference attack under each reply, give:
 * - [Reading.selfHarm]: in every reply the move dealt nothing, took something from this side (its stages, a condition
 *   the leaf counts in its favour, a clean status) and gave this side and took from the opponent nothing.
 * - [Reading.changed]: in some reply the move left the board different beyond HP (stages, volatiles, statuses, either
 *   side's conditions, weather, terrain, rooms). Reported only; it does not send a move to a second turn.
 */
internal object LocalEngineTurnReadout {
    /** Test-only measurement switch; false restores inferred unrevealed status replies. */
    internal var publicRepliesOnly: Boolean = true

    data class Reading(
        val selfHarm: Set<String>,
        val changed: Set<String>,
        /** Board value per move ID and per reference action ID, worlds weighted. */
        val values: Map<String, Double>,
        val plies: Int,
    )

    data class DoublesProtectReading(
        val values: Map<String, Double>,
        val candidates: Int,
        val maxReplies: Int,
        val worlds: Int,
        val branches: Int,
        val elapsedMillis: Long,
        val status: String,
    )

    private const val WORLDS = 3
    private const val EPSILON = 1e-6
    /** The least chance an unnamed public reply gets (an inferred attack, an unseen switch-in). */
    private const val UNNAMED_REPLY = 0.02
    private const val SECOND_TURN_REPLIES = 3
    private const val DOUBLES_REPLIES = 6
    private const val DOUBLES_REPLY_COVERAGE = 0.85
    /** Random streams each first turn is drawn on. */
    private const val SEEDS = 3
    private val planner = NativeInitialProductWorldPlanner(worldLimit = { _, _ -> WORLDS })
    private val logger = org.slf4j.LoggerFactory.getLogger("BetterAI")

    fun read(
        context: BattleDecisionContext,
        tier: BattleTrainerTier,
        moves: List<BattleActionCandidate>,
        references: List<BattleActionCandidate>,
        intents: List<OpponentIntent>,
        tuning: LocalDecisionTuning,
        /** Play a second turn (a move whose data declares a lasting effect). */
        lastingDeclared: Boolean,
        maxPlies: Int = 2,
    ): Reading? {
        val live = context.showdownState
        val plan = when {
            live != null -> planner.planLive(context, tier)
            // The opening plan only where the native evaluator takes it (NativeInitialProductDecisionEvaluator.isOpeningCandidate).
            context.state.turn in 0..1 && NativeOpeningStateRules.acceptsObservations(context.state) -> planner.plan(context, tier)
            else -> planner.planMidBattle(context, tier)
        }
        if (plan.issues.isNotEmpty() || plan.worlds.isEmpty()) {
            logger.error("[BetterAI] Turn readout could not plan the opponent's worlds: {}", plan.issues.map { it.code.name + ":" + it.detailCode })
            return null
        }
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
            (context.deadlineEpochMillis - System.currentTimeMillis()).coerceAtLeast(1L))
        val ids = moves.mapNotNull { it.moveId?.let(PublicIds::canonical) }.distinct()
        val likely = intents.flatMap { intent -> intent.byAction().entries }.associate { (key, p) -> canonicalKey(key) to p }
        val expectation = tuning.statusReplyExpectation
        val knownOpponentMoves = knownOpponentMoveIdsBySlot(context)
        return LocalEngineStopProbe.lease.withWorker(deadline) { worker ->
            val worlds = plan.worlds.mapNotNull { world ->
                val root = live?.let { worker.createLiveBattle(it, world.definition) } ?: worker.createBattle(world.definition)
                val ally = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, root, allowedMechanics = emptySet())
                val refs = references.mapNotNull { ref -> ally.firstOrNull { same(it, ref) }?.let { ref.actionId to it } }
                if (refs.isEmpty()) null else Played(world, root, ally, refs)
            }
            if (worlds.isEmpty()) return@withWorker null
            // First turn: every action against every reply, kept for the comparison and, past one turn, the second.
            val harmedEverywhere = ids.associateWith { true }.toMutableMap()
            val seen = hashSetOf<String>()
            val changed = hashSetOf<String>()
            val firstTurns = worlds.mapNotNull { played ->
                val template = played.world.publicContext.state
                val replies = publicReplies(
                    NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, played.root, allowedMechanics = emptySet()),
                    knownOpponentMoves,
                )
                if (replies.isEmpty()) return@mapNotNull null
                val chances = chances(replies, likely)
                val boards = linkedMapOf<String, List<Pair<NativeBattleFrame, BattleStateView>>>()
                val actions = linkedMapOf<String, BattleActionCandidate>()
                val referenceBoards = replies.map { reply -> play(worker, played.root, played.refs.first().second, reply, template) }
                for ((id, action) in played.refs) {
                    actions[id] = action
                    boards[id] = if (id == played.refs.first().first) referenceBoards else replies.map { play(worker, played.root, action, it, template) }
                }
                for (id in ids) {
                    val move = played.ally.firstOrNull { it.kind == BattleActionKind.USE_MOVE && it.moveId?.let(PublicIds::canonical) == id } ?: continue
                    val moveBoards = replies.map { play(worker, played.root, move, it, template) }
                    boards[id] = moveBoards
                    actions[id] = move
                    seen += id
                    moveBoards.zip(referenceBoards).forEach { (mine, theirs) ->
                        if (lasting(mine.second) != lasting(theirs.second)) changed += id
                        if (!selfHarm(mine.second, theirs.second, context)) harmedEverywhere[id] = false
                    }
                }
                FirstTurn(played, replies, chances, boards, actions)
            }
            if (firstTurns.isEmpty()) return@withWorker null
            // Two turns only for a move whose data declares a lasting effect: read off the board instead, nearly every
            // status move qualified and a decision took up to 1.5 s on a desktop. A scripted move (Haze) stays one turn.
            val plies = if (lastingDeclared && maxPlies >= 2) 2 else 1
            val seeds = if (plies == 2) 1 else SEEDS
            val values = linkedMapOf<String, Double>()
            val weights = linkedMapOf<String, Double>()
            for (turn in firstTurns) {
                val cache = LocalProjectedActionCalculationCache()
                val source = turn.played.world.publicContext
                actionLoop@ for ((key, boards) in turn.boards) {
                    // Each first turn drawn on SEEDS random streams (a miss, a critical hit, a secondary effect); the
                    // comparison above reads the first draw only.
                    val action = turn.actions.getValue(key)
                    val perReply = mutableListOf<Double>()
                    for ((r, reply) in turn.replies.withIndex()) {
                        var total = 0.0
                        for (seed in 0 until seeds) {
                            val (frame, state) = if (seed == 0) boards[r] else play(worker,
                                worker.reseed(turn.played.root.snapshotJson, seed), action, reply, turn.played.world.publicContext.state)
                            total += if (plies == 1 || frame.ended || frame.requestState != "move") leaf(state, source, cache, tuning)
                            else secondTurn(worker, frame, turn.played.world, source, cache, tuning, likely, expectation) ?:
                                continue@actionLoop
                        }
                        perReply += total / seeds
                    }
                    val value = blend(perReply, turn.chances, expectation)
                    values.merge(key, turn.played.world.probability * value, Double::plus)
                    weights.merge(key, turn.played.world.probability, Double::plus)
                }
            }
            Reading(
                harmedEverywhere.filter { (id, harmed) -> harmed && id in seen }.keys,
                changed,
                values.mapValues { (key, value) -> value / weights.getValue(key) },
                plies,
            )
        }
    }

    /**
     * What a turn spent on each of [protects] brings, played in the engine against the opponent's replies, worlds and
     * replies weighed as [read] weighs them: the turn's score ([LocalImmediateTurnScorer], the finished-battle value
     * left out), keyed by action ID. A hit the Protect blocks leaves nothing on the board, so what is left is what the
     * turn changed: a contact move punished (Spiky Shield's damage, Baneful Bunker's poison, King's Shield's drop, all
     * as the engine runs them, no move named), the opponent's own raise or switch, the turn's end. Missing for a move
     * the engine could not play. This Protect-only readout excludes unrevealed status replies so a hypothesised status
     * move cannot suppress or determine protection; inferred attacks and switches remain.
     */
    fun protectTurns(
        context: BattleDecisionContext,
        tier: BattleTrainerTier,
        protects: List<BattleActionCandidate>,
        intents: List<OpponentIntent>,
        tuning: LocalDecisionTuning,
        /** Test seam for proving the production [SEEDS] average; production callers keep the defaults. */
        seedCount: Int = SEEDS,
        seedStart: Int = 0,
    ): Map<String, Double> {
        if (protects.isEmpty()) return emptyMap()
        require(seedCount >= 1 && seedStart >= 0)
        val live = context.showdownState
        val plan = when {
            live != null -> planner.planLive(context, tier)
            context.state.turn in 0..1 && NativeOpeningStateRules.acceptsObservations(context.state) -> planner.plan(context, tier)
            else -> planner.planMidBattle(context, tier)
        }
        if (plan.issues.isNotEmpty() || plan.worlds.isEmpty()) {
            logger.error("[BetterAI] Protect readout could not plan the opponent's worlds: {}", plan.issues.map { it.code.name + ":" + it.detailCode })
            return emptyMap()
        }
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
            (context.deadlineEpochMillis - System.currentTimeMillis()).coerceAtLeast(1L))
        val likely = intents.flatMap { intent -> intent.byAction().entries }.associate { (key, p) -> canonicalKey(key) to p }
        val expectation = tuning.statusReplyExpectation
        val knownOpponentMoves = knownOpponentMoveIdsBySlot(context)
        return LocalEngineStopProbe.lease.withWorker(deadline) { worker ->
            val values = linkedMapOf<String, Double>()
            val weights = linkedMapOf<String, Double>()
            for (world in plan.worlds) {
                val root = live?.let { worker.createLiveBattle(it, world.definition) } ?: worker.createBattle(world.definition)
                val ally = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, root, allowedMechanics = emptySet())
                val replies = publicReplies(
                    NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, root, allowedMechanics = emptySet()),
                    knownOpponentMoves,
                )
                if (replies.isEmpty()) continue
                val chances = chances(replies, likely)
                val source = world.publicContext
                val cache = LocalProjectedActionCalculationCache()
                for (protect in protects) {
                    val move = ally.firstOrNull { same(it, protect) } ?: continue
                    val turns = replies.map { reply ->
                        var total = 0.0
                        // As read's first turn: the same action/reply is drawn on independent, reproducible streams.
                        // A status move that passes King's Shield must not be priced from one hit-or-miss coin flip.
                        for (seed in seedStart until seedStart + seedCount) {
                            val seededRoot = if (seed == 0) root else worker.reseed(root.snapshotJson, seed)
                            val after = play(worker, seededRoot, move, reply, source.state).second
                            total += LocalImmediateTurnScorer.score(source.state, after, source, cache, tuning).total -
                                jbro.cobblemon.mcc.betterai.evaluation.LocalTerminalOutcomeValue.evaluate(after) +
                                jbro.cobblemon.mcc.betterai.evaluation.LocalTerminalOutcomeValue.evaluate(source.state)
                        }
                        total / seedCount
                    }
                    values.merge(protect.actionId, world.probability * blend(turns, chances, expectation), Double::plus)
                    weights.merge(protect.actionId, world.probability, Double::plus)
                }
            }
            values.mapValues { (id, value) -> value / weights.getValue(id) }
        } ?: emptyMap()
    }

    /**
     * A doubles Protect joint action, less the same joint action with only the protecting slot passing. The caller
     * substitutes this value for LocalProtectCredit, so blocked attacks and contact retaliation are not counted twice.
     * Missing values deliberately mean "keep the credit".
     */
    fun doublesProtectTurns(
        context: BattleDecisionContext,
        tier: BattleTrainerTier,
        protects: List<BattleActionCandidate>,
        intents: List<OpponentIntent>,
        tuning: LocalDecisionTuning,
        seedCount: Int = SEEDS,
        seedStart: Int = 0,
    ): DoublesProtectReading {
        require(context.state.format == BattleFormat.DOUBLE)
        require(seedCount >= 1 && seedStart >= 0)
        val started = System.nanoTime()
        fun result(
            values: Map<String, Double> = emptyMap(),
            worlds: Int = 0,
            replies: Int = 0,
            branches: Int = 0,
            status: String,
        ) = DoublesProtectReading(
            values, protects.size, replies, worlds, branches,
            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), status,
        )
        if (protects.isEmpty()) return result(status = "NO_CANDIDATES")
        val live = context.showdownState
        val plan = when {
            live != null -> planner.planLive(context, tier)
            context.state.turn in 0..1 && NativeOpeningStateRules.acceptsObservations(context.state) -> planner.plan(context, tier)
            else -> planner.planMidBattle(context, tier)
        }
        if (plan.issues.isNotEmpty() || plan.worlds.isEmpty()) {
            logger.error(
                "[BetterAI] Doubles Protect readout could not plan the opponent's worlds: {}",
                plan.issues.map { it.code.name + ":" + it.detailCode },
            )
            return result(status = "PLAN_FAILED")
        }
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
            (context.deadlineEpochMillis - System.currentTimeMillis()).coerceAtLeast(1L),
        )
        val knownOpponentMoves = knownOpponentMoveIdsBySlot(context)
        return LocalEngineStopProbe.lease.withWorker(deadline) { worker ->
            val totals = linkedMapOf<String, Double>()
            val weights = linkedMapOf<String, Double>()
            val completedWorlds = hashMapOf<String, Int>()
            var maxReplies = 0
            var branches = 0
            var usedWorlds = 0
            for (world in plan.worlds) {
                val root = live?.let { worker.createLiveBattle(it, world.definition) } ?: worker.createBattle(world.definition)
                val ally = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, root, allowedMechanics = emptySet())
                val mapping = NativeRootActionMatcher.match(BattleFormat.DOUBLE, protects, ally).productToNative
                val picked = doublesReplies(
                    publicReplies(
                        NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, root, allowedMechanics = emptySet()),
                        knownOpponentMoves,
                    ),
                    intents,
                    context.state,
                )
                if (mapping.isEmpty() || picked.isEmpty()) continue
                usedWorlds++
                maxReplies = maxOf(maxReplies, picked.size)
                val source = world.publicContext
                val cache = LocalProjectedActionCalculationCache()
                for (protect in protects) {
                    val action = mapping[protect.actionId] ?: continue
                    val protecting = protect.componentActions.singleOrNull(::isProtectComponent) ?: continue
                    val waiting = withPass(action, requireNotNull(protecting.actorSlot)) ?: continue
                    val perReply = mutableListOf<Double>()
                    var complete = true
                    for ((reply, _) in picked) {
                        var total = 0.0
                        for (seed in seedStart until seedStart + seedCount) {
                            val playedRoot = if (seed == 0) root else worker.reseed(root.snapshotJson, seed)
                            val passedRoot = if (seed == 0) root else worker.reseed(root.snapshotJson, seed)
                            val delta = runCatching {
                                val played = play(worker, playedRoot, action, reply, source.state).second
                                val passed = play(worker, passedRoot, waiting, reply, source.state).second
                                turnScore(played, source, cache, tuning) - turnScore(passed, source, cache, tuning)
                            }.getOrElse {
                                complete = false
                                logger.debug("[BetterAI] Doubles Protect pass comparison failed for {}", protect.actionId, it)
                                0.0
                            }
                            if (!complete) break
                            total += delta
                            branches += 2
                        }
                        if (!complete) break
                        perReply += total / seedCount
                    }
                    if (!complete || perReply.size != picked.size) continue
                    val chances = picked.map { it.second }.normalize()
                    totals.merge(
                        protect.actionId,
                        world.probability * blend(perReply, chances, tuning.statusReplyExpectation),
                        Double::plus,
                    )
                    weights.merge(protect.actionId, world.probability, Double::plus)
                    completedWorlds.merge(protect.actionId, 1, Int::plus)
                }
            }
            val values = totals.mapNotNull { (id, total) ->
                if (completedWorlds[id] != usedWorlds || usedWorlds != plan.worlds.size) null
                else id to total / weights.getValue(id)
            }.toMap()
            result(
                values, usedWorlds, maxReplies, branches,
                if (values.isEmpty()) "NO_COMPLETE_VALUE" else "SUCCESS",
            )
        } ?: result(status = "WORKER_UNAVAILABLE")
    }

    internal fun publicReplies(
        replies: List<BattleActionCandidate>,
        knownMoveIdsBySlot: Map<Int, Set<String>>?,
    ): List<BattleActionCandidate> {
        if (!publicRepliesOnly || knownMoveIdsBySlot == null) return replies
        val known = knownMoveIdsBySlot.mapValues { (_, ids) -> ids.mapTo(hashSetOf(), PublicIds::canonical) }
        return replies.filter { reply ->
            parts(reply).all { part ->
                if (part.kind != BattleActionKind.USE_MOVE) return@all true
                val moveId = part.moveId ?: return@all true
                if (replyDamageCategory(part) != BattleMoveDamageCategory.STATUS) return@all true
                val actorKnown = part.actorSlot?.let(known::get) ?: return@all true
                PublicIds.canonical(moveId) in actorKnown
            }
        }
    }

    /** Compatibility helper for focused single-slot tests. */
    internal fun publicReplies(replies: List<BattleActionCandidate>, knownMoveIds: Set<String>?): List<BattleActionCandidate> =
        publicReplies(replies, knownMoveIds?.let { mapOf(0 to it) })

    private fun knownOpponentMoveIdsBySlot(context: BattleDecisionContext): Map<Int, Set<String>>? =
        context.state.pokemon.filter { it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted }
            .associate { requireNotNull(it.activeSlot) to it.knownMoveIds }
            .takeIf { it.isNotEmpty() }

    internal fun knownOpponentMoveIds(
        activeOpponentId: UUID?,
        pokemon: List<BattlePokemonStateView>,
    ): Set<String>? {
        if (activeOpponentId == null) return null
        return pokemon.singleOrNull {
            it.side == BattleSide.OPPONENT && it.battlePokemonId == activeOpponentId
        }?.knownMoveIds ?: emptySet()
    }

    internal fun knownOpponentMoveIds(
        activeOpponentIds: Map<Int, UUID>,
        pokemon: List<BattlePokemonStateView>,
    ): Map<Int, Set<String>>? = activeOpponentIds.mapValues { (_, id) ->
        knownOpponentMoveIds(id, pokemon).orEmpty()
    }.takeIf { it.isNotEmpty() }

    private fun activeOpponentIds(frame: NativeBattleFrame): Map<Int, UUID> = frame.p2Active.mapNotNull { active ->
        val slot = active.activeSlot?.takeIf { active.hp > 0 } ?: return@mapNotNull null
        runCatching { UUID.fromString(active.uuid) }.getOrNull()?.let { slot to it }
    }.toMap()

    private fun replyDamageCategory(reply: BattleActionCandidate): BattleMoveDamageCategory? {
        reply.moveDetails?.damageCategory?.let { return it }
        val moveId = reply.moveId?.let(PublicIds::canonical) ?: return null
        val category = runCatching { EngineRuntimeDex.current().second.move(moveId)?.category }.getOrNull() ?: return null
        return BattleMoveDamageCategory.entries.firstOrNull { it.name.equals(category, ignoreCase = true) }
    }

    private fun parts(candidate: BattleActionCandidate): List<BattleActionCandidate> =
        if (candidate.kind == BattleActionKind.COMPOSITE) candidate.componentActions else listOf(candidate)

    private fun doublesReplies(
        replies: List<BattleActionCandidate>,
        intents: List<OpponentIntent>,
        state: BattleStateView,
    ): List<Pair<BattleActionCandidate, Double>> {
        if (replies.isEmpty()) return emptyList()
        val bySlot = intents.associateBy(OpponentIntent::activeSlot)
        val weighted = replies.map { reply ->
            val probability = parts(reply).fold(1.0) { product, part ->
                val intent = part.actorSlot?.let(bySlot::get)
                product * (intent?.let { intentProbability(part, it, state) } ?: UNNAMED_REPLY)
            }
            reply to probability
        }.sortedByDescending { it.second }
        val normalized = weighted.map { it.first to it.second }.let { entries ->
            val chances = entries.map { it.second }.normalize()
            entries.indices.map { entries[it].first to chances[it] }
        }
        val selected = mutableListOf<Pair<BattleActionCandidate, Double>>()
        var coverage = 0.0
        for (entry in normalized) {
            if (selected.size >= DOUBLES_REPLIES || coverage >= DOUBLES_REPLY_COVERAGE) break
            selected += entry
            coverage += entry.second
        }
        return selected
    }

    private fun intentProbability(
        action: BattleActionCandidate,
        intent: OpponentIntent,
        state: BattleStateView,
    ): Double {
        val targetId = action.targets.singleOrNull()?.let { target ->
            state.pokemon.firstOrNull { it.side == target.side && it.activeSlot == target.slot && !it.fainted }
                ?.battlePokemonId
        }
        val matched = intent.options.filter { option ->
            when (action.kind) {
                BattleActionKind.SWITCH -> option.switchInId == action.switchPokemonId
                BattleActionKind.USE_MOVE -> option.moveId?.let(PublicIds::canonical) == action.moveId?.let(PublicIds::canonical) &&
                    (option.targetId == null || option.targetId == targetId)
                BattleActionKind.WAIT -> true
                else -> false
            }
        }.sumOf { it.probability }
        return matched.takeIf { it > 0.0 } ?: UNNAMED_REPLY
    }

    private fun List<Double>.normalize(): List<Double> {
        val total = sum()
        return if (total <= 0.0) map { 1.0 / size } else map { it / total }
    }

    private fun isProtectComponent(action: BattleActionCandidate): Boolean =
        action.kind == BattleActionKind.USE_MOVE && action.mechanic == null &&
            LocalStallingProtectionRules.isStallingProtection(action)

    private fun withPass(action: BattleActionCandidate, slot: Int): BattleActionCandidate? {
        if (action.kind != BattleActionKind.COMPOSITE || action.componentActions.none { it.actorSlot == slot }) return null
        val components = action.componentActions.map { component ->
            if (component.actorSlot != slot) component
            else BattleActionCandidate("native:protect-pass:$slot", BattleActionKind.WAIT, actorSlot = slot)
        }
        return BattleActionCandidate(
            actionId = "native:protect-pass-joint:${action.actionId}",
            kind = BattleActionKind.COMPOSITE,
            componentActionIds = components.map(BattleActionCandidate::actionId),
            componentActions = components,
        )
    }

    private data class Played(
        val world: NativeInitialProductWorld,
        val root: NativeBattleFrame,
        val ally: List<BattleActionCandidate>,
        val refs: List<Pair<String, BattleActionCandidate>>,
    )

    private data class FirstTurn(
        val played: Played,
        val replies: List<BattleActionCandidate>,
        val chances: List<Double>,
        val boards: Map<String, List<Pair<NativeBattleFrame, BattleStateView>>>,
        val actions: Map<String, BattleActionCandidate>,
    )

    /** This side's best attack on the second turn, against the opponent's three likeliest public replies. */
    private fun secondTurn(
        worker: NativeBranchWorker,
        frame: NativeBattleFrame,
        world: NativeInitialProductWorld,
        source: BattleDecisionContext,
        cache: LocalProjectedActionCalculationCache,
        tuning: LocalDecisionTuning,
        likely: Map<String, Double>,
        expectation: Double,
    ): Double? {
        val template = world.publicContext.state
        val ours = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, frame, allowedMechanics = emptySet())
        val attacks = ours.filter { it.kind == BattleActionKind.USE_MOVE && it.moveDetails?.damageCategory != BattleMoveDamageCategory.STATUS }
            .ifEmpty { ours.filter { it.kind == BattleActionKind.USE_MOVE } }.ifEmpty { ours }
        val all = publicReplies(
            NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, frame, allowedMechanics = emptySet()),
            knownOpponentMoveIds(activeOpponentIds(frame), source.state.pokemon),
        )
        if (all.isEmpty()) return null
        val allChances = chances(all, likely)
        val top = all.indices.sortedByDescending { allChances[it] }.take(SECOND_TURN_REPLIES)
        if (attacks.isEmpty() || top.isEmpty()) return leaf(NativeBattleStateAdapter.adapt(frame, template), source, cache, tuning)
        val replies = top.map { all[it] }
        val chances = top.map { allChances[it] }.let { picked -> picked.map { it / picked.sum() } }
        return attacks.maxOf { attack ->
            blend(replies.map { reply -> leaf(play(worker, frame, attack, reply, template).second, source, cache, tuning) }, chances, expectation)
        }
    }

    /** The likely share weighed by the replies' chances, the rest the worst of them. */
    private fun blend(values: List<Double>, chances: List<Double>, expectation: Double): Double =
        expectation * values.zip(chances).sumOf { (value, chance) -> value * chance } + (1.0 - expectation) * values.min()

    /**
     * The prediction names only what it can (a switch to a Pokemon it has seen, the moves the opponent has shown); the
     * chance it leaves over belongs to the moves it does not name, shared evenly. A switch to a Pokemon not yet seen
     * gets only the least share. A token share for every unnamed reply made an unnamed Ferrothorn's 27% switch three
     * quarters of the weight, and spreading the moves' share over unseen switches half of it.
     */
    private fun chances(replies: List<BattleActionCandidate>, likely: Map<String, Double>): List<Double> {
        val named = replies.map { likely[key(it)] }
        val left = (1.0 - named.filterNotNull().sum()).coerceAtLeast(0.0)
        val unnamedMoves = replies.indices.count { named[it] == null && replies[it].kind != BattleActionKind.SWITCH }
        val share = if (unnamedMoves == 0) 0.0 else maxOf(left / unnamedMoves, UNNAMED_REPLY)
        val raw = replies.indices.map { i ->
            named[i] ?: if (replies[i].kind == BattleActionKind.SWITCH) UNNAMED_REPLY else share
        }
        val total = raw.sum()
        return if (total <= 0.0) replies.map { 1.0 / replies.size } else raw.map { it / total }
    }

    private fun key(action: BattleActionCandidate): String = when (action.kind) {
        BattleActionKind.SWITCH -> "switch:${action.switchPokemonId}"
        else -> "move:${action.moveId?.let(PublicIds::canonical)}"
    }

    private fun canonicalKey(key: String): String =
        if (key.startsWith("move:")) "move:" + PublicIds.canonical(key.removePrefix("move:")) else key

    private fun play(
        worker: NativeBranchWorker,
        frame: NativeBattleFrame,
        ours: BattleActionCandidate,
        theirs: BattleActionCandidate,
        template: BattleStateView,
    ): Pair<NativeBattleFrame, BattleStateView> {
        val next = worker.branch(frame.snapshotJson, NativeShowdownChoiceEncoder.encode(ours, BattleSide.ALLY, frame),
            NativeShowdownChoiceEncoder.encode(theirs, BattleSide.OPPONENT, frame))
        return next to NativeBattleStateAdapter.adapt(next, template)
    }

    private fun turnScore(
        state: BattleStateView,
        source: BattleDecisionContext,
        cache: LocalProjectedActionCalculationCache,
        tuning: LocalDecisionTuning,
    ): Double = LocalImmediateTurnScorer.score(source.state, state, source, cache, tuning).total -
        jbro.cobblemon.mcc.betterai.evaluation.LocalTerminalOutcomeValue.evaluate(state) +
        jbro.cobblemon.mcc.betterai.evaluation.LocalTerminalOutcomeValue.evaluate(source.state)

    /**
     * The search's leaf without its finished-battle bonus: the Pokemon a finished battle took are already in the material.
     * Moved into the ranking's units the ±1000 of a won or lost battle swamped everything, so a Yanmega left last, whose
     * attacks lost within two turns, read Protect at 31,000 points and used it every turn (2026-10-13).
     */
    private fun leaf(state: BattleStateView, source: BattleDecisionContext, cache: LocalProjectedActionCalculationCache, tuning: LocalDecisionTuning) =
        LocalLookaheadStateEvaluator.evaluate(state, source, cache, tuning = tuning, includePositionEffects = true) -
            if (LocalLookaheadStateEvaluator.battleEnded(state)) jbro.cobblemon.mcc.betterai.evaluation.LocalTerminalOutcomeValue.evaluate(state) else 0.0

    /** The side's standing beyond HP. */
    private data class Assets(val stages: Int, val field: Double, val status: Double, val hp: Double)

    private fun assets(state: BattleStateView, side: BattleSide, context: BattleDecisionContext) = Assets(
        stages = state.pokemon.filter { it.side == side && it.activeSlot != null && !it.fainted }.sumOf { it.statStages.values.sum() },
        field = LocalImmediateTurnScorer.sideFieldValue(state, side, context),
        status = LocalImmediateTurnScorer.sideStatusBurden(state, side),
        hp = state.pokemon.filter { it.side == side }.sumOf { if (it.fainted) 0.0 else it.hpFraction },
    )

    private fun selfHarm(move: BattleStateView, reference: BattleStateView, context: BattleDecisionContext): Boolean {
        val us = assets(move, BattleSide.ALLY, context)
        val usRef = assets(reference, BattleSide.ALLY, context)
        val them = assets(move, BattleSide.OPPONENT, context)
        val themRef = assets(reference, BattleSide.OPPONENT, context)
        val dealtNothing = them.hp >= themRef.hp - EPSILON
        val gained = us.stages > usRef.stages || us.field > usRef.field + EPSILON || us.status < usRef.status - EPSILON ||
            us.hp > usRef.hp + EPSILON
        val lost = us.stages < usRef.stages || us.field < usRef.field - EPSILON || us.status > usRef.status + EPSILON
        val tookFromThem = them.stages < themRef.stages || them.field < themRef.field - EPSILON || them.status > themRef.status + EPSILON
        return dealtNothing && lost && !gained && !tookFromThem
    }

    /** Everything on the board that outlasts the turn, HP aside. */
    private fun lasting(state: BattleStateView): List<Any?> = listOf(
        state.pokemon.filter { it.activeSlot != null }.map { Triple(it.statStages, it.statusId, it.knownVolatileEffectIds) },
        state.field.weather?.effectId, state.field.terrain?.effectId,
        state.field.roomEffects.map { it.effectId }, state.field.globalEffects.map { it.effectId },
        state.field.sideConditions.mapValues { (_, effects) -> effects.map { it.effectId to it.stacks } },
    )

    private fun same(native: BattleActionCandidate, product: BattleActionCandidate): Boolean = when (product.kind) {
        BattleActionKind.SWITCH -> native.kind == BattleActionKind.SWITCH && native.switchPokemonId == product.switchPokemonId
        BattleActionKind.USE_MOVE -> native.kind == BattleActionKind.USE_MOVE &&
            native.moveId?.let(PublicIds::canonical) == product.moveId?.let(PublicIds::canonical)
        else -> false
    }
}
