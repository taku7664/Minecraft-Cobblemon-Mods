package jbro.cobblemon.morebattlecontent.betterai.calculation

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalDeclaredMultiHit
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalKnownStatMechanics
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalPublicMechanicsKernel
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalPublicMoveProjection
import jbro.cobblemon.morebattlecontent.betterai.mechanics.PublicSwitchEntryHazardCalculator
import jbro.cobblemon.morebattlecontent.betterai.mechanics.ShowdownStandardDamageProjection
import jbro.cobblemon.morebattlecontent.betterai.mechanics.ShowdownStandardDamageProjectionResult
import jbro.cobblemon.morebattlecontent.betterai.mechanics.StandardTypeEffectiveness

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
        val typeMultiplier = publicTypeMultiplier(details, target)
        val mechanics = LocalPublicMechanicsKernel.projectMove(candidate, context, actingSide)
        declaredDamageRollFractions(candidate, actor, target, mechanics)?.let { return it }
        val projection = standardDamageProjection(candidate, details, actor, target, stab, typeMultiplier)
            ?: return null
        val maxHp = target?.combatStats?.maxHp ?: return null
        val (rolls, denominator) = if (actingSide == BattleSide.ALLY) {
            projection.minimumHypothesisRolls to maxHp.maximum
        } else {
            projection.maximumHypothesisRolls to maxHp.minimum
        }
        val hitCount = if (LocalDeclaredMultiHit.usesPerHitAccuracy(candidate)) 1 else {
            LocalDeclaredMultiHit.representativeCount(candidate, actor)
        }
        return rolls.map { damage ->
            (damage.toDouble() / denominator * hitCount * mechanics.knownDamageMultiplier)
                .coerceAtMost(target.hpFraction)
                .coerceIn(0.0, 1.0)
        }
    }

    private fun declaredDamageRollFractions(
        candidate: BattleActionCandidate,
        actor: BattlePokemonStateView?,
        target: BattlePokemonStateView?,
        mechanics: LocalPublicMoveProjection,
    ): List<Double>? {
        val effects = candidate.moveDetails?.effects?.effects.orEmpty()
        val targetHp = target?.hpFraction ?: return null
        if (mechanics.publiclyNullified) return listOf(0.0)
        effects.firstOrNull { it.kind == BattleMoveEffectKind.ONE_HIT_KO }?.let {
            val actorLevel = actor?.level
            val targetLevel = target.level
            val levelBlocked = actorLevel != null && targetLevel != null && targetLevel > actorLevel
            val sturdyBlocked = canonical(target.knownAbilityId) == "sturdy"
            return listOf(if (levelBlocked || sturdyBlocked) 0.0 else targetHp)
        }
        val maxHp = target.combatStats?.maxHp ?: return null
        effects.firstOrNull { it.kind == BattleMoveEffectKind.FIXED_DAMAGE_LEVEL }?.let {
            val damage = actor?.level ?: return null
            return listOf(
                (damage.toDouble() / maxHp.maximum).coerceAtMost(targetHp),
                (damage.toDouble() / maxHp.minimum).coerceAtMost(targetHp),
            )
        }
        effects.firstOrNull { it.kind == BattleMoveEffectKind.FIXED_DAMAGE_VALUE }?.let { effect ->
            val amount = effect.amountRange ?: return null
            return listOf(
                (amount.minimum.toDouble() / maxHp.maximum).coerceAtMost(targetHp),
                (amount.maximum.toDouble() / maxHp.minimum).coerceAtMost(targetHp),
            )
        }
        return null
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
        val typeMultiplier = target?.knownTypeIds?.takeIf { it.isNotEmpty() }?.let {
            basis += BattleCalculationBasis.PUBLIC_TYPES
            publicTypeMultiplier(details, target)
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
        // A declared effect with no probability is a certain effect - that is how the outcome
        // projector reads the same field. Requiring a literal `1.0` here meant every recovery move
        // whose data omits the probability produced no `selfHealingFractionRange`, so the local
        // evaluator never entered its recovery branch at all and scored the move as a generic status
        // effect instead. Everything hanging off that branch, including both anti-recovery-loop
        // guards, was unreachable for real move data.
        val declaredHeal = details.effects?.effects?.singleOrNull {
            it.kind == BattleMoveEffectKind.HEAL_FRACTION &&
                it.target == BattleMoveEffectTarget.USER &&
                (it.probability ?: 1.0) == 1.0 &&
                it.fractionRange != null
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
        if (details.damageCategory == BattleMoveDamageCategory.STATUS || isDelayedSlotDamage(details)) return null
        if (details.targetPattern !in DAMAGE_TARGET_PATTERNS || candidate.mechanic != null) return null
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
        val effects = details.effects?.effects.orEmpty()
        val guaranteedCritical = effects.any { it.kind == BattleMoveEffectKind.ALWAYS_CRITICAL }
        val stealsStages = effects.any { it.kind == BattleMoveEffectKind.STEALS_STAT_STAGES }
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
        val stolenAttackStage = if (stealsStages) {
            when (details.damageCategory) {
                BattleMoveDamageCategory.PHYSICAL -> target.stage("attack", "atk").coerceAtLeast(0)
                BattleMoveDamageCategory.SPECIAL -> target.stage("special_attack", "specialattack", "spa").coerceAtLeast(0)
                BattleMoveDamageCategory.STATUS -> 0
            }
        } else {
            0
        }
        val effectiveAttackStage = if (guaranteedCritical && attackStage < 0) 0 else {
            (attackStage + stolenAttackStage).coerceIn(-6, 6)
        }
        val ignoresDefensiveStages = guaranteedCritical ||
            effects.any { it.kind == BattleMoveEffectKind.IGNORE_DEFENSIVE_STAGES }
        val effectiveDefenceStage = if (ignoresDefensiveStages && defenceStage > 0) 0 else defenceStage
        val stagedAttack = applyStage(attack, effectiveAttackStage)
        return ShowdownStandardDamageProjection.project(
            level = level,
            power = effectivePower,
            attack = publicStatusModifiedAttack(stagedAttack, details.damageCategory, actor),
            defence = LocalKnownStatMechanics.defence(
                applyStage(defence, effectiveDefenceStage),
                details.damageCategory,
                target,
            ),
            targetMaxHp = targetStats.maxHp,
            targetHpFraction = target.hpFraction,
            stab = knownStab,
            typeMultiplier = knownTypeMultiplier,
            guaranteedCritical = guaranteedCritical,
        )
    }

    private fun publicTypeMultiplier(
        details: BattleMoveCandidateView,
        target: BattlePokemonStateView?,
    ): Double? {
        val types = target?.knownTypeIds?.takeIf { it.isNotEmpty() } ?: return null
        val ignoresImmunity = details.effects?.effects.orEmpty().any {
            it.kind == BattleMoveEffectKind.IGNORE_TYPE_IMMUNITY
        }
        // A revealed defensive ability is public information, so the fair chart must honour it.
        // Without this the projection reports a clean hit for Ground into a revealed Levitate.
        return StandardTypeEffectiveness.multiplierAgainst(
            attackingTypeId = details.typeId,
            defendingTypeIds = types,
            defenderAbilityId = target.knownAbilityId,
            ignoreTypeImmunity = ignoresImmunity,
        )
    }

    private fun isDelayedSlotDamage(details: BattleMoveCandidateView): Boolean =
        details.effects?.effects.orEmpty().any {
            it.kind == BattleMoveEffectKind.SLOT_CONDITION && canonical(it.valueId) == "futuremove"
        }

    private val DAMAGE_TARGET_PATTERNS = setOf(
        BattleMoveTargetPattern.SELECTED,
        BattleMoveTargetPattern.SELECTED_OPPONENT,
        BattleMoveTargetPattern.SELECTED_ALLY,
        BattleMoveTargetPattern.SELECTED_ALLY_OR_SELF,
        BattleMoveTargetPattern.RANDOM_OPPONENT,
        BattleMoveTargetPattern.ALL_ACTIVE,
        BattleMoveTargetPattern.ALL_ADJACENT,
        BattleMoveTargetPattern.ALL_OPPONENTS,
        BattleMoveTargetPattern.ALL_ALLIES,
    )

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
        val explicitTarget = candidate.targets.singleOrNull()
        if (explicitTarget != null) return active(context, explicitTarget.side, explicitTarget.slot)
        val targetSide = if (actingSide == BattleSide.ALLY) BattleSide.OPPONENT else BattleSide.ALLY
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
