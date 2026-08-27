package jbro.cobblemon.morebattlecontent.betterai.outcome

import java.util.IdentityHashMap
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicFutureActionFactory
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicMoveOutcomeBranchProjector
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalImmediateTurnScorer
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalLookaheadStateEvaluator
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalTacticalSituationalEvaluator
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalBadPoisonCounter
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalContactAfterHitMechanics
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalDirectHitMechanics
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalObservedActionOrder
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalPublicStatusImmunity
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalStallingProtectionRules
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalStanceChangeStateProjector
import jbro.cobblemon.morebattlecontent.betterai.mechanics.RecursiveControlEffect
import jbro.cobblemon.morebattlecontent.betterai.mechanics.RecursiveControlEffectKind
import jbro.cobblemon.morebattlecontent.betterai.mechanics.RecursiveDelayedStrike
import jbro.cobblemon.morebattlecontent.betterai.mechanics.copyState
import jbro.cobblemon.morebattlecontent.betterai.state.LocalEndTurnStateProjector
import jbro.cobblemon.morebattlecontent.betterai.state.LocalFieldEffectProjector
import jbro.cobblemon.morebattlecontent.betterai.state.LocalSwitchStateProjector
import jbro.cobblemon.morebattlecontent.betterai.state.PublicTurnProjection
import jbro.cobblemon.morebattlecontent.betterai.state.RecursiveActionHistory
import kotlin.math.roundToInt

/**
 * Event-free execution of one complete turn from both sides' submitted actions.
 *
 * This is a projection, not a search: it takes two chosen actions and reports the resulting public
 * states. The search calls it; it never calls the search.
 */

private data class TurnPrimitiveAction(
    val side: BattleSide,
    val action: BattleActionCandidate,
    val actorPokemonId: UUID?,
)

private data class WeightedOrder(val actions: List<TurnPrimitiveAction>, val probability: Double)

internal enum class ChanceEffectProjectionMode {
    EXPECTED_SCORE,
    BRANCH_STATE,
}

internal object PublicSingleTurnProjector {
    fun project(
        initialState: BattleStateView,
        allyAction: BattleActionCandidate,
        opponentAction: BattleActionCandidate,
        sourceContext: BattleDecisionContext,
        history: RecursiveActionHistory = RecursiveActionHistory(),
        maxChanceBranchesPerMove: Int = MAX_CHANCE_BRANCHES_PER_MOVE,
        chanceEffectMode: ChanceEffectProjectionMode = ChanceEffectProjectionMode.EXPECTED_SCORE,
        calculationCache: LocalProjectedActionCalculationCache = LocalProjectedActionCalculationCache(),
        shouldContinue: () -> Boolean = { true },
    ): List<PublicTurnProjection> {
        require(initialState.format == BattleFormat.SINGLE || initialState.format == BattleFormat.DOUBLE)
        require(maxChanceBranchesPerMove > 0)
        if (!shouldContinue()) return emptyList()
        val turnActions = primitiveTurnActions(initialState, BattleSide.ALLY, allyAction) +
            primitiveTurnActions(initialState, BattleSide.OPPONENT, opponentAction)
        var switchedState = initialState
        turnActions.filter { it.action.kind == BattleActionKind.SWITCH }.forEach { ordered ->
            if (!shouldContinue()) return emptyList()
            switchedState = applySwitch(
                switchedState,
                ordered.side,
                ordered.action,
                sourceContext,
                calculationCache,
            )
        }

        val moveActions = turnActions.filter { it.action.kind == BattleActionKind.USE_MOVE }
        val orders = when (moveActions.size) {
            0 -> listOf(WeightedOrder(emptyList(), 1.0))
            1 -> listOf(WeightedOrder(moveActions, 1.0))
            // The decision state is passed alongside the projected one because an action-order
            // observation only describes the speed conditions it was seen under, and those are the
            // conditions of the state the inferences were drawn from.
            else -> possibleOrders(sourceContext.state, switchedState, moveActions)
        }
        return orders.flatMap { weightedOrder ->
            val order = weightedOrder.actions
            if (!shouldContinue()) return emptyList()
            var branches = listOf(
                WeightedState(
                    state = switchedState,
                    probability = 1.0,
                    lastMoveByPokemon = history.lastMoveByPokemon,
                ),
            )
            order.forEachIndexed { actionIndex, ordered ->
                branches = branches.flatMap { branch ->
                    if (!shouldContinue()) return emptyList()
                    applyMove(
                        branch.state,
                        ordered.side,
                        ordered.action,
                        sourceContext,
                        branch.protectedPokemonIds,
                        branch.protectionAttackDrops,
                        branch.tauntedPokemonIds,
                        branch.forcedMoveIdsByPokemon,
                        history,
                        maxChanceBranchesPerMove,
                        chanceEffectMode,
                        calculationCache,
                        shouldContinue,
                        pendingDamagingMovePokemonIds = order.drop(actionIndex + 1).mapNotNullTo(linkedSetOf()) { pending ->
                            val pendingAction = pending.action
                            if (pendingAction.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS) {
                                return@mapNotNullTo null
                            }
                            branch.state.pokemon.singleOrNull {
                                it.side == pending.side && it.activeSlot == pendingAction.actorSlot && !it.fainted && it.hpFraction > 0.0
                            }?.battlePokemonId
                        },
                    ).map { outcome ->
                        val newlyTaunted = outcome.controlEffects.filter {
                            it.kind == RecursiveControlEffectKind.TAUNT
                        }.mapTo(linkedSetOf()) { it.targetPokemonId }
                        val newlyForced = outcome.controlEffects.filter {
                            it.kind == RecursiveControlEffectKind.ENCORE && it.targetPokemonId !in outcome.executedMoveIdsByPokemon
                        }.mapNotNull { effect ->
                            branch.lastMoveByPokemon[effect.targetPokemonId]?.let { effect.targetPokemonId to it }
                        }.toMap()
                        WeightedState(
                            outcome.state,
                            branch.probability * outcome.probability,
                            branch.executedSides + outcome.executedSides,
                            branch.protectedPokemonIds + outcome.protectedPokemonIds,
                            branch.controlEffects + outcome.controlEffects,
                            branch.switchedSides + outcome.switchedSides,
                            branch.protectionAttackDrops + outcome.protectionAttackDrops,
                            branch.tauntedPokemonIds + outcome.tauntedPokemonIds + newlyTaunted,
                            branch.forcedMoveIdsByPokemon + outcome.forcedMoveIdsByPokemon + newlyForced,
                            branch.lastMoveByPokemon + outcome.executedMoveIdsByPokemon,
                            branch.executedMoveIdsByPokemon + outcome.executedMoveIdsByPokemon,
                            branch.expectedScoreAdjustment + outcome.expectedScoreAdjustment,
                            branch.protectionResultsByPokemon + outcome.protectionResultsByPokemon,
                        )
                    }
                }.let { mergeBranches(it, maxChanceBranchesPerMove) }
            }
            history.delayedStrikes.filter { it.remainingTurns == 1 }.forEach { strike ->
                branches = branches.flatMap { branch ->
                    resolveDelayedStrike(branch.state, strike, sourceContext).map { resolved ->
                        branch.copy(
                            state = resolved.state,
                            probability = branch.probability * resolved.probability,
                        )
                    }
                }.let { mergeBranches(it, maxChanceBranchesPerMove) }
            }
            branches.map { outcome ->
                val badPoisonTurns = LocalBadPoisonCounter.advance(initialState, outcome.state, history.badPoisonTurnsByPokemon)
                val saltCuredPokemonIds = (history.saltCuredPokemonIds + outcome.controlEffects
                    .filter { it.kind == RecursiveControlEffectKind.SALT_CURE }
                    .map { it.targetPokemonId })
                    .filterTo(linkedSetOf()) { id ->
                        outcome.state.pokemon.any {
                            it.battlePokemonId == id && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
                        }
                    }
                PublicTurnProjection(
                    incrementTurn(
                        LocalEndTurnStateProjector.project(
                            outcome.state,
                            badPoisonTurns,
                            saltCuredPokemonIds,
                        ),
                    ),
                    order.map(TurnPrimitiveAction::side),
                    order.mapNotNull(TurnPrimitiveAction::actorPokemonId),
                    outcome.probability,
                    weightedOrder.probability,
                    outcome.executedSides,
                    outcome.controlEffects,
                    outcome.switchedSides,
                    outcome.executedMoveIdsByPokemon,
                    badPoisonTurns,
                    outcome.expectedScoreAdjustment,
                    outcome.protectionResultsByPokemon,
                )
            }
        }.groupBy { projection ->
            listOf(
                projection.order,
                projection.actionOrderPokemonIds,
                fingerprint(projection.state),
                projection.executedSides,
                projection.controlEffects,
                projection.switchedSides,
                projection.executedMoveIdsByPokemon,
                projection.badPoisonTurnsByPokemon,
                projection.protectionResultsByPokemon,
            )
        }
            .values
            .map { identical ->
                val probability = identical.sumOf(PublicTurnProjection::probability)
                identical.first().copy(
                    probability = probability,
                    expectedScoreAdjustment = weightedExpectedScoreAdjustment(
                        identical.map { it.probability to it.expectedScoreAdjustment },
                        probability,
                    ),
                )
            }
    }

