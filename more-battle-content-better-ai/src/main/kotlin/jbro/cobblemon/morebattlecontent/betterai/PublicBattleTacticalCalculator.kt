package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*

/**
 * Produces only mechanics facts derivable from the fair decision context.
 *
 * It intentionally has no trainer profile, strategy, memory weighting, utility, ranking, or
 * recommendation input. Missing mechanics stay unknown rather than being replaced by a heuristic.
 */
internal object PublicBattleTacticalCalculator {
    fun calculate(
        context: BattleDecisionContext,
        actingSide: BattleSide = BattleSide.ALLY,
    ): BattleDecisionContext {
        if (context.candidates.all(::fullyCalculated)) return context
        return BattleDecisionContext(
            requestId = context.requestId,
            state = context.state,
            candidates = context.candidates.map { calculateCandidate(it, context, actingSide) },
            deadlineEpochMillis = context.deadlineEpochMillis,
            memory = context.memory,
            publicActionCatalog = context.publicActionCatalog,
        )
    }

    /**
     * Returns sixteen mechanically possible Showdown damage rolls without assigning probability to
     * hidden stat hypotheses. Own attacks use the public lower-damage hypothesis; opponent attacks
     * use the public upper-damage hypothesis.
     */
    fun conservativeDamageRollFractions(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        actingSide: BattleSide,
    ): List<Double>? {
        val details = candidate.moveDetails ?: return null
        val actor = candidate.actorSlot?.let { slot -> active(context, actingSide, slot) }
        val target = singleOpponentTarget(candidate, context, actingSide)
        val stab = actor?.knownTypeIds?.takeIf { it.isNotEmpty() }?.let { types ->
            if (types.any { it.equals(details.typeId, ignoreCase = true) }) 1.5 else 1.0
        }
        val typeMultiplier = target?.knownTypeIds?.takeIf { it.isNotEmpty() }?.let { types ->
            StandardTypeEffectiveness.multiplier(details.typeId, types)
        }
        val projection = standardDamageProjection(candidate, details, actor, target, stab, typeMultiplier)
            ?: return null
        val maxHp = target?.combatStats?.maxHp ?: return null
        val mechanics = LocalPublicMechanicsKernel.projectMove(candidate, context, actingSide)
        val (rolls, denominator) = if (actingSide == BattleSide.ALLY) {
            projection.minimumHypothesisRolls to maxHp.maximum
        } else {
            projection.maximumHypothesisRolls to maxHp.minimum
        }
        return rolls.map { damage ->
            (damage.toDouble() / denominator * mechanics.knownDamageMultiplier)
                .coerceAtMost(target.hpFraction)
                .coerceIn(0.0, 1.0)
        }
    }

    private fun fullyCalculated(candidate: BattleActionCandidate): Boolean =
        if (candidate.kind == BattleActionKind.COMPOSITE) {
            candidate.componentActions.isNotEmpty() && candidate.componentActions.all(::fullyCalculated)
        } else {
            candidate.facts != null
        }

    private fun calculateCandidate(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        actingSide: BattleSide,
    ): BattleActionCandidate {
        if (candidate.kind == BattleActionKind.COMPOSITE) {
            val components = candidate.componentActions.map { calculateCandidate(it, context, actingSide) }
            return candidate.copyWith(componentActions = components)
        }
        if (candidate.facts != null) return candidate
        return candidate.copyWith(facts = facts(candidate, context, actingSide))
    }

