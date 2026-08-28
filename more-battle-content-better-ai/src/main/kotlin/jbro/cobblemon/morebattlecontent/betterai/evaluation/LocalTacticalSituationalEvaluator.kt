package jbro.cobblemon.morebattlecontent.betterai.evaluation

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleKnockoutAssessment
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectTarget
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveOutcomeKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveRequirementKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveRequirementView
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventKind
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalSideConditionRules
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalStallingProtectionRules

/**
 * Local-only tactical interpretation of already-public state and mechanical candidate facts.
 * This must not become a second calculator or an input to the Router decision path.
 */
internal object LocalTacticalSituationalEvaluator {
    fun statusPressure(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        accuracy: Double,
    ): Double = LocalNonDamagingMoveEvaluator.pressure(candidate, context, accuracy)

    /**
     * Score for removing the target from play, on top of the HP damage already priced in.
     *
     * A knockout is worth `LIVING_POKEMON_VALUE` board points beyond the health it takes, and the
     * health is already counted as damage pressure. So the same constant covers both assessments,
     * scaled by how likely the knockout actually is - a guaranteed knockout is the certain case of the
     * possible one, not a different kind of event with its own price.
     *
     * The legacy constants (`50` guaranteed, `35` possible) were unrelated to the board value and were
     * additionally stacked on a flat `250` applied at ranking time.
     */
    fun knockoutAdjustment(
        candidate: BattleActionCandidate,
        accuracy: Double,
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
    ): Double {
        val facts = candidate.facts
        if (tuning.legacyRawPowerFallback) {
            return when (facts?.standardKnockoutAssessment) {
                BattleKnockoutAssessment.GUARANTEED -> GUARANTEED_KNOCKOUT_BONUS * accuracy
                BattleKnockoutAssessment.POSSIBLE -> {
                    val probability = facts.standardDamageRollKoProbabilityRange
                        ?.let { (it.minimum + it.maximum) / 2.0 }
                        ?: 0.0
                    POSSIBLE_KNOCKOUT_BONUS * probability * accuracy
                }
                else -> 0.0
            }
        }
        val probability = when (facts?.standardKnockoutAssessment) {
            BattleKnockoutAssessment.GUARANTEED -> 1.0
            BattleKnockoutAssessment.POSSIBLE -> facts.standardDamageRollKoProbabilityRange
                ?.let { (it.minimum + it.maximum) / 2.0 }
                ?: 0.0
            else -> return 0.0
        }
        // A knockout landed before the reply is worth more than the same knockout landed after it: one
        // ends the exchange, the other only wins the trade. `firstStrikeWeight` says how much of the
        // knockout's value is conditional on getting there first, and it ships at zero until the
        // measurement says otherwise.
        val order = facts?.actsFirstProbability
        val initiative = if (order == null) 1.0 else 1.0 - tuning.firstStrikeWeight * (1.0 - order)
        return tuning.knockoutMaterialScore * probability * accuracy * initiative
    }

    /**
     * Damage and knockout value for the slots a spread move hits *besides* its primary target.
     *
     * The primary target is already priced by the ordinary pressure and knockout terms, and the
     * outcome evaluator cancels its own copy of that term against them. The extra slots appear in
     * neither, so without this a doubles spread move was valued as if it hit one Pokemon - the AI
     * could not see the one thing that makes a spread move worth clicking.
     *
     * Only the extras are summed here, so the cancellation between the scorer and the outcome
     * evaluator over the primary target is left exactly as it was.
     */
    fun spreadAdjustment(
        candidate: BattleActionCandidate,
        accuracy: Double,
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
    ): Double {
        val extras = candidate.facts?.spreadTargets.orEmpty().drop(1)
        if (extras.isEmpty()) return 0.0
        return extras.sumOf { extra ->
            val damage = extra.standardDamageFractionRange
                ?.let { (it.minimum + it.maximum) * 50.0 * accuracy }
                ?: 0.0
            val knockoutProbability = when (extra.standardKnockoutAssessment) {
                BattleKnockoutAssessment.GUARANTEED -> 1.0
                BattleKnockoutAssessment.POSSIBLE -> extra.standardDamageRollKoProbabilityRange
                    ?.let { (it.minimum + it.maximum) / 2.0 }
                    ?: 0.0
                else -> 0.0
            }
            damage + tuning.knockoutMaterialScore * knockoutProbability * accuracy
        }
    }