    private fun primitiveTurnActions(
        state: BattleStateView,
        side: BattleSide,
        submitted: BattleActionCandidate,
    ): List<TurnPrimitiveAction> {
        val actions = if (submitted.kind == BattleActionKind.COMPOSITE) submitted.componentActions else listOf(submitted)
        return actions.map { action ->
            val actorId = action.actorSlot?.let { slot ->
                state.pokemon.firstOrNull {
                    it.side == side && it.activeSlot == slot && !it.fainted && it.hpFraction > 0.0
                }?.battlePokemonId
            }
            TurnPrimitiveAction(side, action, actorId)
        }
    }

    private fun applySwitch(
        state: BattleStateView,
        side: BattleSide,
        action: BattleActionCandidate,
        sourceContext: BattleDecisionContext,
        calculationCache: LocalProjectedActionCalculationCache,
    ): BattleStateView {
        val actionWithoutFacts = action.withoutFacts()
        val calculatedContext = calculationCache.getOrCalculate(state, side, actionWithoutFacts) {
            PublicBattleTacticalCalculator.calculate(
                actionContext(state, actionWithoutFacts, sourceContext),
                side,
            )
        }
        return LocalSwitchStateProjector.project(state, side, calculatedContext.candidates.single())
    }

    private fun applyMove(
        state: BattleStateView,
        side: BattleSide,
        action: BattleActionCandidate,
        sourceContext: BattleDecisionContext,
        protectedPokemonIds: Set<UUID>,
        protectionAttackDrops: Map<UUID, Int> = emptyMap(),
        tauntedPokemonIds: Set<UUID> = emptySet(),
        forcedMoveIdsByPokemon: Map<UUID, String> = emptyMap(),
        history: RecursiveActionHistory = RecursiveActionHistory(),
        maxChanceBranchesPerMove: Int = MAX_CHANCE_BRANCHES_PER_MOVE,
        chanceEffectMode: ChanceEffectProjectionMode = ChanceEffectProjectionMode.EXPECTED_SCORE,
        calculationCache: LocalProjectedActionCalculationCache = LocalProjectedActionCalculationCache(),
        shouldContinue: () -> Boolean = { true },
        availabilityChecked: Boolean = false,
        pendingDamagingMovePokemonIds: Set<UUID> = emptySet(),
    ): List<WeightedState> {
        if (!shouldContinue()) return emptyList()
        val actorBeforeTransition = state.pokemon.firstOrNull {
            it.side == side && it.activeSlot == action.actorSlot && !it.fainted && it.hpFraction > 0.0
        } ?: return listOf(WeightedState(state, 1.0, protectedPokemonIds = protectedPokemonIds))
        val publicStatus = canonicalId(actorBeforeTransition.statusId)
        if (
            !availabilityChecked && publicStatus in SLEEP_IDS &&
            action.moveDetails?.effects?.effects.orEmpty().none {
                it.kind == BattleMoveEffectKind.USABLE_WHILE_ASLEEP
            }
        ) {
            return listOf(WeightedState(state, 1.0, protectedPokemonIds = protectedPokemonIds))
        }
        if (!availabilityChecked && publicStatus in FREEZE_IDS) {
            val unable = WeightedState(
                state = state,
                probability = 1.0 - FREEZE_THAW_PROBABILITY,
                protectedPokemonIds = protectedPokemonIds,
            )
            val thawedState = state.copyState(
                pokemon = state.pokemon.map { pokemon ->
                    if (pokemon.battlePokemonId == actorBeforeTransition.battlePokemonId) {
                        pokemon.copyState(statusId = null)
                    } else {
                        pokemon
                    }
                },
            )
            val able = applyMove(
                thawedState,
                side,
                action,
                sourceContext,
                protectedPokemonIds,
                protectionAttackDrops,
                tauntedPokemonIds,
                forcedMoveIdsByPokemon,
                history,
                maxChanceBranchesPerMove,
                chanceEffectMode,
                calculationCache,
                shouldContinue,
                availabilityChecked = true,
                pendingDamagingMovePokemonIds = pendingDamagingMovePokemonIds,
            ).map { outcome -> outcome.copy(probability = outcome.probability * FREEZE_THAW_PROBABILITY) }
            return listOf(unable) + able
        }
        if (!availabilityChecked && publicStatus in PARALYSIS_IDS) {
            val unable = WeightedState(
                state = state,
                probability = FULL_PARALYSIS_PROBABILITY,
                protectedPokemonIds = protectedPokemonIds,
            )
            val able = applyMove(
                state,
                side,
                action,
                sourceContext,
                protectedPokemonIds,
                protectionAttackDrops,
                tauntedPokemonIds,
                forcedMoveIdsByPokemon,
                history,
                maxChanceBranchesPerMove,
                chanceEffectMode,
                calculationCache,
                shouldContinue,
                availabilityChecked = true,
                pendingDamagingMovePokemonIds = pendingDamagingMovePokemonIds,
            ).map { outcome ->
                outcome.copy(probability = outcome.probability * (1.0 - FULL_PARALYSIS_PROBABILITY))
            }
            return listOf(unable) + able
        }
        val effectiveAction = forcedMoveIdsByPokemon[actorBeforeTransition.battlePokemonId]
            ?.let { forcedMoveId ->
                forcedMoveAction(state, side, actorBeforeTransition.battlePokemonId, forcedMoveId, sourceContext)
            }
            ?: action
        if (
            effectiveAction.moveDetails?.targetPattern == BattleMoveTargetPattern.RANDOM_OPPONENT &&
            effectiveAction.targets.isEmpty()
        ) {
            val targets = state.pokemon.filter {
                it.side != side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
            }
            if (targets.isEmpty()) return listOf(WeightedState(state, 1.0))
            val targetProbability = 1.0 / targets.size
            return targets.flatMap { randomTarget ->
                applyMove(
                    state = state,
                    side = side,
                    action = effectiveAction.withResolvedRandomTarget(randomTarget),
                    sourceContext = sourceContext,
                    protectedPokemonIds = protectedPokemonIds,
                    protectionAttackDrops = protectionAttackDrops,
                    tauntedPokemonIds = tauntedPokemonIds,
                    forcedMoveIdsByPokemon = forcedMoveIdsByPokemon,
                    history = history,
                    maxChanceBranchesPerMove = maxChanceBranchesPerMove,
                    chanceEffectMode = chanceEffectMode,
                    calculationCache = calculationCache,
                    shouldContinue = shouldContinue,
                    availabilityChecked = true,
                    pendingDamagingMovePokemonIds = pendingDamagingMovePokemonIds,
                ).map { it.copy(probability = it.probability * targetProbability) }
            }.let { mergeBranches(it, maxChanceBranchesPerMove) }
        }
        val projectedFormState = LocalStanceChangeStateProjector.beforeMove(state, side, effectiveAction)
        val actor = projectedFormState.pokemon.single { it.battlePokemonId == actorBeforeTransition.battlePokemonId }
        if (actor.battlePokemonId in tauntedPokemonIds &&
            effectiveAction.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS
        ) {
            return listOf(WeightedState(projectedFormState, 1.0))
        }
        val actionWithoutFacts = effectiveAction.withoutFacts()
        val calculated = calculationCache.getOrCalculate(projectedFormState, side, actionWithoutFacts) {
            PublicBattleTacticalCalculator.calculate(
                actionContext(projectedFormState, actionWithoutFacts, sourceContext),
                side,
            )
        }
        val calculatedAction = calculated.candidates.single()
        val projection = PublicActionOutcomeProjector.project(calculatedAction, calculated, side)
        val defaultTargetSide = if (side == BattleSide.ALLY) BattleSide.OPPONENT else BattleSide.ALLY
        val explicitTarget = calculatedAction.targets.singleOrNull()
        val target = if (explicitTarget != null) {
            projectedFormState.pokemon.firstOrNull {
                it.side == explicitTarget.side && it.activeSlot == explicitTarget.slot && !it.fainted && it.hpFraction > 0.0
            }
        } else {
            projectedFormState.pokemon.singleOrNull {
                it.side == defaultTargetSide && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
            }
        }
        val effects = calculatedAction.moveDetails?.effects?.effects.orEmpty()
        val moveId = requireNotNull(effectiveAction.moveId)
        if (LocalTacticalSituationalEvaluator.hasUnmetPublicRequirement(calculatedAction, calculated, side)) {
            return listOf(
                WeightedState(
                    state = projectedFormState,
                    probability = 1.0,
                    executedSides = setOf(side),
                    executedMoveIdsByPokemon = mapOf(actor.battlePokemonId to moveId),
                ),
            )
        }
        if (
            actor.battlePokemonId in history.actedSinceEntryPokemonIds &&
            effects.any { it.kind == BattleMoveEffectKind.FIRST_ACTIVE_TURN_ONLY }
        ) {
            return listOf(
                WeightedState(
                    state = projectedFormState,
                    probability = 1.0,
                    executedSides = setOf(side),
                    executedMoveIdsByPokemon = mapOf(actor.battlePokemonId to moveId),
                ),
            )
        }
        val slotCondition = effects.singleOrNull { it.kind == BattleMoveEffectKind.SLOT_CONDITION }
        if (canonicalId(slotCondition?.valueId) == "revivalblessing") {
            val faintedAllies = projectedFormState.pokemon.filter { it.side == side && it.fainted }
            if (faintedAllies.isEmpty()) {
                return listOf(
                    WeightedState(
                        projectedFormState,
                        1.0,
                        executedSides = setOf(side),
                        executedMoveIdsByPokemon = mapOf(actor.battlePokemonId to moveId),
                    ),
                )
            }
            return faintedAllies.map { fainted ->
                WeightedState(
                    state = projectedFormState.copyState(
                        pokemon = projectedFormState.pokemon.map { pokemon ->
                            if (pokemon.battlePokemonId == fainted.battlePokemonId) {
                                pokemon.copyState(hpFraction = 0.5, fainted = false)
                            } else {
                                pokemon
                            }
                        },
                    ),
                    probability = 1.0 / faintedAllies.size,
                    executedSides = setOf(side),
                    executedMoveIdsByPokemon = mapOf(actor.battlePokemonId to moveId),
                )
            }
        }
        val chargingContinuation = history.chargingMoveByPokemon[actor.battlePokemonId]
            ?.let { canonicalId(it) == canonicalId(moveId) } == true
        val skipsCharge = effects.any { effect ->
            effect.kind == BattleMoveEffectKind.CHARGE_SKIP_WEATHER &&
                canonicalId(effect.valueId) == canonicalId(projectedFormState.field.weather?.effectId)
        }
        if (
            effects.any { it.kind == BattleMoveEffectKind.CHARGE_TURN } &&
            !chargingContinuation && !skipsCharge
        ) {
            return listOf(
                WeightedState(
                    state = projectedFormState,
                    probability = 1.0,
                    executedSides = setOf(side),
                    controlEffects = listOf(
                        RecursiveControlEffect(
                            kind = RecursiveControlEffectKind.CHARGE,
                            sourceSide = side,
                            sourcePokemonId = actor.battlePokemonId,
                            targetPokemonId = actor.battlePokemonId,
                            valueId = moveId,
                        ),
                    ),
                    executedMoveIdsByPokemon = mapOf(actor.battlePokemonId to moveId),
                ),
            )
        }
        if (canonicalId(slotCondition?.valueId) == "futuremove" && target?.activeSlot != null) {
            val delayed = RecursiveDelayedStrike(
                sourcePokemon = actor,
                sourceSide = side,
                targetSide = target.side,
                targetSlot = requireNotNull(target.activeSlot),
                moveId = moveId,
                moveDetails = requireNotNull(calculatedAction.moveDetails),
                remainingTurns = FUTURE_MOVE_DELAY_TURNS,
            )
            return listOf(
                WeightedState(
                    state = projectedFormState,
                    probability = 1.0,
                    executedSides = setOf(side),
                    controlEffects = listOf(
                        RecursiveControlEffect(
                            RecursiveControlEffectKind.DELAYED_STRIKE,
                            side,
                            actor.battlePokemonId,
                            target.battlePokemonId,
                            delayedStrike = delayed,
                        ),
                    ),
                    executedMoveIdsByPokemon = mapOf(actor.battlePokemonId to moveId),
                ),
            )
        }
        val spreadTargets = damagingSpreadTargets(projectedFormState, side, actor, calculatedAction)
        if (spreadTargets.isNotEmpty()) {
            return applyDamagingSpreadMove(
                state = projectedFormState,
                side = side,
                actor = actor,
                originalAction = effectiveAction,
                targets = spreadTargets,
                sourceContext = sourceContext,
                effects = effects,
                protectedPokemonIds = protectedPokemonIds,
                protectionAttackDrops = protectionAttackDrops,
                history = history,
                maxChanceBranchesPerMove = maxChanceBranchesPerMove,
                chanceEffectMode = chanceEffectMode,
                calculationCache = calculationCache,
                shouldContinue = shouldContinue,
            )
        }
        val requiresPendingDamagingMove = calculatedAction.moveDetails?.effects?.requirements.orEmpty().any {
            it.kind == BattleMoveRequirementKind.TARGET_PENDING_DAMAGING_MOVE
        }
        if (requiresPendingDamagingMove && target?.battlePokemonId !in pendingDamagingMovePokemonIds) {
            return listOf(
                WeightedState(
                    state = projectedFormState,
                    probability = 1.0,
                    executedSides = setOf(side),
                    executedMoveIdsByPokemon = mapOf(actor.battlePokemonId to requireNotNull(effectiveAction.moveId)),
                ),
            )
        }
        if (effects.any { it.kind == BattleMoveEffectKind.PROTECT_USER && (it.probability ?: 1.0) == 1.0 }) {
            val contactDrop = if (canonicalId(calculatedAction.moveId) == "kingsshield") 1 else 0
            val usesSharedStallCheck = LocalStallingProtectionRules.isStallingProtection(calculatedAction)
            val successProbability = if (usesSharedStallCheck) {
                LocalStallingProtectionRules.nextSuccessProbability(
                    history.protectionChainByPokemon[actor.battlePokemonId] ?: 0,
                )
            } else {
                1.0
            }
            val successResult = if (usesSharedStallCheck) mapOf(actor.battlePokemonId to true) else emptyMap()
            val success = WeightedState(
                state = projectedFormState,
                probability = successProbability,
                executedSides = setOf(side),
                protectedPokemonIds = setOf(actor.battlePokemonId),
                protectionAttackDrops = if (contactDrop > 0) mapOf(actor.battlePokemonId to contactDrop) else emptyMap(),
                executedMoveIdsByPokemon = mapOf(actor.battlePokemonId to requireNotNull(effectiveAction.moveId)),
                protectionResultsByPokemon = successResult,
            )
            if (successProbability >= CERTAIN_PROBABILITY) return listOf(success)
            return listOf(
                success,
                WeightedState(
                    state = projectedFormState,
                    probability = 1.0 - successProbability,
                    executedSides = setOf(side),
                    executedMoveIdsByPokemon = mapOf(actor.battlePokemonId to requireNotNull(effectiveAction.moveId)),
                    protectionResultsByPokemon = mapOf(actor.battlePokemonId to false),
                ),
            )
        }
        val targetIsProtected = target?.battlePokemonId in protectedPokemonIds
        val breaksProtection = effects.any { it.kind == BattleMoveEffectKind.BREAKS_PROTECTION }
        if (projection.publiclyNullified || (targetIsProtected && !breaksProtection)) {
            val contact = "contact" in calculatedAction.moveDetails?.effects?.mechanicFlags.orEmpty()
            val attackDrop = target?.battlePokemonId?.let(protectionAttackDrops::get) ?: 0
            val protectionState = if (targetIsProtected && contact && attackDrop > 0) {
                applyStatStage(projectedFormState, actor.battlePokemonId, "attack", -attackDrop)
            } else {
                projectedFormState
            }
            val blockedState = applyCrashRecoil(protectionState, actor.battlePokemonId, effects)
            return listOf(
                WeightedState(
                    state = blockedState,
                    probability = 1.0,
                    executedSides = setOf(side),
                    executedMoveIdsByPokemon = effectiveAction.moveId?.let {
                        mapOf(actor.battlePokemonId to it)
                    }.orEmpty(),
                ),
            )
        }
        return PublicMoveOutcomeBranchProjector.project(calculatedAction, calculated, side).flatMap { moveOutcome ->
            if (!moveOutcome.hit) {
                listOf(
                    WeightedState(
                        state = applyCrashRecoil(projectedFormState, actor.battlePokemonId, effects),
                        probability = moveOutcome.probability,
                        executedSides = setOf(side),
                        executedMoveIdsByPokemon = effectiveAction.moveId?.let {
                            mapOf(actor.battlePokemonId to it)
                        }.orEmpty(),
                    ),
                )
            } else {
                val appliedHit = LocalDirectHitMechanics.apply(
                    projectedFormState,
                    actor.battlePokemonId,
                    target?.battlePokemonId,
                    moveOutcome.damageFraction,
                    effects,
                    ignoreTargetAbility = ignoresTargetAbility(actor, calculatedAction),
                )
                LocalContactAfterHitMechanics.project(
                    appliedHit.state,
                    actor.battlePokemonId,
                    target?.battlePokemonId,
                    calculatedAction,
                    appliedHit.directDamageFraction,
                ).flatMap { contactOutcome ->
                    applyChanceEffects(
                        contactOutcome.state,
                        actor.battlePokemonId,
                        target?.battlePokemonId,
                        effects,
                        executedSide = side,
                        mode = chanceEffectMode,
                        shouldContinue = shouldContinue,
                    ).map { it.copy(probability = it.probability * contactOutcome.probability) }
                }.flatMap { effectOutcome ->
                    applyForcedTargetSwitch(
                        effectOutcome,
                        target,
                        effects,
                        sourceContext,
                        calculationCache,
                    )
                }.map { effectOutcome ->
                    val recharge = effects.any {
                        it.kind == BattleMoveEffectKind.RECHARGE_TURN && (it.probability ?: 1.0) >= CERTAIN_PROBABILITY
                    }
                    effectOutcome.copy(
                        probability = effectOutcome.probability * moveOutcome.probability,
                        expectedScoreAdjustment = effectOutcome.expectedScoreAdjustment +
                            LocalImmediateTurnScorer.expectedKnockoutBonus(
                                side,
                                moveOutcome.knockoutProbability,
                            ),
                        controlEffects = effectOutcome.controlEffects + if (recharge) {
                            listOf(
                                RecursiveControlEffect(
                                    RecursiveControlEffectKind.RECHARGE,
                                    side,
                                    actor.battlePokemonId,
                                    actor.battlePokemonId,
                                ),
                            )
                        } else {
                            emptyList()
                        },
                        executedMoveIdsByPokemon = effectiveAction.moveId?.let {
                            mapOf(actor.battlePokemonId to it)
                        }.orEmpty(),
                    )
                }.map { effectOutcome ->
                    effectOutcome.copy(
                        controlEffects = effectOutcome.controlEffects + scriptedTrapEffects(
                            effectiveAction,
                            side,
                            actor.battlePokemonId,
                            target?.battlePokemonId,
                        ),
                    )
                }.map { effectOutcome ->
                    if (effects.any { it.kind == BattleMoveEffectKind.SWITCH_USER }) {
                        applyPivotSwitch(
                            effectOutcome,
                            side,
                            actor.battlePokemonId,
                            sourceContext,
                            history,
                            calculationCache,
                            shouldContinue,
                        )
                    } else {
                        effectOutcome
                    }
                }
            }
        }.let { mergeBranches(it, maxChanceBranchesPerMove) }
            .map { outcome ->
                if (
                    LocalStallingProtectionRules.advancesSharedCounter(calculatedAction) &&
                    actor.battlePokemonId in outcome.executedMoveIdsByPokemon
                ) {
                    outcome.copy(protectionResultsByPokemon = mapOf(actor.battlePokemonId to true))
                } else {
                    outcome
                }
            }
    }

