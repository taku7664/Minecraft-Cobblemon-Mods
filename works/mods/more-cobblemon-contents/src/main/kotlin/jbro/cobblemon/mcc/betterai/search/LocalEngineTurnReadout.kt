package jbro.cobblemon.mcc.betterai.search

import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.evaluation.LocalImmediateTurnScorer
import jbro.cobblemon.mcc.betterai.evaluation.LocalLookaheadStateEvaluator
import jbro.cobblemon.mcc.betterai.matchup.OpponentIntent
import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleStateAdapter
import jbro.cobblemon.mcc.betterai.simulation.NativeBranchWorker
import jbro.cobblemon.mcc.betterai.simulation.NativeInitialProductWorld
import jbro.cobblemon.mcc.betterai.simulation.NativeInitialProductWorldPlanner
import jbro.cobblemon.mcc.betterai.simulation.NativeOpeningStateRules
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownChoiceEncoder
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownRequestActionFactory
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * Status moves and the attacks they displace, played against the opponent's replies in the engine and read off its
 * board. Nothing is read from a move's name.
 *
 * Each action is played against every reply the opponent has, in its three likeliest worlds, and the board after it
 * is valued by the search's leaf ([LocalLookaheadStateEvaluator]). The replies are weighed by how likely the
 * opponent is to make them ([OpponentIntent], the same prediction the switching rules use), with a share of the
 * worst one mixed in ([LocalDecisionTuning.statusReplyExpectation] is the likely part). Taking only the worst read
 * every reply as the one that hurts most, often the opponent switching out, which drops its own stages and so made
 * wiping them worth nothing. Each first turn is drawn on three random streams. A move whose data declares a lasting
 * effect is played a second turn instead (on one stream): this side's best attack against the opponent's three
 * likeliest replies, weighed the same way.
 *
 * The same boards, compared side by side against the reference attack under each reply, give:
 * - [Reading.selfHarm]: in every reply the move dealt nothing, took something from this side (its stages, a condition
 *   the leaf counts in its favour, a clean status) and gave this side and took from the opponent nothing.
 * - [Reading.changed]: in some reply the move left the board different beyond HP (stages, volatiles, statuses, either
 *   side's conditions, weather, terrain, rooms). Reported only; it does not send a move to a second turn.
 */
internal object LocalEngineTurnReadout {
    data class Reading(
        val selfHarm: Set<String>,
        val changed: Set<String>,
        /** Board value per move ID and per reference action ID, worlds weighted. */
        val values: Map<String, Double>,
        val plies: Int,
    )

    private const val WORLDS = 3
    private const val EPSILON = 1e-6
    /** The least chance a reply the prediction does not name gets (a hypothesised move, an unseen switch-in). */
    private const val UNNAMED_REPLY = 0.02
    private const val SECOND_TURN_REPLIES = 3
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
            val firstTurns = worlds.map { played ->
                val template = played.world.publicContext.state
                val replies = NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, played.root, allowedMechanics = emptySet())
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
            // Two turns only for a move whose data declares a lasting effect: read off the board instead, nearly every
            // status move qualified and a decision took up to 1.5 s on a desktop. A scripted move (Haze) stays one turn.
            val plies = if (lastingDeclared && maxPlies >= 2) 2 else 1
            val seeds = if (plies == 2) 1 else SEEDS
            val values = linkedMapOf<String, Double>()
            val weight = worlds.sumOf { it.world.probability }
            for (turn in firstTurns) {
                val cache = LocalProjectedActionCalculationCache()
                val source = turn.played.world.publicContext
                for ((key, boards) in turn.boards) {
                    // Each first turn drawn on SEEDS random streams (a miss, a critical hit, a secondary effect); the
                    // comparison above reads the first draw only.
                    val action = turn.actions.getValue(key)
                    val perReply = turn.replies.mapIndexed { r, reply ->
                        (0 until seeds).map { seed ->
                            val (frame, state) = if (seed == 0) boards[r] else play(worker,
                                worker.reseed(turn.played.root.snapshotJson, seed), action, reply, turn.played.world.publicContext.state)
                            if (plies == 1 || frame.ended || frame.requestState != "move") leaf(state, source, cache, tuning)
                            else secondTurn(worker, frame, turn.played.world, source, cache, tuning, likely, expectation)
                        }.average()
                    }
                    val value = blend(perReply, turn.chances, expectation)
                    values.merge(key, turn.played.world.probability / weight * value, Double::plus)
                }
            }
            Reading(harmedEverywhere.filter { (id, harmed) -> harmed && id in seen }.keys, changed, values, plies)
        }
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

    /** This side's best attack on the second turn, against the opponent's three likeliest replies. */
    private fun secondTurn(
        worker: NativeBranchWorker,
        frame: NativeBattleFrame,
        world: NativeInitialProductWorld,
        source: BattleDecisionContext,
        cache: LocalProjectedActionCalculationCache,
        tuning: LocalDecisionTuning,
        likely: Map<String, Double>,
        expectation: Double,
    ): Double {
        val template = world.publicContext.state
        val ours = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, frame, allowedMechanics = emptySet())
        val attacks = ours.filter { it.kind == BattleActionKind.USE_MOVE && it.moveDetails?.damageCategory != BattleMoveDamageCategory.STATUS }
            .ifEmpty { ours.filter { it.kind == BattleActionKind.USE_MOVE } }.ifEmpty { ours }
        val all = NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, frame, allowedMechanics = emptySet())
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