    fun activePersistentEffectRefreshPenalty(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
    ): Double {
        if (candidate.moveDetails?.damageCategory != BattleMoveDamageCategory.STATUS) return 0.0
        val declaredEffects = candidate.moveDetails?.effects?.effects.orEmpty()
        val duplicateCount = declaredEffects.count { effect ->
            val effectId = effect.valueId ?: return@count false
            when (effect.kind) {
                BattleMoveEffectKind.SIDE_CONDITION -> {
                    val side = when (effect.target) {
                        BattleMoveEffectTarget.USER_SIDE -> BattleSide.ALLY
                        BattleMoveEffectTarget.TARGET_SIDE -> BattleSide.OPPONENT
                        else -> return@count false
                    }
                    context.state.field.sideConditions.getValue(side).any { active ->
                        if (canonicalEffectId(active.effectId) != canonicalEffectId(effectId)) {
                            false
                        } else if (active.remainingTurns == EXPIRING_EFFECT_TURNS) {
                            false
                        } else {
                            val maximumStacks = LocalSideConditionRules.maximumStacks(
                                effectId,
                                effect.amountRange?.maximum,
                            )
                            val activeStacks = active.stacks
                            activeStacks == null || maximumStacks != null && activeStacks >= maximumStacks
                        }
                    }
                }
                BattleMoveEffectKind.WEATHER -> activeEffectBlocksRefresh(context.state.field.weather, effectId)
                BattleMoveEffectKind.TERRAIN -> activeEffectBlocksRefresh(context.state.field.terrain, effectId)
                BattleMoveEffectKind.FIELD_CONDITION ->
                    (context.state.field.roomEffects + context.state.field.globalEffects).any {
                        activeEffectBlocksRefresh(it, effectId)
                    }
                else -> false
            }
        }
        return duplicateCount * ACTIVE_PERSISTENT_EFFECT_REFRESH_PENALTY
    }

    fun saturatedStatStagePenalty(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
    ): Double {
        val stageEffects = candidate.moveDetails?.effects?.effects.orEmpty().filter {
            it.kind == BattleMoveEffectKind.STAT_STAGE && it.statStages.isNotEmpty()
        }
        if (stageEffects.isEmpty()) return 0.0
        val allSaturated = stageEffects.all { effect ->
            val target = when (effect.target) {
                BattleMoveEffectTarget.USER -> actor(candidate, context)
                BattleMoveEffectTarget.SELECTED_TARGET -> selectedOpponent(candidate, context)
                else -> null
            } ?: return@all false
            effect.statStages.all { (statId, change) ->
                val current = target.statStages[statId] ?: 0
                current + change !in MINIMUM_STAT_STAGE..MAXIMUM_STAT_STAGE
            }
        }
        return if (allSaturated) SATURATED_STAT_STAGE_PENALTY else 0.0
    }

    fun alreadyBoostedSelfSetup(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
    ): Boolean {
        val details = candidate.moveDetails ?: return false
        if (details.power > 0.0) return false
        val raisesUserStage = details.effects?.effects.orEmpty().any { effect ->
            effect.kind == BattleMoveEffectKind.STAT_STAGE &&
                effect.target == BattleMoveEffectTarget.USER &&
                effect.statStages.values.any { it > 0 }
        }
        if (!raisesUserStage) return false
        return actor(candidate, context)?.statStages?.values?.any { it > 0 } == true
    }