    private fun facts(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        actingSide: BattleSide,
    ): BattleCandidateFactsView {
        val details = candidate.moveDetails
        if (candidate.kind == BattleActionKind.SWITCH) {
            val target = candidate.switchPokemonId?.let { targetId ->
                context.state.pokemon.firstOrNull {
                    it.battlePokemonId == targetId && it.side == actingSide && !it.fainted
                }
            }
            val entryHpLoss = target?.let {
                PublicSwitchEntryHazardCalculator.hpLoss(context.state.field, actingSide, it)
            }
            return BattleCandidateFactsView(
                switchEntryHpLossFraction = entryHpLoss,
                calculationCoverage = if (entryHpLoss == null) {
                    BattleCalculationCoverage.UNKNOWN
                } else {
                    BattleCalculationCoverage.PARTIAL
                },
                unknowns = setOf(BattleCalculationUnknown.ENTRY_EFFECTS, BattleCalculationUnknown.ACTION_ORDER),
                basis = if (entryHpLoss == null) emptySet() else setOf(
                    BattleCalculationBasis.PUBLIC_TYPES,
                    BattleCalculationBasis.SERVER_PROVIDED_MECHANICS,
                ),
            )
        }
        if (candidate.kind != BattleActionKind.USE_MOVE || details == null) {
            return BattleCandidateFactsView(
                calculationCoverage = BattleCalculationCoverage.UNKNOWN,
                unknowns = setOf(BattleCalculationUnknown.MOVE_EFFECTS),
            )
        }

        val basis = linkedSetOf(BattleCalculationBasis.MOVE_TEMPLATE)
        val unknowns = linkedSetOf(
            BattleCalculationUnknown.ACCURACY_MODIFIERS,
            BattleCalculationUnknown.ACTION_ORDER,
            BattleCalculationUnknown.MOVE_EFFECTS,
        )
        val actor = candidate.actorSlot?.let { slot -> active(context, actingSide, slot) }
        val stab = actor?.knownTypeIds?.takeIf { it.isNotEmpty() }?.let { types ->
            basis += BattleCalculationBasis.PUBLIC_TYPES
            if (types.any { it.equals(details.typeId, ignoreCase = true) }) 1.5 else 1.0
        }
        val target = singleOpponentTarget(candidate, context, actingSide)
        val typeMultiplier = target?.knownTypeIds?.takeIf { it.isNotEmpty() }?.let { types ->
            basis += BattleCalculationBasis.PUBLIC_TYPES
            StandardTypeEffectiveness.multiplier(details.typeId, types)
        }
        if (details.damageCategory != BattleMoveDamageCategory.STATUS) {
            unknowns += BattleCalculationUnknown.DYNAMIC_DAMAGE_MODIFIERS
            if (typeMultiplier == null) unknowns += BattleCalculationUnknown.TARGET_TYPES
        }
        val projection = standardDamageProjection(candidate, details, actor, target, stab, typeMultiplier)
        if (details.damageCategory != BattleMoveDamageCategory.STATUS && projection == null) {
            if (actor?.combatStats == null) unknowns += BattleCalculationUnknown.ATTACKER_OFFENSIVE_STATS
            if (target?.combatStats == null) unknowns += BattleCalculationUnknown.OPPONENT_DEFENSIVE_STATS
            if (target == null) unknowns += BattleCalculationUnknown.TARGET_CURRENT_HP
            unknowns += BattleCalculationUnknown.DAMAGE_ENGINE
        } else if (projection != null) {
            basis += BattleCalculationBasis.PUBLIC_STAT_RANGES
            basis += BattleCalculationBasis.SHOWDOWN_GEN9_FORMULA
        }
        val declaredHeal = details.effects?.effects?.singleOrNull {
            it.kind == BattleMoveEffectKind.HEAL_FRACTION &&
                it.target == BattleMoveEffectTarget.USER && it.probability == 1.0 && it.fractionRange != null
        }
        val declaredStatus = details.effects?.effects?.singleOrNull {
            it.kind == BattleMoveEffectKind.STATUS &&
                it.target == BattleMoveEffectTarget.SELECTED_TARGET && it.probability != null
        }
        return BattleCandidateFactsView(
            baseAccuracyProbability = details.accuracy.div(100.0).coerceIn(0.0, 1.0),
            typeChartMultiplier = typeMultiplier,
            baseSameTypeAttackBonus = stab,
            standardDamageModel = projection?.let { BattleStandardDamageModel.SHOWDOWN_GEN9_BASE_NON_CRITICAL },
            standardDamageFractionRange = projection?.damageFractionRange,
            standardDamageRollKoProbabilityRange = projection?.koProbabilityRange,
            standardKnockoutAssessment = projection?.knockoutAssessment,
            selfHealingFractionRange = declaredHeal?.fractionRange,
            statusEffectProbability = declaredStatus?.probability?.times(details.accuracy / 100.0),
            calculationCoverage = BattleCalculationCoverage.PARTIAL,
            unknowns = unknowns,
            basis = basis,
        )
    }