    private fun applyCrashRecoil(
        state: BattleStateView,
        actorPokemonId: UUID,
        effects: List<BattleMoveEffectView>,
    ): BattleStateView {
        if (effects.none { it.kind == BattleMoveEffectKind.CRASH_RECOIL }) return state
        val actor = state.pokemon.singleOrNull { it.battlePokemonId == actorPokemonId } ?: return state
        if (canonicalId(actor.knownAbilityId) == "magicguard" || actor.fainted || actor.hpFraction <= 0.0) return state
        val hp = (actor.hpFraction - 0.5).coerceAtLeast(0.0)
        return state.copyState(
            pokemon = state.pokemon.map { pokemon ->
                if (pokemon.battlePokemonId == actorPokemonId) {
                    pokemon.copyState(hpFraction = hp, fainted = hp <= 0.0)
                } else {
                    pokemon
                }
            },
        )
    }

    private data class DelayedStrikeResolution(val state: BattleStateView, val probability: Double)

    private fun resolveDelayedStrike(
        state: BattleStateView,
        strike: RecursiveDelayedStrike,
        sourceContext: BattleDecisionContext,
    ): List<DelayedStrikeResolution> {
        val target = state.pokemon.singleOrNull {
            it.side == strike.targetSide && it.activeSlot == strike.targetSlot && !it.fainted && it.hpFraction > 0.0
        } ?: return listOf(DelayedStrikeResolution(state, 1.0))
        val sourceSlot = strike.sourcePokemon.activeSlot ?: 0
        val temporaryPokemon = state.pokemon.map { pokemon ->
            when {
                pokemon.battlePokemonId == strike.sourcePokemon.battlePokemonId -> strike.sourcePokemon.copyState(
                    activeSlot = sourceSlot,
                    hpFraction = strike.sourcePokemon.hpFraction.coerceAtLeast(1e-6),
                    fainted = false,
                )
                pokemon.side == strike.sourceSide && pokemon.activeSlot == sourceSlot -> pokemon.copyState(activeSlot = null)
                else -> pokemon
            }
        }.let { pokemon ->
            if (pokemon.any { it.battlePokemonId == strike.sourcePokemon.battlePokemonId }) pokemon else {
                pokemon + strike.sourcePokemon.copyState(activeSlot = sourceSlot, hpFraction = 1.0, fainted = false)
            }
        }
        val temporaryState = state.copyState(pokemon = temporaryPokemon)
        val resolvedDetails = strike.moveDetails.forDelayedImpact()
        val action = BattleActionCandidate(
            actionId = "lookahead:delayed:${strike.moveId}:${state.turn}",
            kind = BattleActionKind.USE_MOVE,
            actorSlot = sourceSlot,
            moveSlot = 0,
            moveId = strike.moveId,
            targets = listOf(BattleTargetSlot(strike.targetSide, strike.targetSlot)),
            moveDetails = resolvedDetails,
        )
        val calculated = PublicBattleTacticalCalculator.calculate(
            actionContext(temporaryState, action, sourceContext),
            strike.sourceSide,
        )
        return PublicMoveOutcomeBranchProjector.project(calculated.candidates.single(), calculated, strike.sourceSide)
            .map { branch ->
                if (!branch.hit) {
                    DelayedStrikeResolution(state, branch.probability)
                } else {
                    DelayedStrikeResolution(
                        LocalDirectHitMechanics.apply(
                            state,
                            strike.sourcePokemon.battlePokemonId,
                            target.battlePokemonId,
                            branch.damageFraction,
                            emptyList(),
                            ignoreTargetAbility = false,
                        ).state,
                        branch.probability,
                    )
                }
            }
    }