    fun overcommittedSelfSetup(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
    ): Boolean {
        if (!alreadyBoostedSelfSetup(candidate, context)) return false
        val active = actor(candidate, context) ?: return false
        val accumulatedPositiveStages = active.statStages.values.sumOf { it.coerceAtLeast(0) }
        return active.hpFraction <= CRITICAL_SETUP_HP_FRACTION ||
            accumulatedPositiveStages >= MAXIMUM_SETUP_STAGE_BUDGET
    }

    fun unmetPublicRequirementPenalty(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
    ): Double = candidate.moveDetails?.effects?.requirements.orEmpty().count {
        requirementState(it, candidate, context) == RequirementState.UNSATISFIED
    } * UNMET_PUBLIC_REQUIREMENT_PENALTY

    fun hasUnmetPublicRequirement(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        actingSide: BattleSide,
    ): Boolean = candidate.moveDetails?.effects?.requirements.orEmpty().any {
        requirementState(it, candidate, context, actingSide) == RequirementState.UNSATISFIED
    }

    fun recentPublicFailurePenalty(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
    ): Double {
        val actorId = actor(candidate, context)?.battlePokemonId ?: return 0.0
        val moveId = candidate.moveId?.let(::canonicalEffectId) ?: return 0.0
        val failure = context.state.observedEvents.lastOrNull { event ->
            event.kind == BattleObservedEventKind.MOVE_OUTCOME &&
                event.actorPokemonId == actorId &&
                event.moveOutcome?.kind in PUBLIC_FAILURE_OUTCOMES &&
                event.moveOutcome?.moveId?.let(::canonicalEffectId) == moveId
        } ?: return 0.0
        if (failure.turn < context.state.turn - RECENT_FAILURE_TURNS) return 0.0
        val conditionMayHaveChanged = context.state.observedEvents.any { event ->
            event.sequence > failure.sequence && event.kind in REQUIREMENT_CHANGE_EVENTS
        }
        return if (conditionMayHaveChanged) 0.0 else RECENT_PUBLIC_FAILURE_PENALTY
    }

    fun repeatedProtectionPenalty(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
    ): Double {
        val protects = candidate.moveDetails?.effects?.effects.orEmpty().any {
            it.kind == BattleMoveEffectKind.PROTECT_USER
        }
        val chain = LocalStallingProtectionRules.consecutiveSuccessfulUses(
            context.state,
            BattleSide.ALLY,
            candidate.actorSlot,
        )
        if (!protects || chain < 1) return 0.0
        return REPEATED_PROTECTION_HABIT_PENALTY +
            context.memory.nonProgressControlStreak.coerceAtMost(MAX_PROTECTION_NO_PROGRESS_STREAK) *
            PROTECTION_NO_PROGRESS_PENALTY
    }

    fun consecutiveUseForbiddenPenalty(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
    ): Double {
        val cannotUseTwice = candidate.moveDetails?.effects?.mechanicFlags?.any {
            canonicalEffectId(it) == "cantusetwice"
        } == true
        if (!cannotUseTwice || context.memory.sameMoveRepeatCount < 1) return 0.0
        return if (sameEffect(candidate.moveId, context.memory.lastMoveId)) {
            CONSECUTIVE_USE_FORBIDDEN_PENALTY
        } else {
            0.0
        }
    }