    private fun standardDamageProjection(
        candidate: BattleActionCandidate,
        details: BattleMoveCandidateView,
        actor: BattlePokemonStateView?,
        target: BattlePokemonStateView?,
        stab: Double?,
        typeMultiplier: Double?,
    ): ShowdownStandardDamageProjectionResult? {
        if (details.damageCategory == BattleMoveDamageCategory.STATUS) return null
        if (details.targetPattern != BattleMoveTargetPattern.SELECTED || candidate.mechanic != null) return null
        val level = actor?.level ?: return null
        val actorStats = actor.combatStats ?: return null
        val targetStats = target?.combatStats ?: return null
        val wholePower = details.power.toInt().takeIf { it > 0 && it.toDouble() == details.power } ?: return null
        val effectivePower = LocalKnownStatMechanics.effectivePower(wholePower, actor)
        val knownStab = stab ?: return null
        val knownTypeMultiplier = typeMultiplier ?: return null
        val (attack, defence) = when (details.damageCategory) {
            BattleMoveDamageCategory.PHYSICAL -> actorStats.attack to targetStats.defence
            BattleMoveDamageCategory.SPECIAL -> actorStats.specialAttack to targetStats.specialDefence
            BattleMoveDamageCategory.STATUS -> return null
        }
        val attackStage = when (details.damageCategory) {
            BattleMoveDamageCategory.PHYSICAL -> actor.stage("attack", "atk")
            BattleMoveDamageCategory.SPECIAL -> actor.stage("special_attack", "specialattack", "spa")
            BattleMoveDamageCategory.STATUS -> 0
        }
        val defenceStage = when (details.damageCategory) {
            BattleMoveDamageCategory.PHYSICAL -> target.stage("defence", "defense", "def")
            BattleMoveDamageCategory.SPECIAL -> target.stage(
                "special_defence", "special_defense", "specialdefence", "specialdefense", "spd",
            )
            BattleMoveDamageCategory.STATUS -> 0
        }
        val stagedAttack = applyStage(attack, attackStage)
        return ShowdownStandardDamageProjection.project(
            level = level,
            power = effectivePower,
            attack = publicStatusModifiedAttack(stagedAttack, details.damageCategory, actor),
            defence = LocalKnownStatMechanics.defence(applyStage(defence, defenceStage), details.damageCategory, target),
            targetMaxHp = targetStats.maxHp,
            targetHpFraction = target.hpFraction,
            stab = knownStab,
            typeMultiplier = knownTypeMultiplier,
        )
    }

    private fun publicStatusModifiedAttack(
        attack: BattleIntegerRange,
        category: BattleMoveDamageCategory,
        actor: BattlePokemonStateView,
    ): BattleIntegerRange {
        if (category != BattleMoveDamageCategory.PHYSICAL) return attack
        val status = canonical(actor.statusId)
        val ability = canonical(actor.knownAbilityId)
        val multiplier = when {
            status != null && ability == "guts" -> 1.5
            status in BURN_STATUS_IDS -> 0.5
            else -> 1.0
        }
        return BattleIntegerRange(
            minimum = (attack.minimum * multiplier).toInt().coerceAtLeast(1),
            maximum = (attack.maximum * multiplier).toInt().coerceAtLeast(1),
        )
    }

    private fun canonical(value: String?): String? = value
        ?.substringAfter(':')
        ?.lowercase()
        ?.filter(Char::isLetterOrDigit)

    private fun BattlePokemonStateView.stage(vararg aliases: String): Int = statStages.entries
        .firstOrNull { (key, _) -> key.substringAfter(':').lowercase() in aliases }
        ?.value
        ?.coerceIn(-6, 6)
        ?: 0

    private fun applyStage(range: BattleIntegerRange, stage: Int): BattleIntegerRange = BattleIntegerRange(
        minimum = applyStage(range.minimum, stage),
        maximum = applyStage(range.maximum, stage),
    )

    private fun applyStage(value: Int, stage: Int): Int = if (stage >= 0) {
        value * (2 + stage) / 2
    } else {
        value * 2 / (2 - stage)
    }.coerceAtLeast(1)

    private val BURN_STATUS_IDS = setOf("brn", "burn", "burned", "burnt")

    private fun singleOpponentTarget(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        actingSide: BattleSide,
    ): BattlePokemonStateView? {
        val explicitSlots = candidate.targets.filter { it.side == BattleSide.OPPONENT }.map { it.slot }
        val targetSide = if (actingSide == BattleSide.ALLY) BattleSide.OPPONENT else BattleSide.ALLY
        if (explicitSlots.size == 1) return active(context, targetSide, explicitSlots.single())
        val activeOpponents = context.state.pokemon.filter {
            it.side == targetSide && it.activeSlot != null && !it.fainted
        }
        return activeOpponents.singleOrNull()
    }

    private fun active(context: BattleDecisionContext, side: BattleSide, slot: Int): BattlePokemonStateView? =
        context.state.pokemon.firstOrNull {
            it.side == side && it.activeSlot == slot && !it.fainted
        }

    private fun BattleActionCandidate.copyWith(
        componentActions: List<BattleActionCandidate> = this.componentActions,
        facts: BattleCandidateFactsView? = this.facts,
    ) = BattleActionCandidate(
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
        facts = facts,
        tags = tags,
    )
}
