package jbro.cobblemon.morebattlecontent.betterai.calculation

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.api.ai.BattleAbilityAvailability
import jbro.cobblemon.morebattlecontent.api.ai.BattleInferenceConfidence
import jbro.cobblemon.morebattlecontent.api.ai.BattleInferenceView
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalDeclaredMultiHit
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalFullHealthSurvivalRules
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalKnownStatMechanics
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalMechanicFormResolution
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalPublicMechanicsKernel
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalPublicStatusImmunity
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalPublicTurnOrder
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
        val targets = resolvedTargets(candidate, context, actingSide)
        // These rolls feed the search, which applies them to one defender. A spread move is therefore
        // projected against its primary target only: the extra slot is visible to the scorer through
        // `spreadTargets`, but the recursive projection still moves one HP bar per action.
        val target = targets.firstOrNull()
        val spreadMultiplier = if (targets.size > 1) SPREAD_DAMAGE_MULTIPLIER else 1.0
        val stab = sameTypeAttackBonus(details, actor, candidate)
        val typeMultiplier = publicTypeMultiplier(details, target, context)
        val mechanics = LocalPublicMechanicsKernel.projectMove(candidate, context, actingSide)
        declaredDamageRollFractions(candidate, actor, target, mechanics)?.let { return it }
        val projection =
            standardDamageProjection(candidate, details, actor, target, stab, typeMultiplier, spreadMultiplier)
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
        val stab = sameTypeAttackBonus(details, actor, candidate)
            ?.also { basis += BattleCalculationBasis.PUBLIC_TYPES }
        val targets = resolvedTargets(candidate, context, actingSide)
        val target = targets.firstOrNull()
        val spreadMultiplier = if (targets.size > 1) SPREAD_DAMAGE_MULTIPLIER else 1.0
        val typeMultiplier = target?.knownTypeIds?.takeIf { it.isNotEmpty() }?.let {
            basis += BattleCalculationBasis.PUBLIC_TYPES
            publicTypeMultiplier(details, target, context)
        }
        if (details.damageCategory != BattleMoveDamageCategory.STATUS) {
            unknowns += BattleCalculationUnknown.DYNAMIC_DAMAGE_MODIFIERS
            if (typeMultiplier == null) unknowns += BattleCalculationUnknown.TARGET_TYPES
        }
        val rawProjection =
            standardDamageProjection(candidate, details, actor, target, stab, typeMultiplier, spreadMultiplier)
        // A Focus Sash or Sturdy at full health turns a knockout the rolls call guaranteed into a
        // survivor on one health. The projector has always known that; the facts the ranking is built
        // from did not, so the layer that decides was the one working from the wrong premise. A move
        // that strikes more than once is unaffected - the second hit goes through whatever held the
        // first.
        val survivesOneHit = target != null &&
            LocalDeclaredMultiHit.maximumCount(candidate) <= 1 &&
            LocalFullHealthSurvivalRules.survivesAnySingleHit(context.state, target)
        val projection = if (survivesOneHit) rawProjection?.withoutKnockout(target) else rawProjection
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
            // The contract has always carried this field and nothing ever filled it, so the ranking
            // had no notion of who moves first and the entire subject lived inside the search. At the
            // lowest tier that search is one ply and discounted twice, which is precisely the trainer
            // a player meets first. The opponent's priority is unknown, so this answers the question
            // that can be answered honestly: how this move compares against an ordinary reply.
            actsFirstProbability = LocalPublicTurnOrder.actsFirstProbability(
                state = context.state,
                actorSide = actingSide,
                actorSlot = candidate.actorSlot,
                actorPriority = details.priority,
                opponentPriority = 0,
            ),
            standardDamageModel = projection?.let { BattleStandardDamageModel.SHOWDOWN_GEN9_BASE_NON_CRITICAL },
            standardDamageFractionRange = projection?.damageFractionRange,
            standardDamageRollKoProbabilityRange = projection?.koProbabilityRange,
            standardKnockoutAssessment = projection?.knockoutAssessment,
            selfHealingFractionRange = declaredHeal?.fractionRange,
            // The projector has always refused a status the target cannot take; the facts the root
            // ranking is built from did not, so Toxic into a Steel type was priced as a normal play
            // and only the search knew better. Same module, same answer, one place.
            statusEffectProbability = declaredStatus
                ?.takeIf { target == null || !LocalPublicStatusImmunity.blocked(context.state, target, it.valueId) }
                ?.probability?.times(details.accuracy / 100.0),
            calculationCoverage = BattleCalculationCoverage.PARTIAL,
            unknowns = unknowns,
            basis = basis,
            spreadTargets = spreadTargetFacts(candidate, details, actor, stab, targets, spreadMultiplier),
        )
    }

    /**
     * Per-slot facts for a move that hits several opponents, or nothing for an ordinary move.
     *
     * The first entry deliberately repeats the primary target rather than listing only the extras, so
     * a reader never has to combine two differently shaped sources to see the whole turn.
     */
    private fun spreadTargetFacts(
        candidate: BattleActionCandidate,
        details: BattleMoveCandidateView,
        actor: BattlePokemonStateView?,
        stab: Double?,
        targets: List<BattlePokemonStateView>,
        spreadMultiplier: Double,
    ): List<BattleSpreadTargetFactsView> {
        if (targets.size < 2) return emptyList()
        return targets.mapNotNull { each ->
            val slot = each.activeSlot ?: return@mapNotNull null
            val typeMultiplier = each.knownTypeIds.takeIf { it.isNotEmpty() }
                ?.let { publicTypeMultiplier(details, each) }
            val projection =
                standardDamageProjection(candidate, details, actor, each, stab, typeMultiplier, spreadMultiplier)
            BattleSpreadTargetFactsView(
                side = each.side,
                slot = slot,
                typeChartMultiplier = typeMultiplier,
                standardDamageFractionRange = projection?.damageFractionRange,
                standardDamageRollKoProbabilityRange = projection?.koProbabilityRange,
                standardKnockoutAssessment = projection?.knockoutAssessment,
            )
        }
    }

    private fun standardDamageProjection(
        candidate: BattleActionCandidate,
        details: BattleMoveCandidateView,
        actor: BattlePokemonStateView?,
        target: BattlePokemonStateView?,
        stab: Double?,
        typeMultiplier: Double?,
        spreadMultiplier: Double = 1.0,
    ): ShowdownStandardDamageProjectionResult? {
        if (details.damageCategory == BattleMoveDamageCategory.STATUS || isDelayedSlotDamage(details)) return null
        if (details.targetPattern !in DAMAGE_TARGET_PATTERNS) return null
        val level = actor?.level ?: return null
        // A mechanic candidate used to project nothing at all - not a rough number, nothing - which put
        // every Mega, Tera and Dynamax option into the ranking as an attack that deals no damage. Every
        // battle tower set carries one, so the whole feature sat unusable behind a single condition.
        //
        // What the mechanic does to the *move* already arrives resolved: a Max move is described as
        // itself. What was missing is the actor using it - the Tera type, the doubled health, the Mega
        // spread.
        //
        // Mega was the last one left, on the reasoning that its spread lives behind another mod. That
        // reasoning was wrong about where the data is. Every battle form a species has is already
        // published on the Pokemon as `knownFormStates` - exact for one's own party, public species
        // ranges for the opponent - so the form is read rather than invented. A species with two Megas
        // resolves only when the held stone names which one.
        //
        // A mechanic that still resolves to neither types nor stats projects nothing, which is the rule
        // that was here before and remains the right one: a wrong number is worse than an absent one,
        // because the ranking believes it.
        val mechanic = candidate.mechanic
        val mechanicStats = LocalMechanicFormResolution.transformedStats(candidate, actor)
        if (mechanic != null &&
            LocalMechanicFormResolution.transformedTypeIds(candidate, actor).isEmpty() &&
            mechanicStats == null
        ) {
            return null
        }
        val actorStats = mechanicStats ?: actor.combatStats ?: return null
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
            attack = LocalKnownStatMechanics.attack(
                publicStatusModifiedAttack(stagedAttack, details.damageCategory, actor),
                details.damageCategory,
                actor,
            ),
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
            // The reduction is a damage step, not a type-chart fact. The published
            // `typeChartMultiplier` must stay the plain effectiveness against that Pokemon.
            spreadMultiplier = spreadMultiplier,
            // Life Orb and Expert Belt scale the finished damage rather than a stat, so they arrive
            // here alongside the spread reduction instead of inside the attack range.
            itemDamageMultiplier = LocalKnownStatMechanics.damageMultiplier(actor, knownTypeMultiplier),
        )
    }

    /**
     * The same-type bonus, including what Terastallization does to it.
     *
     * Tera is the one mechanic that changes the attacker rather than the attack, and it does not simply
     * swap the type: the user keeps the bonus on its original types and gains one on the Tera type, and
     * a move matching both is doubled rather than raised once. Reading only the pre-Tera types would
     * price a Tera attack as an ordinary one and reading only the Tera type would throw away the
     * bonus the user still has.
     */
    private fun sameTypeAttackBonus(
        details: BattleMoveCandidateView,
        actor: BattlePokemonStateView?,
        candidate: BattleActionCandidate,
    ): Double? {
        val original = actor?.knownTypeIds?.takeIf { it.isNotEmpty() } ?: return null
        val transformed = LocalMechanicFormResolution.transformedTypeIds(candidate, actor)
        fun matches(types: Collection<String>) = types.any { it.equals(details.typeId, ignoreCase = true) }
        // A Mega swaps the typing rather than adding to it, so the doubling below - which exists for
        // Tera keeping both - must not reach it.
        if (transformed.isNotEmpty() && LocalMechanicFormResolution.replacesOriginalTypes(candidate, actor)) {
            return if (matches(transformed)) 1.5 else 1.0
        }
        val matchesOriginal = matches(original)
        val matchesTransformed = transformed.isNotEmpty() && matches(transformed)
        return when {
            matchesOriginal && matchesTransformed -> 2.0
            matchesOriginal || matchesTransformed -> 1.5
            else -> 1.0
        }
    }

    private fun publicTypeMultiplier(
        details: BattleMoveCandidateView,
        target: BattlePokemonStateView?,
        context: BattleDecisionContext? = null,
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
            defenderAbilityId = target.knownAbilityId
                ?: context?.let { blockingOrdinaryAbility(details.typeId, target, it) },
            ignoreTypeImmunity = ignoresImmunity,
        )
    }

    /**
     * An immunity every ordinary member of the species has, treated as the fact it is.
     *
     * The chart honoured a defensive ability only once the battle had revealed it, which left the
     * damage facts - the numbers the ranking is built on - reporting a clean hit into an absorber the
     * species always has. Sap Sipper worked because it had been revealed; Earth Eater did not, so
     * trainers threw Earthquake into an Orthworm and healed it, turn after turn.
     *
     * This does not guess at the individual. The species' ability pool is public data, and when every
     * ordinary entry in it grants the same immunity there is nothing left to guess: the hidden ability
     * is the exception, not the expectation, and no player treats it as one. A pool with two ordinary
     * abilities where only one absorbs stays unresolved, because there the doubt is real.
     */
    private fun blockingOrdinaryAbility(
        moveTypeId: String,
        target: BattlePokemonStateView,
        context: BattleDecisionContext,
    ): String? {
        val ordinary = context.state.inferences.asSequence()
            .filter { it.subjectPokemonId == target.battlePokemonId && it.categoryId == ABILITY_CATEGORY }
            .filter { it.confidence != BattleInferenceConfidence.RULED_OUT }
            .filter { it.abilityAvailability != BattleAbilityAvailability.HIDDEN }
            .mapNotNull(BattleInferenceView::candidateId)
            .distinct()
            .toList()
        if (ordinary.isEmpty()) return null
        val neutral = StandardTypeEffectiveness.multiplier(moveTypeId, target.knownTypeIds)
        val allBlock = ordinary.all { ability ->
            StandardTypeEffectiveness.multiplierAgainst(
                attackingTypeId = moveTypeId,
                defendingTypeIds = target.knownTypeIds,
                defenderAbilityId = ability,
            ) < neutral
        }
        return ordinary.first().takeIf { allBlock }
    }

    private const val ABILITY_CATEGORY = "ability"

    private fun isDelayedSlotDamage(details: BattleMoveCandidateView): Boolean =
        details.effects?.effects.orEmpty().any {
            it.kind == BattleMoveEffectKind.SLOT_CONDITION && canonical(it.valueId) == "futuremove"
        }

    /** Patterns the engine resolves itself, striking every opposing active slot at once. */
    private val SPREAD_OPPONENT_PATTERNS = setOf(
        BattleMoveTargetPattern.ALL_OPPONENTS,
        BattleMoveTargetPattern.ALL_ADJACENT,
        BattleMoveTargetPattern.ALL_ACTIVE,
    )

    /** Gen 9 reduces a spread move to 0.75x when it actually lands on more than one target. */
    private const val SPREAD_DAMAGE_MULTIPLIER = 0.75

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
    ): BattlePokemonStateView? = resolvedTargets(candidate, context, actingSide).firstOrNull()

    /**
     * Every opposing slot this move hits, in active-slot order.
     *
     * A spread move never carries an explicit target: the engine offers no target choice for it, so
     * the adapter builds one candidate with an empty target list. Resolving that by taking the single
     * active opponent worked in singles and returned nothing in doubles, which skipped the damage
     * projection entirely and dropped every spread move onto the coarse power-based fallback. The
     * move's own target pattern is the fact that was being ignored.
     *
     * Only opposing slots are resolved. `ALL_ADJACENT` and `ALL_ACTIVE` also strike the ally, but that
     * is priced separately as collateral rather than as pressure, and folding it in here would put
     * damage dealt to one's own side into a field that means damage dealt to the opponent's.
     */
    private fun resolvedTargets(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        actingSide: BattleSide,
    ): List<BattlePokemonStateView> {
        val explicitTarget = candidate.targets.singleOrNull()
        if (explicitTarget != null) {
            val declared = active(context, explicitTarget.side, explicitTarget.slot)
            return listOfNotNull(declared?.let { redirectedAwayFrom(it, candidate, context) ?: it })
        }
        val targetSide = if (actingSide == BattleSide.ALLY) BattleSide.OPPONENT else BattleSide.ALLY
        val activeOpponents = context.state.pokemon
            .filter { it.side == targetSide && it.activeSlot != null && !it.fainted }
            .sortedBy { it.activeSlot }
        val pattern = candidate.moveDetails?.targetPattern
        if (pattern in SPREAD_OPPONENT_PATTERNS) return activeOpponents
        // Without an explicit target and without a spread pattern the defender is only determined when
        // exactly one opponent is on the field; guessing between two would invent a fact.
        return listOfNotNull(activeOpponents.singleOrNull())
    }


    /**
     * The slot a publicly known ability pulls this move into instead, or nothing.
     *
     * Lightning Rod and Storm Drain were modelled only as absorbers standing on their own slot, which
     * is the whole of what they do in singles and half of what they do in doubles. The other half is
     * that they take the move away from the partner: an Electric attack aimed at the slot beside a
     * Lightning Rod does not hit that slot at all.
     *
     * Missing it is not a small mispricing. The AI projected full damage on the declared target,
     * spent the turn, dealt nothing, and handed the opponent a free Special Attack stage - and would
     * do it again next turn, because nothing about the failure was in the public state it reads. The
     * fix belongs here rather than in the damage step, because the question being got wrong is "who
     * does this move hit", and this is the one function that answers it. Everything downstream - the
     * type chart, the absorbing-ability check, the knockout - then reads the right defender without
     * knowing redirection exists.
     *
     * The same public standard as every other ability reading: revealed, or the only ordinary ability
     * the species has. Ability-ignoring attackers are deliberately not exempted. Mold Breaker stops
     * the absorption, not the redirection, so such a move still gets pulled aside and then lands -
     * which is exactly what falls out of resolving the target here and letting the kernel decide the
     * rest.
     */
    private fun redirectedAwayFrom(
        declared: BattlePokemonStateView,
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
    ): BattlePokemonStateView? {
        if (context.state.format != BattleFormat.DOUBLE) return null
        if (candidate.moveDetails?.targetPattern !in REDIRECTABLE_TARGET_PATTERNS) return null
        val moveType = canonical(candidate.moveDetails?.typeId) ?: return null
        return context.state.pokemon.firstOrNull { other ->
            other.side == declared.side &&
                other.activeSlot != null &&
                other.battlePokemonId != declared.battlePokemonId &&
                !other.fainted &&
                other.hpFraction > 0.0 &&
                redirectsPublicly(other, context, moveType)
        }
    }

    private fun redirectsPublicly(
        pokemon: BattlePokemonStateView,
        context: BattleDecisionContext,
        moveType: String,
    ): Boolean {
        val revealed = canonical(pokemon.knownAbilityId)
        if (revealed != null) return REDIRECTING_ABILITIES[revealed] == moveType
        val ordinary = context.state.inferences.asSequence()
            .filter { it.subjectPokemonId == pokemon.battlePokemonId && it.categoryId == ABILITY_CATEGORY }
            .filter { it.confidence != BattleInferenceConfidence.RULED_OUT }
            .filter { it.abilityAvailability != BattleAbilityAvailability.HIDDEN }
            .mapNotNull { canonical(it.candidateId) }
            .distinct()
            .toList()
        return ordinary.isNotEmpty() && ordinary.all { REDIRECTING_ABILITIES[it] == moveType }
    }

    /** The abilities that take a single-target move of their type away from the slot beside them. */
    private val REDIRECTING_ABILITIES = mapOf(
        "lightningrod" to "electric",
        "stormdrain" to "water",
    )

    /** Only a move that picks one slot can be pulled to a different one. */
    private val REDIRECTABLE_TARGET_PATTERNS = setOf(
        BattleMoveTargetPattern.SELECTED,
        BattleMoveTargetPattern.SELECTED_OPPONENT,
    )

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