    fun pendingDamagingMoveRiskPenalty(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
    ): Double {
        val requiresPendingDamage = candidate.moveDetails?.effects?.requirements.orEmpty().any {
            it.kind == BattleMoveRequirementKind.TARGET_PENDING_DAMAGING_MOVE
        }
        if (!requiresPendingDamage) return 0.0
        val opponent = selectedOpponent(candidate, context) ?: context.state.pokemon.singleOrNull {
            it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        } ?: return CONDITIONAL_MOVE_UNKNOWN_RISK_PENALTY
        val knownMoves = context.publicActionCatalog.forPokemon(opponent.battlePokemonId)
        val complete = context.publicActionCatalog.isMoveSetComplete(opponent.battlePokemonId)
        val damagingMoves = knownMoves.count { it.details.damageCategory != BattleMoveDamageCategory.STATUS }
        val hasNonDamagingMove = knownMoves.any { it.details.damageCategory == BattleMoveDamageCategory.STATUS }
        val hasSwitch = context.state.pokemon.any {
            it.side == BattleSide.OPPONENT && it.activeSlot == null && !it.fainted && it.hpFraction > 0.0
        }
        if (complete && damagingMoves == 0) return CONDITIONAL_MOVE_CERTAIN_FAILURE_PENALTY
        var penalty = if (hasNonDamagingMove || hasSwitch) CONDITIONAL_MOVE_ALTERNATIVE_RISK_PENALTY else 0.0
        if (!complete) penalty += CONDITIONAL_MOVE_UNKNOWN_RISK_PENALTY
        return penalty
    }

    fun forcedTempoPenalty(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
    ): Double {
        val effects = candidate.moveDetails?.effects?.effects.orEmpty()
        val actor = actor(candidate, context)
        val chargeSkippedByItem = actor?.knownHeldItemId?.let(::canonicalEffectId) == "powerherb"
        val chargeSkippedByWeather = context.state.field.weather?.effectId?.let { weatherId ->
            effects.any {
                it.kind == BattleMoveEffectKind.CHARGE_SKIP_WEATHER && sameEffect(it.valueId, weatherId)
            }
        } == true
        val chargePenalty = if (
            effects.any { it.kind == BattleMoveEffectKind.CHARGE_TURN } &&
            !chargeSkippedByItem && !chargeSkippedByWeather
        ) {
            CHARGE_TURN_PENALTY
        } else {
            0.0
        }
        val rechargePenalty = if (effects.any { it.kind == BattleMoveEffectKind.RECHARGE_TURN }) {
            RECHARGE_TURN_PENALTY
        } else {
            0.0
        }
        return chargePenalty + rechargePenalty
    }

    fun expiredFirstActiveTurnPenalty(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
    ): Double {
        val firstTurnOnly = candidate.moveDetails?.effects?.effects?.any {
            it.kind == BattleMoveEffectKind.FIRST_ACTIVE_TURN_ONLY
        } == true
        if (!firstTurnOnly) return 0.0
        val actorSlot = candidate.actorSlot ?: return 0.0
        val actorId = context.state.pokemon.firstOrNull {
            it.side == BattleSide.ALLY && it.activeSlot == actorSlot && !it.fainted
        }?.battlePokemonId ?: return 0.0
        val lastEntrySequence = context.state.observedEvents
            .asSequence()
            .filter {
                it.kind == BattleObservedEventKind.SWITCHED && it.actorPokemonId == actorId
            }
            .maxOfOrNull { it.sequence }
            ?: -1L
        val alreadyMovedSinceEntry = context.state.observedEvents.any {
            it.kind == BattleObservedEventKind.MOVE_USED &&
                it.actorPokemonId == actorId &&
                it.sequence > lastEntrySequence
        }
        return if (alreadyMovedSinceEntry) EXPIRED_FIRST_ACTIVE_TURN_PENALTY else 0.0
    }

    fun postEntryHp(candidate: BattleActionCandidate, currentHp: Double): Double =
        (currentHp - (candidate.facts?.switchEntryHpLossFraction ?: 0.0)).coerceAtLeast(0.0)

    fun entryKnockoutPenalty(candidate: BattleActionCandidate, currentHp: Double): Double =
        if (currentHp > 0.0 && postEntryHp(candidate, currentHp) <= 0.0) ENTRY_KNOCKOUT_PENALTY else 0.0

