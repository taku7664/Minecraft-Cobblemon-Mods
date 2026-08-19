package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*

/**
 * Produces only mechanics facts derivable from the fair decision context.
 *
 * It intentionally has no trainer profile, strategy, memory weighting, utility, ranking, or
 * recommendation input. Missing mechanics stay unknown rather than being replaced by a heuristic.
 */
internal object PublicBattleTacticalCalculator {
    fun calculate(context: BattleDecisionContext): BattleDecisionContext {
        if (context.candidates.all(::fullyCalculated)) return context
        return BattleDecisionContext(
            requestId = context.requestId,
            state = context.state,
            candidates = context.candidates.map { calculateCandidate(it, context) },
            deadlineEpochMillis = context.deadlineEpochMillis,
            memory = context.memory,
        )
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
    ): BattleActionCandidate {
        if (candidate.kind == BattleActionKind.COMPOSITE) {
            val components = candidate.componentActions.map { calculateCandidate(it, context) }
            return candidate.copyWith(componentActions = components)
        }
        if (candidate.facts != null) return candidate
        return candidate.copyWith(facts = facts(candidate, context))
    }

    private fun facts(candidate: BattleActionCandidate, context: BattleDecisionContext): BattleCandidateFactsView {
        val details = candidate.moveDetails
        if (candidate.kind == BattleActionKind.SWITCH) {
            return BattleCandidateFactsView(
                calculationCoverage = BattleCalculationCoverage.UNKNOWN,
                unknowns = setOf(BattleCalculationUnknown.ENTRY_EFFECTS, BattleCalculationUnknown.ACTION_ORDER),
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
        val actor = candidate.actorSlot?.let { slot -> active(context, BattleSide.ALLY, slot) }
        val stab = actor?.knownTypeIds?.takeIf { it.isNotEmpty() }?.let { types ->
            basis += BattleCalculationBasis.PUBLIC_TYPES
            if (types.any { it.equals(details.typeId, ignoreCase = true) }) 1.5 else 1.0
        }
        val target = singleOpponentTarget(candidate, context)
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
        return ShowdownStandardDamageProjection.project(
            level = level,
            power = wholePower,
            attack = applyStage(attack, attackStage),
            defence = applyStage(defence, defenceStage),
            targetMaxHp = targetStats.maxHp,
            targetHpFraction = target.hpFraction,
            stab = knownStab,
            typeMultiplier = knownTypeMultiplier,
        )
    }

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

    private fun singleOpponentTarget(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
    ): BattlePokemonStateView? {
        val explicitSlots = candidate.targets.filter { it.side == BattleSide.OPPONENT }.map { it.slot }
        if (explicitSlots.size == 1) return active(context, BattleSide.OPPONENT, explicitSlots.single())
        val activeOpponents = context.state.pokemon.filter {
            it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted
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