    private fun applyForcedTargetSwitch(
        outcome: WeightedState,
        originalTarget: BattlePokemonStateView?,
        effects: List<BattleMoveEffectView>,
        sourceContext: BattleDecisionContext,
        calculationCache: LocalProjectedActionCalculationCache,
    ): List<WeightedState> {
        val switchEffect = effects.singleOrNull { it.kind == BattleMoveEffectKind.SWITCH_TARGET }
            ?: return listOf(outcome)
        val targetId = originalTarget?.battlePokemonId ?: return listOf(outcome)
        val target = outcome.state.pokemon.singleOrNull { it.battlePokemonId == targetId }
            ?: return listOf(outcome)
        val slot = target.activeSlot ?: return listOf(outcome)
        if (target.fainted || target.hpFraction <= 0.0 || canonicalId(target.knownAbilityId) in FORCED_SWITCH_IMMUNITIES) {
            return listOf(outcome)
        }
        val reserves = outcome.state.pokemon.filter {
            it.side == target.side && it.activeSlot == null && !it.fainted && it.hpFraction > 0.0
        }
        if (reserves.isEmpty()) return listOf(outcome)
        val successProbability = (switchEffect.probability ?: 1.0).coerceIn(0.0, 1.0)
        if (successProbability <= 0.0) return listOf(outcome)
        val switched = reserves.map { reserve ->
            val raw = BattleActionCandidate(
                actionId = "lookahead:forced-target:${target.battlePokemonId}:${reserve.battlePokemonId}",
                kind = BattleActionKind.SWITCH,
                actorSlot = slot,
                switchPokemonId = reserve.battlePokemonId,
                tags = setOf("public_lookahead", "forced_target_switch"),
            )
            val calculated = calculationCache.getOrCalculate(outcome.state, target.side, raw) {
                PublicBattleTacticalCalculator.calculate(
                    actionContext(outcome.state, raw, sourceContext),
                    target.side,
                )
            }.candidates.single()
            outcome.copy(
                state = LocalSwitchStateProjector.project(outcome.state, target.side, calculated),
                probability = outcome.probability * successProbability / reserves.size,
                switchedSides = outcome.switchedSides + target.side,
            )
        }
        return if (successProbability >= CERTAIN_PROBABILITY) switched else {
            switched + outcome.copy(probability = outcome.probability * (1.0 - successProbability))
        }
    }