    fun compositeCoordinationAdjustment(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
    ): Double {
        if (context.state.format != BattleFormat.DOUBLE) return 0.0
        val attacksByTarget = candidate.componentActions
            .asSequence()
            .filter { it.moveDetails?.damageCategory != BattleMoveDamageCategory.STATUS }
            .filter { it.facts?.standardDamageFractionRange != null }
            .mapNotNull { action ->
                val slot = action.targets.singleOrNull { it.side == BattleSide.OPPONENT }?.slot
                    ?: return@mapNotNull null
                slot to action
            }
            .groupBy({ it.first }, { it.second })

        return attacksByTarget.entries.sumOf { (targetSlot, attacks) ->
            if (attacks.size < 2) return@sumOf 0.0
            val target = context.state.pokemon.firstOrNull {
                it.side == BattleSide.OPPONENT && it.activeSlot == targetSlot && !it.fainted
            } ?: return@sumOf 0.0
            val alreadySecured = attacks.any { action ->
                val facts = action.facts
                facts?.standardKnockoutAssessment == BattleKnockoutAssessment.GUARANTEED &&
                    (facts.baseAccuracyProbability ?: 0.0) >= CERTAIN_ACCURACY
            }
            if (alreadySecured) {
                -REDUNDANT_FOCUS_PENALTY
            } else {
                val minimumCombinedDamage = attacks.sumOf {
                    it.facts?.standardDamageFractionRange?.minimum ?: 0.0
                }
                val allHitProbability = attacks.fold(1.0) { probability, action ->
                    probability * (action.facts?.baseAccuracyProbability ?: 0.0)
                }
                if (minimumCombinedDamage >= target.hpFraction) {
                    SECURE_FOCUS_KNOCKOUT_BONUS * allHitProbability
                } else {
                    0.0
                }
            }
        }
    }

    private fun selectedOpponent(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        actingSide: BattleSide = BattleSide.ALLY,
    ): BattlePokemonStateView? {
        val targetSide = if (actingSide == BattleSide.ALLY) BattleSide.OPPONENT else BattleSide.ALLY
        val targetSlot = candidate.targets.singleOrNull { it.side == targetSide }?.slot ?: return null
        return context.state.pokemon.firstOrNull {
            it.side == targetSide && it.activeSlot == targetSlot && !it.fainted
        }
    }

    private fun actor(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        actingSide: BattleSide = BattleSide.ALLY,
    ): BattlePokemonStateView? {
        val actorSlot = candidate.actorSlot ?: return null
        return context.state.pokemon.firstOrNull {
            it.side == actingSide && it.activeSlot == actorSlot && !it.fainted
        }
    }

