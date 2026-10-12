package jbro.cobblemon.mcc.betterai.search

import jbro.cobblemon.mcc.betterai.evaluation.LocalImmediateTurnScorer
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleStateAdapter
import jbro.cobblemon.mcc.betterai.simulation.NativeInitialProductWorldPlanner
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownChoiceEncoder
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownRequestActionFactory
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * What a status move did in its own turn, against what the reference action did there, read off the engine's board.
 *
 * Each move and the reference are played against every reply the opponent has, in its three likeliest worlds, and
 * the two boards after each reply are compared side by side. Nothing is read from a move's name:
 * - [selfHarm]: in every reply the move dealt nothing, took something from this side (its stages, a condition the
 *   leaf counts in its favour, a clean status) and gave this side and took from the opponent nothing. Haze with only
 *   this side boosted, Defog with only this side's hazards down, Court Change with only this side's screens up.
 * - [changed]: in some reply the move left the board different beyond HP (stages, volatiles, statuses, either side's
 *   conditions, weather, terrain, rooms), so its worth lies past its own turn as well.
 */
internal object LocalEngineTurnReadout {
    data class Reading(val selfHarm: Set<String>, val changed: Set<String>)

    private const val WORLDS = 3
    private const val EPSILON = 1e-6
    private val planner = NativeInitialProductWorldPlanner(worldLimit = { _, _ -> WORLDS })
    private val logger = org.slf4j.LoggerFactory.getLogger("BetterAI")

    /** Keyed by move ID: a status move with a mechanic reads as its plain version. */
    fun read(
        context: BattleDecisionContext,
        tier: BattleTrainerTier,
        moves: List<BattleActionCandidate>,
        reference: BattleActionCandidate,
    ): Reading? {
        val live = context.showdownState
        val plan = when {
            live != null -> planner.planLive(context, tier)
            // The opening plan only where the native evaluator takes it (NativeInitialProductDecisionEvaluator.isOpeningCandidate).
            context.state.turn in 0..1 && jbro.cobblemon.mcc.betterai.simulation.NativeOpeningStateRules.acceptsObservations(context.state) ->
                planner.plan(context, tier)
            else -> planner.planMidBattle(context, tier)
        }
        if (plan.issues.isNotEmpty() || plan.worlds.isEmpty()) {
            logger.error("[BetterAI] Turn readout could not plan the opponent's worlds: {}", plan.issues.map { it.code.name + ":" + it.detailCode })
            return null
        }
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
            (context.deadlineEpochMillis - System.currentTimeMillis()).coerceAtLeast(1L))
        val ids = moves.mapNotNull { it.moveId?.let(PublicIds::canonical) }.distinct()
        return LocalEngineStopProbe.lease.withWorker(deadline) { worker ->
            val harmedEverywhere = ids.associateWith { true }.toMutableMap()
            val seen = hashSetOf<String>()
            val changed = hashSetOf<String>()
            for (world in plan.worlds) {
                val root = live?.let { worker.createLiveBattle(it, world.definition) } ?: worker.createBattle(world.definition)
                val template = world.publicContext.state
                val ally = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, root, allowedMechanics = emptySet())
                val ref = ally.firstOrNull { same(it, reference) } ?: continue
                val replies = NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, root, allowedMechanics = emptySet())
                for (reply in replies) {
                    val refBoard = board(worker.branch(root.snapshotJson, choice(ref, BattleSide.ALLY, root), choice(reply, BattleSide.OPPONENT, root)), template)
                    for (id in ids) {
                        val move = ally.firstOrNull { it.kind == BattleActionKind.USE_MOVE && it.moveId?.let(PublicIds::canonical) == id } ?: continue
                        val moveBoard = board(worker.branch(root.snapshotJson, choice(move, BattleSide.ALLY, root), choice(reply, BattleSide.OPPONENT, root)), template)
                        seen += id
                        if (lasting(moveBoard) != lasting(refBoard)) changed += id
                        if (!selfHarm(moveBoard, refBoard, context)) harmedEverywhere[id] = false
                    }
                }
            }
            Reading(harmedEverywhere.filter { (id, harmed) -> harmed && id in seen }.keys, changed)
        }
    }

    private fun board(frame: NativeBattleFrame, template: BattleStateView): BattleStateView = NativeBattleStateAdapter.adapt(frame, template)

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

    private fun choice(action: BattleActionCandidate, side: BattleSide, frame: NativeBattleFrame) =
        NativeShowdownChoiceEncoder.encode(action, side, frame)
}