    private fun damagingSpreadTargets(
        state: BattleStateView,
        side: BattleSide,
        actor: BattlePokemonStateView,
        action: BattleActionCandidate,
    ): List<BattlePokemonStateView> {
        if (action.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS) return emptyList()
        val pattern = action.moveDetails?.targetPattern ?: return emptyList()
        return state.pokemon.filter { pokemon ->
            pokemon.activeSlot != null && !pokemon.fainted && pokemon.hpFraction > 0.0 &&
                pokemon.battlePokemonId != actor.battlePokemonId && when (pattern) {
                    BattleMoveTargetPattern.ALL_OPPONENTS -> pokemon.side != side
                    BattleMoveTargetPattern.ALL_ADJACENT,
                    BattleMoveTargetPattern.ALL_ACTIVE,
                    -> true
                    BattleMoveTargetPattern.ALL_ALLIES -> pokemon.side == side
                    else -> false
                }
        }.sortedWith(compareBy<BattlePokemonStateView> { it.side }.thenBy { it.activeSlot })
    }

    private fun applyDamagingSpreadMove(
        state: BattleStateView,
        side: BattleSide,
        actor: BattlePokemonStateView,
        originalAction: BattleActionCandidate,
        targets: List<BattlePokemonStateView>,
        sourceContext: BattleDecisionContext,
        effects: List<BattleMoveEffectView>,
        protectedPokemonIds: Set<UUID>,
        protectionAttackDrops: Map<UUID, Int>,
        history: RecursiveActionHistory,
        maxChanceBranchesPerMove: Int,
        chanceEffectMode: ChanceEffectProjectionMode,
        calculationCache: LocalProjectedActionCalculationCache,
        shouldContinue: () -> Boolean,
    ): List<WeightedState> {
        val moveId = requireNotNull(originalAction.moveId)
        var branches = listOf(
            WeightedState(
                state = state,
                probability = 1.0,
                executedSides = setOf(side),
                executedMoveIdsByPokemon = mapOf(actor.battlePokemonId to moveId),
            ),
        )
        targets.forEachIndexed { targetIndex, initialTarget ->
            branches = branches.flatMap { branch ->
                if (!shouldContinue()) return emptyList()
                val currentActor = branch.state.pokemon.firstOrNull {
                    it.battlePokemonId == actor.battlePokemonId && !it.fainted && it.hpFraction > 0.0
                } ?: return@flatMap listOf(branch)
                val currentTarget = branch.state.pokemon.firstOrNull {
                    it.battlePokemonId == initialTarget.battlePokemonId && !it.fainted && it.hpFraction > 0.0
                } ?: return@flatMap listOf(branch)
                val targetedAction = originalAction.withSingleTarget(currentTarget)
                val calculatedContext = calculationCache.getOrCalculate(branch.state, side, targetedAction) {
                    PublicBattleTacticalCalculator.calculate(actionContext(branch.state, targetedAction, sourceContext), side)
                }
                val calculatedAction = calculatedContext.candidates.single()
                val targetIsProtected = currentTarget.battlePokemonId in protectedPokemonIds
                val breaksProtection = calculatedAction.moveDetails?.effects?.effects.orEmpty().any {
                    it.kind == BattleMoveEffectKind.BREAKS_PROTECTION
                }
                if (targetIsProtected && !breaksProtection) {
                    val contact = "contact" in calculatedAction.moveDetails?.effects?.mechanicFlags.orEmpty()
                    val attackDrop = protectionAttackDrops[currentTarget.battlePokemonId] ?: 0
                    val blockedState = if (contact && attackDrop > 0) {
                        applyStatStage(branch.state, currentActor.battlePokemonId, "attack", -attackDrop)
                    } else {
                        branch.state
                    }
                    return@flatMap listOf(branch.copy(state = blockedState))
                }
                val perTargetEffects = spreadEffectsForTarget(effects, targetIndex == 0)
                PublicMoveOutcomeBranchProjector.project(calculatedAction, calculatedContext, side).flatMap { outcome ->
                    if (!outcome.hit) {
                        listOf(branch.copy(probability = branch.probability * outcome.probability))
                    } else {
                        val applied = LocalDirectHitMechanics.apply(
                            branch.state,
                            currentActor.battlePokemonId,
                            currentTarget.battlePokemonId,
                            outcome.damageFraction,
                            perTargetEffects,
                            ignoreTargetAbility = ignoresTargetAbility(currentActor, calculatedAction),
                        )
                        LocalContactAfterHitMechanics.project(
                            applied.state,
                            currentActor.battlePokemonId,
                            currentTarget.battlePokemonId,
                            calculatedAction,
                            applied.directDamageFraction,
                        ).flatMap { contactOutcome ->
                            applyChanceEffects(
                                contactOutcome.state,
                                currentActor.battlePokemonId,
                                currentTarget.battlePokemonId,
                                perTargetEffects,
                                executedSide = side,
                                mode = chanceEffectMode,
                                shouldContinue = shouldContinue,
                            ).map { effectOutcome ->
                                effectOutcome.copy(
                                    probability = branch.probability * outcome.probability *
                                        contactOutcome.probability * effectOutcome.probability,
                                    executedSides = branch.executedSides + effectOutcome.executedSides,
                                    controlEffects = branch.controlEffects + effectOutcome.controlEffects +
                                        scriptedTrapEffects(
                                            calculatedAction,
                                            side,
                                            currentActor.battlePokemonId,
                                            currentTarget.battlePokemonId,
                                        ),
                                    executedMoveIdsByPokemon = branch.executedMoveIdsByPokemon,
                                    expectedScoreAdjustment = branch.expectedScoreAdjustment +
                                        effectOutcome.expectedScoreAdjustment +
                                        LocalImmediateTurnScorer.expectedKnockoutBonus(side, outcome.knockoutProbability),
                                )
                            }
                        }
                    }
                }
            }.let { mergeBranches(it, maxChanceBranchesPerMove) }
        }
        val recharge = effects.any {
            it.kind == BattleMoveEffectKind.RECHARGE_TURN && (it.probability ?: 1.0) >= CERTAIN_PROBABILITY
        }
        return branches.map { branch ->
            val withRecharge = if (recharge) {
                branch.copy(
                    controlEffects = branch.controlEffects + RecursiveControlEffect(
                        RecursiveControlEffectKind.RECHARGE,
                        side,
                        actor.battlePokemonId,
                        actor.battlePokemonId,
                    ),
                )
            } else {
                branch
            }
            if (effects.any { it.kind == BattleMoveEffectKind.SWITCH_USER }) {
                applyPivotSwitch(
                    withRecharge,
                    side,
                    actor.battlePokemonId,
                    sourceContext,
                    history,
                    calculationCache,
                    shouldContinue,
                )
            } else {
                withRecharge
            }
        }
    }

    private fun spreadEffectsForTarget(
        effects: List<BattleMoveEffectView>,
        firstTarget: Boolean,
    ): List<BattleMoveEffectView> = if (firstTarget) {
        effects
    } else {
        effects.filterNot { effect ->
            effect.target == BattleMoveEffectTarget.USER && effect.kind in ONCE_PER_SPREAD_USER_EFFECTS
        }
    }

    private fun BattleActionCandidate.withSingleTarget(target: BattlePokemonStateView) = BattleActionCandidate(
        actionId = "$actionId:projected-target:${target.side.name.lowercase()}:${target.activeSlot}",
        kind = kind,
        actorSlot = actorSlot,
        moveSlot = moveSlot,
        moveId = moveId,
        targets = listOf(BattleTargetSlot(target.side, requireNotNull(target.activeSlot))),
        mechanic = mechanic,
        moveDetails = moveDetails,
        facts = facts,
        tags = tags,
    )

    private fun BattleActionCandidate.withResolvedRandomTarget(target: BattlePokemonStateView) = BattleActionCandidate(
        actionId = "$actionId:random-target:${target.side.name.lowercase()}:${target.activeSlot}",
        kind = kind,
        actorSlot = actorSlot,
        moveSlot = moveSlot,
        moveId = moveId,
        targets = listOf(BattleTargetSlot(target.side, requireNotNull(target.activeSlot))),
        mechanic = mechanic,
        moveDetails = moveDetails?.copy(targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT),
        facts = facts,
        tags = tags,
    )