    private fun requirementState(
        requirement: BattleMoveRequirementView,
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        actingSide: BattleSide = BattleSide.ALLY,
    ): RequirementState {
        val actor = actor(candidate, context, actingSide)
        val target = selectedOpponent(candidate, context, actingSide)
        return when (requirement.kind) {
            BattleMoveRequirementKind.WEATHER_ANY_OF -> knownMatch(
                context.state.field.weather?.effectId,
                requirement.acceptedValueIds,
            )
            BattleMoveRequirementKind.TERRAIN_PRESENT -> satisfied(context.state.field.terrain != null)
            BattleMoveRequirementKind.USER_STATUS_ANY_OF -> knownMatch(actor?.statusId, requirement.acceptedValueIds)
            BattleMoveRequirementKind.USER_STATUS_PRESENT -> actor?.let { satisfied(it.statusId != null) }
                ?: RequirementState.UNKNOWN
            BattleMoveRequirementKind.TARGET_STATUS_ANY_OF -> knownMatch(target?.statusId, requirement.acceptedValueIds)
            BattleMoveRequirementKind.TARGET_STATUS_ABSENT -> target?.let { satisfied(it.statusId == null) }
                ?: RequirementState.UNKNOWN
            BattleMoveRequirementKind.USER_TYPE_ANY_OF -> actor?.let {
                satisfied(it.knownTypeIds.any { type -> canonicalEffectId(type) in canonical(requirement.acceptedValueIds) })
            } ?: RequirementState.UNKNOWN
            BattleMoveRequirementKind.USER_HP_ABOVE_FRACTION -> {
                val threshold = requirement.threshold ?: return RequirementState.UNKNOWN
                actor?.let { satisfied(it.hpFraction > threshold) } ?: RequirementState.UNKNOWN
            }
            BattleMoveRequirementKind.TARGET_HP_ABOVE_USER -> if (actor != null && target != null) {
                satisfied(target.hpFraction > actor.hpFraction)
            } else {
                RequirementState.UNKNOWN
            }
            BattleMoveRequirementKind.TARGET_HELD_ITEM_PRESENT ->
                if (target?.knownHeldItemId != null) RequirementState.SATISFIED else RequirementState.UNKNOWN
            BattleMoveRequirementKind.FAINTED_ALLY_PRESENT -> satisfied(
                context.state.pokemon.any { it.side == actingSide && it.fainted },
            )
            BattleMoveRequirementKind.RESERVE_ALLY_PRESENT -> satisfied(
                context.state.remainingPokemonBySide.getValue(actingSide) >
                    context.state.pokemon.count {
                        it.side == actingSide && it.activeSlot != null && !it.fainted
                    },
            )
            BattleMoveRequirementKind.PRIOR_DAMAGE_THIS_TURN -> actor?.let { activeActor ->
                satisfied(
                    context.state.observedEvents.any {
                        it.turn == context.state.turn &&
                            it.kind == BattleObservedEventKind.HP_CHANGED &&
                            it.actorPokemonId == activeActor.battlePokemonId &&
                            (it.hpFractionDelta ?: 0.0) < 0.0
                    },
                )
            } ?: RequirementState.UNKNOWN
            BattleMoveRequirementKind.OTHER_MOVES_USED -> actor?.let { activeActor ->
                if (activeActor.knownMoveIds.isEmpty()) {
                    RequirementState.UNKNOWN
                } else {
                    val candidateId = candidate.moveId?.let(::canonicalEffectId)
                    val required = activeActor.knownMoveIds.mapTo(linkedSetOf(), ::canonicalEffectId) - candidateId
                    val used = context.state.observedEvents.asSequence()
                        .filter {
                            it.kind == BattleObservedEventKind.MOVE_USED &&
                                it.actorPokemonId == activeActor.battlePokemonId
                        }
                        .mapNotNull { it.publicValueId?.let(::canonicalEffectId) }
                        .toSet()
                    satisfied(required.isNotEmpty() && required.all { it in used })
                }
            } ?: RequirementState.UNKNOWN
            BattleMoveRequirementKind.USER_SPECIES_ANY_OF -> actor?.let {
                val actorIds = setOfNotNull(it.speciesId, it.formId).mapTo(linkedSetOf(), ::canonicalEffectId)
                satisfied(actorIds.any { id -> id in canonical(requirement.acceptedValueIds) })
            } ?: RequirementState.UNKNOWN
            BattleMoveRequirementKind.MULTIPLE_ACTIVE_POKEMON -> satisfied(context.state.format == BattleFormat.DOUBLE)
            BattleMoveRequirementKind.USER_HELD_BERRY,
            BattleMoveRequirementKind.USER_VOLATILE_PRESENT,
            BattleMoveRequirementKind.TARGET_PENDING_DAMAGING_MOVE,
            BattleMoveRequirementKind.OPPOSITE_GENDER,
            -> RequirementState.UNKNOWN
            BattleMoveRequirementKind.TARGET_LAST_MOVE_PRESENT -> target?.let { activeTarget ->
                satisfied(
                    context.state.observedEvents.any {
                        it.kind == BattleObservedEventKind.MOVE_USED &&
                            it.actorPokemonId == activeTarget.battlePokemonId
                    },
                )
            } ?: RequirementState.UNKNOWN
        }
    }

    private fun knownMatch(value: String?, accepted: Set<String>): RequirementState = when {
        value == null -> RequirementState.UNSATISFIED
        sameEffect(value, "none") -> RequirementState.UNSATISFIED
        canonicalEffectId(value) in canonical(accepted) -> RequirementState.SATISFIED
        else -> RequirementState.UNSATISFIED
    }

    private fun canonical(values: Set<String>): Set<String> = values.mapTo(linkedSetOf(), ::canonicalEffectId)

    private fun satisfied(value: Boolean): RequirementState =
        if (value) RequirementState.SATISFIED else RequirementState.UNSATISFIED

    private fun sameEffect(first: String?, second: String?): Boolean =
        first != null && second != null && canonicalEffectId(first) == canonicalEffectId(second)

    private fun activeEffectBlocksRefresh(
        active: jbro.cobblemon.morebattlecontent.api.ai.BattleTimedEffectView?,
        effectId: String,
    ): Boolean = active != null && active.remainingTurns != EXPIRING_EFFECT_TURNS &&
        sameEffect(active.effectId, effectId)

    private fun canonicalEffectId(effectId: String): String =
        effectId.substringAfter(':').lowercase().filter { it.isLetterOrDigit() }

    private const val GUARANTEED_KNOCKOUT_BONUS = 50.0
    private const val POSSIBLE_KNOCKOUT_BONUS = 35.0
    private const val ENTRY_KNOCKOUT_PENALTY = 200.0
    private const val SECURE_FOCUS_KNOCKOUT_BONUS = 25.0
    private const val REDUNDANT_FOCUS_PENALTY = 30.0
    private enum class RequirementState { SATISFIED, UNSATISFIED, UNKNOWN }

    private const val ACTIVE_PERSISTENT_EFFECT_REFRESH_PENALTY = 100.0
    private const val EXPIRED_FIRST_ACTIVE_TURN_PENALTY = 100.0
    private const val SATURATED_STAT_STAGE_PENALTY = 100.0
    private const val UNMET_PUBLIC_REQUIREMENT_PENALTY = 100.0
    private const val RECENT_PUBLIC_FAILURE_PENALTY = 100.0
    private const val REPEATED_PROTECTION_HABIT_PENALTY = 8.0
    private const val PROTECTION_NO_PROGRESS_PENALTY = 4.0
    private const val MAX_PROTECTION_NO_PROGRESS_STREAK = 3
    private const val CONDITIONAL_MOVE_CERTAIN_FAILURE_PENALTY = 100.0
    private const val CONDITIONAL_MOVE_ALTERNATIVE_RISK_PENALTY = 18.0
    private const val CONDITIONAL_MOVE_UNKNOWN_RISK_PENALTY = 8.0
    private const val CONSECUTIVE_USE_FORBIDDEN_PENALTY = 100.0
    private const val CHARGE_TURN_PENALTY = 20.0
    private const val RECHARGE_TURN_PENALTY = 25.0
    private const val RECENT_FAILURE_TURNS = 2
    private const val EXPIRING_EFFECT_TURNS = 1
    private const val MINIMUM_STAT_STAGE = -6
    private const val MAXIMUM_STAT_STAGE = 6
    private const val CRITICAL_SETUP_HP_FRACTION = 0.25
    private const val MAXIMUM_SETUP_STAGE_BUDGET = 4
    private const val CERTAIN_ACCURACY = 0.999
    private val PUBLIC_FAILURE_OUTCOMES = setOf(
        BattleMoveOutcomeKind.FAILED,
        BattleMoveOutcomeKind.NO_TARGET,
        BattleMoveOutcomeKind.CANNOT_ACT,
    )
    private val REQUIREMENT_CHANGE_EVENTS = setOf(
        BattleObservedEventKind.SWITCHED,
        BattleObservedEventKind.HP_CHANGED,
        BattleObservedEventKind.STATUS_CHANGED,
        BattleObservedEventKind.FIELD_EFFECT_CHANGED,
    )
}