    private fun scriptedTrapEffects(
        action: BattleActionCandidate,
        sourceSide: BattleSide,
        actorId: UUID,
        targetId: UUID?,
    ): List<RecursiveControlEffect> {
        val moveId = canonicalId(action.moveId)
        if (moveId !in SCRIPTED_TARGET_TRAP_MOVES && moveId !in setOf("jawlock", "saltcure")) return emptyList()
        val effects = arrayListOf<RecursiveControlEffect>()
        if (moveId == "saltcure") {
            targetId?.let {
                effects += RecursiveControlEffect(RecursiveControlEffectKind.SALT_CURE, sourceSide, actorId, it)
            }
            return effects
        }
        targetId?.let {
            effects += RecursiveControlEffect(RecursiveControlEffectKind.TRAP, sourceSide, actorId, it)
        }
        if (moveId == "jawlock") {
            effects += RecursiveControlEffect(RecursiveControlEffectKind.TRAP, sourceSide, actorId, actorId)
        }
        return effects
    }

    private fun forcedMoveAction(
        state: BattleStateView,
        side: BattleSide,
        actorPokemonId: UUID,
        moveId: String,
        sourceContext: BattleDecisionContext,
    ): BattleActionCandidate? = PublicFutureActionFactory.primitiveActionsForPokemon(
        state,
        side,
        actorPokemonId,
        sourceContext.publicActionCatalog,
    ).firstOrNull { it.kind == BattleActionKind.USE_MOVE && it.moveId == moveId }

    private fun applyStatStage(
        state: BattleStateView,
        pokemonId: UUID,
        statId: String,
        amount: Int,
    ): BattleStateView = state.copyState(
        pokemon = state.pokemon.map { pokemon ->
            if (pokemon.battlePokemonId != pokemonId) return@map pokemon
            val stages = pokemon.statStages.toMutableMap()
            stages[statId] = ((stages[statId] ?: 0) + amount).coerceIn(-6, 6)
            pokemon.copyState(statStages = stages)
        },
    )

    private fun applyPivotSwitch(
        branch: WeightedState,
        side: BattleSide,
        actorPokemonId: UUID,
        sourceContext: BattleDecisionContext,
        history: RecursiveActionHistory,
        calculationCache: LocalProjectedActionCalculationCache,
        shouldContinue: () -> Boolean,
    ): WeightedState {
        val outgoing = branch.state.pokemon.singleOrNull {
            it.battlePokemonId == actorPokemonId && it.side == side && it.activeSlot != null &&
                !it.fainted && it.hpFraction > 0.0
        } ?: return branch
        if (branch.controlEffects.any {
                it.kind == RecursiveControlEffectKind.TRAP && it.targetPokemonId == outgoing.battlePokemonId
            }
        ) return branch
        val choices = PublicFutureActionFactory.primitiveActionsForPokemon(
            branch.state,
            side,
            outgoing.battlePokemonId,
            sourceContext.publicActionCatalog,
            history,
        ).filter { it.kind == BattleActionKind.SWITCH }.map { generated ->
            val action = BattleActionCandidate(
                actionId = generated.actionId + ":pivot",
                kind = BattleActionKind.SWITCH,
                actorSlot = requireNotNull(outgoing.activeSlot),
                switchPokemonId = generated.switchPokemonId,
                tags = generated.tags + "pivot_follow_up",
            )
            val projected = LocalSwitchStateProjector.project(branch.state, side, action)
            projected to LocalLookaheadStateEvaluator.evaluate(
                state = projected,
                source = sourceContext,
                calculationCache = calculationCache,
                shouldContinue = shouldContinue,
            )
        }
        if (choices.isEmpty()) return branch
        val selected = if (side == BattleSide.ALLY) {
            choices.maxBy { it.second }
        } else {
            choices.minBy { it.second }
        }
        return branch.copy(state = selected.first, switchedSides = branch.switchedSides + side)
    }

    private fun ignoresTargetAbility(actor: BattlePokemonStateView, action: BattleActionCandidate): Boolean =
        canonicalId(actor.knownAbilityId) in ABILITY_IGNORING_ABILITIES ||
            action.moveDetails?.effects?.effects?.any { it.kind == BattleMoveEffectKind.IGNORE_ABILITY } == true

    private fun applyChanceEffects(
        initial: BattleStateView,
        actorId: UUID,
        targetId: UUID?,
        effects: List<BattleMoveEffectView>,
        executedSide: BattleSide,
        mode: ChanceEffectProjectionMode,
        shouldContinue: () -> Boolean,
    ): List<WeightedState> {
        val projectable = effects.filter { effect ->
            effect.kind in PROJECTED_EFFECT_KINDS &&
                effect.target in PROJECTED_EFFECT_TARGETS
        }
        var branches = listOf(WeightedState(initial, 1.0, setOf(executedSide)))
        projectable.forEach { effect ->
            if (!shouldContinue()) return emptyList()
            val probability = (effect.probability ?: 1.0).coerceIn(0.0, 1.0)
            if (probability <= 0.0) return@forEach
            branches = branches.flatMap { branch ->
                val controlEffect = recursiveControlEffect(effect, actorId, targetId, executedSide)
                val applied = applyEffect(branch.state, actorId, targetId, effect, executedSide)
                when {
                    probability >= CERTAIN_PROBABILITY -> listOf(
                        branch.copy(
                            state = applied,
                            controlEffects = branch.controlEffects + listOfNotNull(controlEffect),
                        ),
                    )
                    mode == ChanceEffectProjectionMode.EXPECTED_SCORE -> listOf(
                        branch.copy(
                            expectedScoreAdjustment = branch.expectedScoreAdjustment +
                                LocalImmediateTurnScorer.expectedEffectScore(branch.state, applied, probability),
                        ),
                    )
                    else -> listOf(
                        branch.copy(probability = branch.probability * (1.0 - probability)),
                        branch.copy(
                            state = applied,
                            probability = branch.probability * probability,
                            controlEffects = branch.controlEffects + listOfNotNull(controlEffect),
                        ),
                    )
                }
            }
        }
        return branches
    }

    private fun recursiveControlEffect(
        effect: BattleMoveEffectView,
        actorId: UUID,
        targetId: UUID?,
        sourceSide: BattleSide,
    ): RecursiveControlEffect? {
        if (effect.kind != BattleMoveEffectKind.VOLATILE_STATUS) return null
        val targetPokemonId = when (effect.target) {
            BattleMoveEffectTarget.USER -> actorId
            BattleMoveEffectTarget.SELECTED_TARGET -> targetId
            else -> null
        } ?: return null
        val kind = when (canonicalId(effect.valueId)) {
            "taunt" -> RecursiveControlEffectKind.TAUNT
            "encore" -> RecursiveControlEffectKind.ENCORE
            "trapped", "partiallytrapped", "meanlook", "octolock", "jawlock" -> RecursiveControlEffectKind.TRAP
            else -> return null
        }
        return RecursiveControlEffect(kind, sourceSide, actorId, targetPokemonId)
    }

    private fun applyEffect(
        state: BattleStateView,
        actorId: UUID,
        targetId: UUID?,
        effect: BattleMoveEffectView,
        executedSide: BattleSide,
    ): BattleStateView {
        if (effect.kind in FIELD_EFFECT_KINDS) {
            return LocalFieldEffectProjector.apply(state, executedSide, effect, actorId)
        }
        val affectedId = when (effect.target) {
            BattleMoveEffectTarget.USER -> actorId
            BattleMoveEffectTarget.SELECTED_TARGET -> targetId
            else -> null
        } ?: return state
        val affected = state.pokemon.firstOrNull { it.battlePokemonId == affectedId } ?: return state
        if (affected.fainted || affected.hpFraction <= 0.0) return state
        val updated = when (effect.kind) {
            BattleMoveEffectKind.STATUS -> {
                if (effect.valueId == null || LocalPublicStatusImmunity.blocked(state, affected, effect.valueId)) return state
                affected.copyState(statusId = effect.valueId)
            }
            BattleMoveEffectKind.STAT_STAGE -> {
                val stages = affected.statStages.toMutableMap()
                effect.statStages.forEach { (stat, amount) ->
                    stages[stat] = ((stages[stat] ?: 0) + amount).coerceIn(-6, 6)
                }
                if (stages == affected.statStages) return state
                affected.copyState(statStages = stages)
            }
            else -> return state
        }
        return state.copyState(
            pokemon = state.pokemon.map { pokemon ->
                if (pokemon.battlePokemonId == affectedId) updated else pokemon
            },
        )
    }

    private fun mergeBranches(branches: List<WeightedState>, maxBranches: Int): List<WeightedState> {
        val merged = branches.groupBy {
            listOf(
                fingerprint(it.state),
                it.executedSides,
                it.protectedPokemonIds,
                it.controlEffects,
                it.switchedSides,
                it.protectionAttackDrops,
                it.tauntedPokemonIds,
                it.forcedMoveIdsByPokemon,
                it.lastMoveByPokemon,
                it.executedMoveIdsByPokemon,
                it.protectionResultsByPokemon,
            )
        }.values.map { identical ->
            val probability = identical.sumOf(WeightedState::probability)
            WeightedState(
                identical.first().state,
                probability,
                identical.first().executedSides,
                identical.first().protectedPokemonIds,
                identical.first().controlEffects,
                identical.first().switchedSides,
                identical.first().protectionAttackDrops,
                identical.first().tauntedPokemonIds,
                identical.first().forcedMoveIdsByPokemon,
                identical.first().lastMoveByPokemon,
                identical.first().executedMoveIdsByPokemon,
                weightedExpectedScoreAdjustment(
                    identical.map { it.probability to it.expectedScoreAdjustment },
                    probability,
                ),
                identical.first().protectionResultsByPokemon,
            )
        }.sortedByDescending(WeightedState::probability)
        val total = merged.sumOf(WeightedState::probability)
        if (total <= 0.0) return emptyList()
        val normalized = merged.map { it.copy(probability = it.probability / total) }
        if (normalized.size <= maxBranches) return normalized

        // The remaining discrete branches are action-changing events such as misses, full
        // paralysis, and public contact reactions. Midpoint systematic sampling preserves their
        // probability mass when a difficulty budget still requires a cap.
        val selectedCounts = IntArray(normalized.size)
        var branchIndex = 0
        var cumulative = normalized.first().probability
        repeat(maxBranches) { sampleIndex ->
            val target = (sampleIndex + 0.5) / maxBranches
            while (branchIndex < normalized.lastIndex && target > cumulative) {
                branchIndex++
                cumulative += normalized[branchIndex].probability
            }
            selectedCounts[branchIndex]++
        }
        return selectedCounts.indices.mapNotNull { index ->
            val count = selectedCounts[index]
            if (count == 0) null else normalized[index].copy(probability = count.toDouble() / maxBranches)
        }
    }

    private fun possibleOrders(
        observedState: BattleStateView,
        state: BattleStateView,
        actions: List<TurnPrimitiveAction>,
    ): List<WeightedOrder> {
        val constraints = actions.indices.flatMap { firstIndex ->
            (firstIndex + 1 until actions.size).mapNotNull { secondIndex ->
                definiteOrder(observedState, state, actions[firstIndex], actions[secondIndex])
            }
        }
        val allowed = permutations(actions).filter { order ->
            constraints.all { (before, after) -> order.indexOf(before) < order.indexOf(after) }
        }.ifEmpty { listOf(actions) }
        if (allowed.size == 1) return listOf(WeightedOrder(allowed.single(), 1.0))
        if (allowed.size == 2 && actions.size == 2) {
            val firstLeads = allowed.first().first() == actions.first()
            val leadProbability = actsFirstProbability(state, actions[0], actions[1])
            if (leadProbability != null) {
                val head = if (firstLeads) leadProbability else 1.0 - leadProbability
                return listOf(
                    WeightedOrder(allowed[0], head),
                    WeightedOrder(allowed[1], 1.0 - head),
                )
            }
        }
        val uniform = 1.0 / allowed.size
        return allowed.map { WeightedOrder(it, uniform) }
    }

    /**
     * How likely the first action is to resolve first, given only what is public about both Speeds.
     *
     * Reached only when the ranges overlap, so neither side is provably faster. The old reading called
     * that a coin flip, which made every degree of uncertainty identical: a Speed drop that took the
     * opponent from certainly-faster to probably-slower scored exactly the same as one that barely
     * moved, and the search had no reason to prefer the bigger drop, the paralysis, or the Tailwind.
     *
     * Both Speeds are treated as uniform over their public range, which is the honest reading of a
     * range that refuses IVs, EVs and nature: no value in it is claimed to be likelier than another.
     * Ties are split evenly, matching the coin flip the real engine performs.
     *
     * Only the two-action case is priced. Four actions in a double battle have up to twenty-four
     * orders whose probabilities do not factor into pairwise comparisons, and nothing measures doubles
     * yet; those stay uniform rather than carrying a number this cannot justify.
     */
    private fun actsFirstProbability(
        state: BattleStateView,
        first: TurnPrimitiveAction,
        second: TurnPrimitiveAction,
    ): Double? {
        val firstSpeed = actionSpeedRange(state, first) ?: return null
        val secondSpeed = actionSpeedRange(state, second) ?: return null
        val trickRoom = state.field.roomEffects.any { effect ->
            val remainingTurns = effect.remainingTurns
            canonicalId(effect.effectId) == "trickroom" && (remainingTurns == null || remainingTurns > 0)
        }
        val probability = uniformGreaterProbability(firstSpeed, secondSpeed)
        return if (trickRoom) 1.0 - probability else probability
    }

    /** P(a > b) plus half of P(a == b), for two independent uniform integer ranges. */
    private fun uniformGreaterProbability(a: Pair<Int, Int>, b: Pair<Int, Int>): Double {
        val aValues = (a.second - a.first + 1).toLong()
        val bValues = (b.second - b.first + 1).toLong()
        if (aValues <= 0L || bValues <= 0L) return 0.5
        var greater = 0L
        var equal = 0L
        // Counted over b, so the cost is the width of one range rather than the product of both.
        for (bValue in b.first..b.second) {
            greater += (a.second.toLong() - maxOf(a.first, bValue + 1) + 1).coerceAtLeast(0L)
            if (bValue in a.first..a.second) equal++
        }
        val total = (aValues * bValues).toDouble()
        return (greater + equal / 2.0) / total
    }

    private fun definiteOrder(
        observedState: BattleStateView,
        state: BattleStateView,
        first: TurnPrimitiveAction,
        second: TurnPrimitiveAction,
    ): Pair<TurnPrimitiveAction, TurnPrimitiveAction>? {
        val firstPriority = effectivePriority(state, first.side, first.action)
        val secondPriority = effectivePriority(state, second.side, second.action)
        if (firstPriority != secondPriority) {
            return if (firstPriority > secondPriority) first to second else second to first
        }
        val trickRoom = state.field.roomEffects.any { effect ->
            val remainingTurns = effect.remainingTurns
            canonicalId(effect.effectId) == "trickroom" && (remainingTurns == null || remainingTurns > 0)
        }
        val firstSpeed = actionSpeedRange(state, first)
        val secondSpeed = actionSpeedRange(state, second)
        val speedOrder = if (firstSpeed == null || secondSpeed == null) {
            null
        } else if (trickRoom) {
            when {
                firstSpeed.second < secondSpeed.first -> first to second
                secondSpeed.second < firstSpeed.first -> second to first
                else -> null
            }
        } else {
            when {
                firstSpeed.first > secondSpeed.second -> first to second
                secondSpeed.first > firstSpeed.second -> second to first
                else -> null
            }
        }
        if (speedOrder != null || trickRoom) return speedOrder
        val firstActorId = first.actorPokemonId ?: return null
        val secondActorId = second.actorPokemonId ?: return null
        return when (LocalObservedActionOrder.before(observedState, state, firstActorId, secondActorId)) {
            true -> first to second
            false -> second to first
            null -> null
        }
    }

    private fun permutations(actions: List<TurnPrimitiveAction>): List<List<TurnPrimitiveAction>> {
        if (actions.size <= 1) return listOf(actions)
        return actions.flatMapIndexed { index, action ->
            val remaining = actions.toMutableList().also { it.removeAt(index) }
            permutations(remaining).map { listOf(action) + it }
        }
    }

    private fun actionSpeedRange(
        state: BattleStateView,
        ordered: TurnPrimitiveAction,
    ): Pair<Int, Int>? {
        val actor = state.pokemon.firstOrNull {
            it.side == ordered.side && it.activeSlot == ordered.action.actorSlot && !it.fainted && it.hpFraction > 0.0
        } ?: return null
        val speed = actor.combatStats?.speed ?: return null
        val stage = actor.statStages.entries.firstOrNull {
            canonicalId(it.key) in SPEED_ALIASES
        }?.value?.coerceIn(-6, 6) ?: 0
        val paralysis = if (canonicalId(actor.statusId) in PARALYSIS_IDS) 0.5 else 1.0
        val tailwind = if (state.field.sideConditions.getValue(ordered.side).any {
                val remainingTurns = it.remainingTurns
                canonicalId(it.effectId) == "tailwind" && (remainingTurns == null || remainingTurns > 0)
            }
        ) 2.0 else 1.0
        val multiplier = paralysis * tailwind
        return (applySpeedStage(speed.minimum, stage) * multiplier).toInt().coerceAtLeast(1) to
            (applySpeedStage(speed.maximum, stage) * multiplier).toInt().coerceAtLeast(1)
    }

    private fun applySpeedStage(value: Int, stage: Int): Int = if (stage >= 0) {
        value * (2 + stage) / 2
    } else {
        value * 2 / (2 - stage)
    }.coerceAtLeast(1)

    private fun effectivePriority(
        state: BattleStateView,
        side: BattleSide,
        action: BattleActionCandidate,
    ): Int {
        val base = action.moveDetails?.priority ?: 0
        val actor = state.pokemon.singleOrNull {
            it.side == side && it.activeSlot == action.actorSlot && !it.fainted && it.hpFraction > 0.0
        }
        val prankster = canonicalId(actor?.knownAbilityId) == "prankster" &&
            action.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS
        return base + if (prankster) 1 else 0
    }

    private fun canonicalId(value: String?): String? = value
        ?.substringAfter(':')
        ?.lowercase()
        ?.filter(Char::isLetterOrDigit)

    private val SPEED_ALIASES = setOf("speed", "spe")

    private fun actionContext(
        state: BattleStateView,
        action: BattleActionCandidate,
        source: BattleDecisionContext,
    ): BattleDecisionContext {
        return BattleDecisionContext(
            requestId = source.requestId,
            state = state,
            candidates = listOf(action),
            deadlineEpochMillis = source.deadlineEpochMillis,
            memory = BattleTacticalMemoryView.empty(),
            publicActionCatalog = source.publicActionCatalog,
        )
    }

    private fun incrementTurn(state: BattleStateView): BattleStateView = state.copyState(turn = state.turn + 1)

    private fun fingerprint(state: BattleStateView): String = buildString {
        state.pokemon.sortedBy { it.battlePokemonId }.forEach {
            append(it.battlePokemonId).append(':').append(it.side).append(':').append(it.activeSlot).append(':')
            append((it.hpFraction * 10_000).roundToInt()).append(':').append(it.statusId).append(':')
            append(it.formId).append(':').append(it.knownHeldItemId).append(':')
            append(it.statStages).append('|')
        }
        appendTimedEffect("weather", state.field.weather)
        appendTimedEffect("terrain", state.field.terrain)
        state.field.roomEffects.sortedBy { it.effectId }.forEach { appendTimedEffect("room", it) }
        state.field.globalEffects.sortedBy { it.effectId }.forEach { appendTimedEffect("global", it) }
        BattleSide.entries.forEach { side ->
            state.field.sideConditions.getValue(side).sortedBy { it.effectId }.forEach { effect ->
                appendTimedEffect(side.name, effect)
            }
        }
    }

    private fun StringBuilder.appendTimedEffect(scope: String, effect: BattleTimedEffectView?) {
        if (effect == null) return
        append(scope).append(':').append(effect.effectId).append(':')
        append(effect.remainingTurns).append(':')
        append(effect.remainingTurnsRange?.minimum).append('-').append(effect.remainingTurnsRange?.maximum).append(':')
        append(effect.stacks).append('|')
    }

    private data class WeightedState(
        val state: BattleStateView,
        val probability: Double,
        val executedSides: Set<BattleSide> = emptySet(),
        val protectedPokemonIds: Set<UUID> = emptySet(),
        val controlEffects: List<RecursiveControlEffect> = emptyList(),
        val switchedSides: Set<BattleSide> = emptySet(),
        val protectionAttackDrops: Map<UUID, Int> = emptyMap(),
        val tauntedPokemonIds: Set<UUID> = emptySet(),
        val forcedMoveIdsByPokemon: Map<UUID, String> = emptyMap(),
        val lastMoveByPokemon: Map<UUID, String> = emptyMap(),
        val executedMoveIdsByPokemon: Map<UUID, String> = emptyMap(),
        val expectedScoreAdjustment: Double = 0.0,
        val protectionResultsByPokemon: Map<UUID, Boolean> = emptyMap(),
    )

    private fun weightedExpectedScoreAdjustment(
        values: List<Pair<Double, Double>>,
        totalProbability: Double,
    ): Double = if (totalProbability > 0.0) {
        values.sumOf { (probability, adjustment) -> probability * adjustment } / totalProbability
    } else {
        0.0
    }

    private const val CERTAIN_PROBABILITY = 0.999_999
    private const val MAX_CHANCE_BRANCHES_PER_MOVE = 64
    private val FORCED_SWITCH_IMMUNITIES = setOf("suctioncups", "guarddog")
    private const val FULL_PARALYSIS_PROBABILITY = 0.25
    private const val FREEZE_THAW_PROBABILITY = 0.20
    private const val FUTURE_MOVE_DELAY_TURNS = 2
    private val PARALYSIS_IDS = setOf("par", "paralysis", "paralyzed", "paralysed")
    private val SLEEP_IDS = setOf("slp", "sleep", "asleep")
    private val FREEZE_IDS = setOf("frz", "freeze", "frozen")
    private val PROJECTED_EFFECT_KINDS = setOf(
        BattleMoveEffectKind.STATUS,
        BattleMoveEffectKind.STAT_STAGE,
        BattleMoveEffectKind.SIDE_CONDITION,
        BattleMoveEffectKind.FIELD_CONDITION,
        BattleMoveEffectKind.WEATHER,
        BattleMoveEffectKind.TERRAIN,
        BattleMoveEffectKind.VOLATILE_STATUS,
    )
    private val PROJECTED_EFFECT_TARGETS = setOf(
        BattleMoveEffectTarget.USER,
        BattleMoveEffectTarget.SELECTED_TARGET,
        BattleMoveEffectTarget.USER_SIDE,
        BattleMoveEffectTarget.TARGET_SIDE,
        BattleMoveEffectTarget.FIELD,
    )
    private val FIELD_EFFECT_KINDS = setOf(
        BattleMoveEffectKind.SIDE_CONDITION,
        BattleMoveEffectKind.FIELD_CONDITION,
        BattleMoveEffectKind.WEATHER,
        BattleMoveEffectKind.TERRAIN,
    )
    private val ONCE_PER_SPREAD_USER_EFFECTS = setOf(
        BattleMoveEffectKind.HEAL_FRACTION,
        BattleMoveEffectKind.MAX_HP_RECOIL,
        BattleMoveEffectKind.STRUGGLE_RECOIL,
        BattleMoveEffectKind.SELF_DESTRUCT,
        BattleMoveEffectKind.STAT_STAGE,
        BattleMoveEffectKind.STATUS,
        BattleMoveEffectKind.VOLATILE_STATUS,
    )
    private val SCRIPTED_TARGET_TRAP_MOVES = setOf(
        "anchorshot",
        "block",
        "meanlook",
        "octolock",
        "spiderweb",
        "spiritshackle",
        "thousandwaves",
    )
    private val ABILITY_IGNORING_ABILITIES = setOf("moldbreaker", "teravolt", "turboblaze")
}

private fun BattleActionCandidate.withoutFacts() = BattleActionCandidate(
    actionId = actionId,
    kind = kind,
    actorSlot = actorSlot,
    moveSlot = moveSlot,
    moveId = moveId,
    targets = targets,
    switchPokemonId = switchPokemonId,
    componentActionIds = componentActionIds,
    componentActions = componentActions,
    mechanic = mechanic,
    moveDetails = moveDetails,
    facts = null,
    tags = tags,
)

private fun BattleMoveCandidateView.forDelayedImpact(): BattleMoveCandidateView {
    val original = effects ?: return this
    val filtered = original.effects.filterNot {
        it.kind == BattleMoveEffectKind.IGNORE_TYPE_IMMUNITY ||
            it.kind == BattleMoveEffectKind.SLOT_CONDITION &&
            it.valueId?.substringAfter(':')?.lowercase()?.filter(Char::isLetterOrDigit) == "futuremove"
    }
    return BattleMoveCandidateView(
        typeId = typeId,
        damageCategory = damageCategory,
        power = power,
        accuracy = accuracy,
        priority = priority,
        currentPp = currentPp,
        targetPattern = targetPattern,
        effects = BattleMoveEffectsView(
            coverage = original.coverage,
            effects = filtered,
            scriptedBehavior = original.scriptedBehavior,
            requirements = original.requirements,
            mechanicFlags = original.mechanicFlags,
        ),
    )
}
