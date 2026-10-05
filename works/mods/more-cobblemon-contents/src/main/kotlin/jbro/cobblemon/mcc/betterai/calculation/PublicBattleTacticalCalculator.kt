package jbro.cobblemon.mcc.betterai.calculation

import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.internal.ai.BattleInferenceConfidence
import jbro.cobblemon.mcc.internal.ai.BattleInferenceView
import jbro.cobblemon.mcc.betterai.mechanics.LocalDeclaredMultiHit
import jbro.cobblemon.mcc.betterai.mechanics.LocalCriticalHitRules
import jbro.cobblemon.mcc.betterai.mechanics.LocalPersistentMoveState
import jbro.cobblemon.mcc.betterai.mechanics.LocalFullHealthSurvivalRules
import jbro.cobblemon.mcc.betterai.mechanics.LocalKnownStatMechanics
import jbro.cobblemon.mcc.betterai.mechanics.LocalMechanicFormResolution
import jbro.cobblemon.mcc.betterai.mechanics.LocalMechanicActivationProjector
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicAbilityMechanics
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicAbilityState
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicMechanicsKernel
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicMoveDamageInputs
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicMoveTargets
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicFieldMechanics
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicItemState
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicStatusImmunity
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicStab
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicTurnOrder
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicMoveProjection
import jbro.cobblemon.mcc.betterai.mechanics.PublicSwitchEntryHazardCalculator
import jbro.cobblemon.mcc.betterai.mechanics.ShowdownStandardDamageProjection
import jbro.cobblemon.mcc.betterai.mechanics.ShowdownStandardDamageProjectionResult
import jbro.cobblemon.mcc.betterai.mechanics.StandardTypeEffectiveness

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
        /**
         * True only for the live board a decision is taken from. Its observed turn order then conditions
         * [BattleCandidateFactsView.actsFirstProbability]; a projected board must not, because it carries
         * the same inferences under a speed context they were not observed in.
         */
        observedBoard: Boolean = false,
    ): BattleDecisionContext {
        if (context.candidates.all(::fullyCalculated)) return context
        val observed = context.state.takeIf { observedBoard }
        return context.copy(
            candidates = context.candidates.map { calculateCandidate(it, context, actingSide, observed) },
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
        val moveContext = LocalMechanicActivationProjector.forMegaCandidate(context, actingSide, candidate)
        return conservativeDamageRollFractionsAfterActivation(candidate, moveContext, actingSide)
    }

    private fun conservativeDamageRollFractionsAfterActivation(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        actingSide: BattleSide,
    ): List<Double>? {
        val resolvedCandidate = resolveDynamicMove(candidate, context, actingSide)
        val details = resolvedCandidate.moveDetails ?: return null
        val actor = resolvedCandidate.actorSlot?.let { slot -> active(context, actingSide, slot) }
        val targets = LocalPublicMoveTargets.resolve(resolvedCandidate, context, actingSide)
        // These rolls feed the search, which applies them to one defender. The search projects a spread
        // move one target at a time, each hit tagged so the reduction still applies.
        val target = targets.firstOrNull()
        val spreadMultiplier = LocalPublicMoveTargets.spreadMultiplier(resolvedCandidate, context, actingSide)
        val stab = sameTypeAttackBonus(details, actor, resolvedCandidate)
        val typeMultiplier = publicTypeMultiplier(resolvedCandidate, target, context, actingSide)
        val mechanics = LocalPublicMechanicsKernel.projectMove(resolvedCandidate, context, actingSide)
        declaredDamageRollFractions(resolvedCandidate, actor, target, mechanics, context.state)?.let { return it }
        val projection =
            standardDamageProjection(
                resolvedCandidate,
                details,
                actor,
                target,
                stab,
                typeMultiplier,
                context.state,
                spreadMultiplier,
            )
                ?: return null
        val maxHp = target?.combatStats?.maxHp ?: return null
        val (rolls, denominator) = if (actingSide == BattleSide.ALLY) {
            projection.minimumHypothesisRolls to maxHp.maximum
        } else {
            projection.maximumHypothesisRolls to maxHp.minimum
        }
        val hitCount = if (LocalDeclaredMultiHit.usesPerHitAccuracy(resolvedCandidate)) 1 else {
            LocalDeclaredMultiHit.representativeCount(resolvedCandidate, actor, context.state)
        }
        return rolls.map { damage ->
            val raw = damage.toDouble() / denominator * hitCount * mechanics.knownDamageMultiplier
            val decoy = target.knownVolatileEffectIds.any { PublicIds.canonical(it) == "substitute" } &&
                "sound" !in details.effects?.mechanicFlags.orEmpty() && LocalPublicAbilityState.effectiveKnownAbility(context.state, actor) != "infiltrator"
            // The aggregate must include hits after a decoy breaks; the hit sequence applies the actual HP cap.
            if (decoy && hitCount > 1) raw.coerceAtLeast(0.0) else raw
                .coerceAtMost(if (decoy) {
                    maxOf(target.hpFraction, LocalPersistentMoveState.substituteRange(target)?.endInclusive ?: 0.25)
                } else target.hpFraction)
                .coerceIn(0.0, 1.0)
        }
    }

    /**
     * Damage rolls of an own spread move against its own partner, as fractions of the partner's maximum HP
     * and not capped at its remaining HP, so the caller can tell a knockout. Both Pokemon belong to the
     * deciding trainer, so the stats are exact and the full damage formula applies (abilities such as Guts,
     * the partner's types and a revealed absorbing ability included).
     */
    fun partnerDamageRollFractions(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        partner: BattlePokemonStateView,
        spreadMultiplier: Double,
    ): List<Double>? {
        val moveContext = LocalMechanicActivationProjector.forMegaCandidate(context, BattleSide.ALLY, candidate)
        return partnerDamageRollFractionsAfterActivation(candidate, moveContext, partner, spreadMultiplier)
    }

    private fun partnerDamageRollFractionsAfterActivation(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        partner: BattlePokemonStateView,
        spreadMultiplier: Double,
    ): List<Double>? {
        val resolvedCandidate = resolveDynamicMove(candidate, context, BattleSide.ALLY)
        val details = resolvedCandidate.moveDetails ?: return null
        val actor = resolvedCandidate.actorSlot?.let { slot -> active(context, BattleSide.ALLY, slot) } ?: return null
        val projection = standardDamageProjection(
            resolvedCandidate,
            details,
            actor,
            partner,
            sameTypeAttackBonus(details, actor, resolvedCandidate),
            publicTypeMultiplier(resolvedCandidate, partner, context, BattleSide.ALLY),
            context.state,
            spreadMultiplier,
        ) ?: return null
        val maxHp = partner.combatStats?.maxHp ?: return null
        val hitCount = if (LocalDeclaredMultiHit.usesPerHitAccuracy(resolvedCandidate)) 1 else {
            LocalDeclaredMultiHit.representativeCount(resolvedCandidate, actor, context.state)
        }
        return projection.maximumHypothesisRolls.map { damage -> damage.toDouble() / maxHp.minimum * hitCount }
    }

    private fun declaredDamageRollFractions(
        candidate: BattleActionCandidate,
        actor: BattlePokemonStateView?,
        target: BattlePokemonStateView?,
        mechanics: LocalPublicMoveProjection,
        state: BattleStateView,
    ): List<Double>? {
        val effects = candidate.moveDetails?.effects?.effects.orEmpty()
        val targetHp = target?.hpFraction ?: return null
        if (mechanics.publiclyNullified) return listOf(0.0)
        effects.firstOrNull { it.kind == BattleMoveEffectKind.ONE_HIT_KO }?.let {
            val actorLevel = actor?.level
            val targetLevel = target.level
            val levelBlocked = actorLevel != null && targetLevel != null && targetLevel > actorLevel
            val sturdyBlocked = LocalPublicAbilityState.effectiveKnownAbility(state, target) == "sturdy"
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
        // Damage set by the HP on the board: half the target's (Super Fang, Ruination, Nature's Madness), the gap
        // down to the user's (Endeavor), the user's own (Final Gambit). All public.
        when (PublicIds.canonical(candidate.moveId.orEmpty())) {
            "superfang", "ruination", "naturesmadness" -> return listOf((targetHp / 2.0).coerceAtLeast(1.0 / maxHp.maximum))
            "endeavor" -> {
                val actorHp = actor?.combatStats?.maxHp?.let { it.minimum * actor.hpFraction } ?: return null
                val gap = (targetHp * maxHp.minimum - actorHp).coerceAtLeast(0.0)
                return listOf((gap / maxHp.minimum).coerceAtMost(targetHp))
            }
            "finalgambit" -> {
                val actorHp = actor?.combatStats?.maxHp?.let { it.minimum * actor.hpFraction } ?: return null
                return listOf((actorHp / maxHp.maximum).coerceAtMost(targetHp), (actorHp / maxHp.minimum).coerceAtMost(targetHp))
            }
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
        observed: BattleStateView?,
    ): BattleActionCandidate {
        val moveContext = LocalMechanicActivationProjector.forMegaCandidate(context, actingSide, candidate)
        if (candidate.kind == BattleActionKind.COMPOSITE) {
            val components = candidate.componentActions.map { calculateCandidate(it, moveContext, actingSide, observed) }
            return candidate.copyWith(componentActions = components)
        }
        if (candidate.facts != null) return candidate
        val resolvedCandidate = resolveDynamicMove(candidate, moveContext, actingSide)
        return resolvedCandidate.copyWith(facts = facts(resolvedCandidate, moveContext, actingSide, observed))
    }

    private fun facts(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        actingSide: BattleSide,
        observed: BattleStateView? = null,
    ): BattleCandidateFactsView {
        val details = candidate.moveDetails
        if (candidate.kind == BattleActionKind.SWITCH) {
            val target = candidate.switchPokemonId?.let { targetId ->
                context.state.pokemon.firstOrNull {
                    it.battlePokemonId == targetId && it.side == actingSide && !it.fainted
                }
            }
            val entryHpLoss = target?.let {
                PublicSwitchEntryHazardCalculator.hpLoss(context.state, actingSide, it)
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
        val targets = LocalPublicMoveTargets.resolve(candidate, context, actingSide)
        val target = targets.firstOrNull()
        val publiclyNullified = LocalPublicMechanicsKernel.projectMove(candidate, context, actingSide).publiclyNullified
        val spreadMultiplier = LocalPublicMoveTargets.spreadMultiplier(candidate, context, actingSide)
        val typeMultiplier = target?.knownTypeIds?.takeIf { it.isNotEmpty() }?.let {
            basis += BattleCalculationBasis.PUBLIC_TYPES
            publicTypeMultiplier(candidate, target, context, actingSide)
        }
        if (details.damageCategory != BattleMoveDamageCategory.STATUS) {
            unknowns += BattleCalculationUnknown.DYNAMIC_DAMAGE_MODIFIERS
            if (typeMultiplier == null) unknowns += BattleCalculationUnknown.TARGET_TYPES
        }
        val rawProjection =
            standardDamageProjection(candidate, details, actor, target, stab, typeMultiplier, context.state, spreadMultiplier)
        // A Focus Sash or Sturdy at full health turns a knockout the rolls call guaranteed into a
        // survivor on one health. The projector has always known that; the facts the ranking is built
        // from did not, so the layer that decides was the one working from the wrong premise. A move
        // that strikes more than once is unaffected - the second hit goes through whatever held the
        // first.
        val survivesOneHit = target != null &&
            LocalDeclaredMultiHit.maximumCount(candidate) <= 1 &&
            LocalFullHealthSurvivalRules.survivesAnySingleHit(
                context.state,
                target,
                ignoreTargetAbility = LocalPublicAbilityMechanics.ignoresTargetAbility(
                    candidate,
                    actor,
                    target,
                    context.state,
                ),
            )
        val projection = if (survivesOneHit) rawProjection?.withoutKnockout(target) else rawProjection
        // Fixed-damage moves (Seismic Toss, Super Fang, Endeavor, Final Gambit) have no formula projection; the
        // ranking reads their declared damage, which the search already used.
        val fixedDamage = details.effects?.effects.orEmpty().any {
            it.kind == BattleMoveEffectKind.ONE_HIT_KO || it.kind == BattleMoveEffectKind.FIXED_DAMAGE_LEVEL ||
                it.kind == BattleMoveEffectKind.FIXED_DAMAGE_VALUE
        } || PublicIds.canonical(candidate.moveId.orEmpty()) in HP_SET_DAMAGE_MOVES
        val declaredRolls = if (projection == null && fixedDamage && details.damageCategory != BattleMoveDamageCategory.STATUS) {
            declaredDamageRollFractions(candidate, actor, target,
                LocalPublicMechanicsKernel.projectMove(candidate, context, actingSide), context.state)
        } else null
        val declaredRange = declaredRolls?.takeIf { it.isNotEmpty() }?.let { BattleDamageFractionRange(it.min(), it.max()) }
        val declaredKnockout = declaredRolls?.takeIf { it.isNotEmpty() && target != null }?.let { rolls ->
            val knockouts = rolls.count { it >= target!!.hpFraction - 1e-9 }
            when (knockouts) {
                rolls.size -> BattleKnockoutAssessment.GUARANTEED
                0 -> BattleKnockoutAssessment.IMPOSSIBLE
                else -> BattleKnockoutAssessment.POSSIBLE
            } to knockouts.toDouble() / rolls.size
        }
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
                actorAction = candidate,
                opponentPriority = 0,
            )?.let { prior ->
                observed?.let { LocalPublicTurnOrder.observedOrderAgainstActiveOpponent(it, context.state, actingSide, candidate, prior) } ?: prior
            },
            standardDamageModel = (projection ?: declaredRange)?.let { BattleStandardDamageModel.SHOWDOWN_GEN9_BASE_NON_CRITICAL },
            standardDamageFractionRange = projection?.damageFractionRange ?: declaredRange,
            standardDamageRollKoProbabilityRange = projection?.koProbabilityRange
                ?: declaredKnockout?.second?.let { BattleFractionRange(it, it) },
            standardKnockoutAssessment = projection?.knockoutAssessment ?: declaredKnockout?.first,
            selfHealingFractionRange = declaredHeal?.fractionRange,
            // The projector has always refused a status the target cannot take; the facts the root
            // ranking is built from did not, so Toxic into a Steel type was priced as a normal play
            // and only the search knew better. Same module, same answer, one place.
            statusEffectProbability = declaredStatus
                ?.takeIf {
                    !publiclyNullified && (target == null || !LocalPublicStatusImmunity.blocked(context.state, target, it.valueId))
                }
                ?.probability?.times(details.accuracy / 100.0),
            calculationCoverage = BattleCalculationCoverage.PARTIAL,
            unknowns = unknowns,
            basis = basis,
            spreadTargets = spreadTargetFacts(candidate, targets, typeMultiplier, projection, context, actingSide),
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
        targets: List<BattlePokemonStateView>,
        primaryTypeMultiplier: Double?,
        primaryProjection: ShowdownStandardDamageProjectionResult?,
        context: BattleDecisionContext,
        actingSide: BattleSide,
    ): List<BattleSpreadTargetFactsView> {
        if (targets.size < 2) return emptyList()
        return targets.mapIndexedNotNull { index, each ->
            val slot = each.activeSlot ?: return@mapIndexedNotNull null
            if (index == 0) {
                return@mapIndexedNotNull BattleSpreadTargetFactsView(
                    side = each.side,
                    slot = slot,
                    typeChartMultiplier = primaryTypeMultiplier,
                    standardDamageFractionRange = primaryProjection?.damageFractionRange,
                    standardDamageRollKoProbabilityRange = primaryProjection?.koProbabilityRange,
                    standardKnockoutAssessment = primaryProjection?.knockoutAssessment,
                )
            }
            // Each extra target is its own hit: its revealed ability, a Focus Sash or Sturdy at full health
            // and a public immunity apply to it exactly as they would to a primary target.
            val hit = LocalPublicMoveTargets.spreadHitOn(candidate, each, "${candidate.actionId}:spread:${each.side}:$slot")
            val facts = facts(hit, context, actingSide)
            val nullified = LocalPublicMechanicsKernel.projectMove(hit, context, actingSide).publiclyNullified
            BattleSpreadTargetFactsView(
                side = each.side,
                slot = slot,
                typeChartMultiplier = if (nullified) 0.0 else facts.typeChartMultiplier,
                standardDamageFractionRange = if (nullified) BattleDamageFractionRange(0.0, 0.0) else facts.standardDamageFractionRange,
                standardDamageRollKoProbabilityRange = if (nullified) BattleFractionRange(0.0, 0.0) else facts.standardDamageRollKoProbabilityRange,
                standardKnockoutAssessment = if (nullified) BattleKnockoutAssessment.IMPOSSIBLE else facts.standardKnockoutAssessment,
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
        state: BattleStateView,
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
        val moveInputs = LocalPublicMoveDamageInputs.resolve(
            candidate,
            actor,
            target,
            state,
        ) ?: return null
        val effectivePower = LocalKnownStatMechanics.effectivePower(moveInputs.powers, actor, state, candidate)
        val knownStab = stab ?: return null
        val knownTypeMultiplier = typeMultiplier ?: return null
        val offensiveStats = if (moveInputs.offensivePokemon.battlePokemonId == actor.battlePokemonId) {
            actorStats
        } else {
            targetStats
        }
        val attack = when (moveInputs.offensiveStat) {
            LocalPublicMoveDamageInputs.CombatStat.ATTACK ->
                offensiveStats.attack
            LocalPublicMoveDamageInputs.CombatStat.DEFENCE ->
                offensiveStats.defence
            LocalPublicMoveDamageInputs.CombatStat.SPECIAL_ATTACK ->
                offensiveStats.specialAttack
            LocalPublicMoveDamageInputs.CombatStat.SPECIAL_DEFENCE ->
                offensiveStats.specialDefence
        }
        val defence = when (moveInputs.defensiveStat) {
            LocalPublicMoveDamageInputs.CombatStat.DEFENCE -> targetStats.defence
            LocalPublicMoveDamageInputs.CombatStat.SPECIAL_DEFENCE -> targetStats.specialDefence
            else -> return null
        }
        val effects = details.effects?.effects.orEmpty()
        val guaranteedCritical = LocalCriticalHitRules.confirmed(candidate, actor, target, state)
        val stealsStages = effects.any { it.kind == BattleMoveEffectKind.STEALS_STAT_STAGES }
        val attackStage = moveInputs.offensiveStage
        val defenceStage = moveInputs.defensiveStage
        val actorAbility = LocalPublicAbilityState.effectiveKnownAbility(state, actor)
        val targetAbility = LocalPublicAbilityState.effectiveKnownAbility(state, target)
        val ignoresTargetAbility = LocalPublicAbilityMechanics.ignoresTargetAbility(candidate, actor, target, state)
        val defenderUnaware = targetAbility == UNAWARE && !ignoresTargetAbility
        val attackerUnaware = actorAbility == UNAWARE
        val stolenAttackStage = if (stealsStages) {
            when (details.damageCategory) {
                BattleMoveDamageCategory.PHYSICAL -> target.stage("attack", "atk").coerceAtLeast(0)
                BattleMoveDamageCategory.SPECIAL -> target.stage("special_attack", "specialattack", "spa").coerceAtLeast(0)
                BattleMoveDamageCategory.STATUS -> 0
            }
        } else {
            0
        }
        val combinedAttackStage = (attackStage + stolenAttackStage).coerceIn(-6, 6)
        val defenderIgnoresOffensiveStage = defenderUnaware &&
            moveInputs.offensivePokemon.battlePokemonId == actor.battlePokemonId &&
            moveInputs.offensiveStat in UNAWARE_IGNORED_OFFENSIVE_STATS
        val effectiveAttackStage = when {
            defenderIgnoresOffensiveStage -> 0
            guaranteedCritical && combinedAttackStage < 0 -> 0
            else -> combinedAttackStage
        }
        val ignoresHelpfulDefensiveStages = guaranteedCritical ||
            effects.any { it.kind == BattleMoveEffectKind.IGNORE_DEFENSIVE_STAGES }
        val effectiveDefenceStage = when {
            attackerUnaware -> 0
            ignoresHelpfulDefensiveStages && defenceStage > 0 -> 0
            else -> defenceStage
        }
        val stagedAttack = applyStage(attack, effectiveAttackStage)
        return ShowdownStandardDamageProjection.project(
            level = level,
            power = effectivePower,
            attack = publicOffensiveStat(stagedAttack, candidate.moveId, details, actor, moveInputs, state),
            defence = LocalKnownStatMechanics.defence(
                applyStage(defence, effectiveDefenceStage),
                moveInputs.defensiveStat,
                target,
                state,
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
            itemDamageMultiplier = LocalKnownStatMechanics.damageMultiplier(actor, knownTypeMultiplier, state),
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
    ): Double? = LocalPublicStab.multiplier(candidate, actor, details.typeId)

    private fun publicTypeMultiplier(
        candidate: BattleActionCandidate,
        target: BattlePokemonStateView?,
        context: BattleDecisionContext,
        actingSide: BattleSide,
    ): Double? {
        val details = candidate.moveDetails ?: return null
        val types = target?.knownTypeIds?.takeIf { it.isNotEmpty() } ?: return null
        val actor = candidate.actorSlot?.let { active(context, actingSide, it) }
        val ignoresAbility = LocalPublicAbilityMechanics.ignoresTargetAbility(candidate, actor, target, context.state)
        val ignoresImmunity = details.effects?.effects.orEmpty().any {
            it.kind == BattleMoveEffectKind.IGNORE_TYPE_IMMUNITY
        }
        // A revealed defensive ability is public information, so the fair chart must honour it.
        // Without this the projection reports a clean hit for Ground into a revealed Levitate.
        return StandardTypeEffectiveness.multiplierAgainst(
            attackingTypeId = details.typeId,
            defendingTypeIds = types,
            defenderAbilityId = LocalPublicAbilityState.effectiveKnownAbility(context.state, target)
                ?: blockingPossibleAbility(details.typeId, target, context),
            ignoreTypeImmunity = ignoresImmunity,
            applyAbilities = !ignoresAbility,
            moveId = candidate.moveId,
        )
    }

    /** Do not turn a possible immunity into a fact by discarding hidden ability candidates. */
    private fun blockingPossibleAbility(
        moveTypeId: String,
        target: BattlePokemonStateView,
        context: BattleDecisionContext,
    ): String? {
        if (!LocalPublicAbilityState.isActive(context.state, target, "levitate")) return null
        val possible = context.state.inferences.asSequence()
            .filter { it.subjectPokemonId == target.battlePokemonId && it.categoryId == ABILITY_CATEGORY }
            .filter { it.confidence != BattleInferenceConfidence.RULED_OUT }
            .mapNotNull(BattleInferenceView::candidateId)
            .distinct()
            .toList()
        if (possible.isEmpty()) return null
        val neutral = StandardTypeEffectiveness.multiplier(moveTypeId, target.knownTypeIds)
        val allBlock = possible.all { ability ->
            StandardTypeEffectiveness.multiplierAgainst(
                attackingTypeId = moveTypeId,
                defendingTypeIds = target.knownTypeIds,
                defenderAbilityId = ability,
            ) < neutral
        }
        return possible.first().takeIf { allBlock }
    }

    private const val ABILITY_CATEGORY = "ability"

    private fun isDelayedSlotDamage(details: BattleMoveCandidateView): Boolean =
        details.effects?.effects.orEmpty().any {
            it.kind == BattleMoveEffectKind.SLOT_CONDITION && canonical(it.valueId) == "futuremove"
        }

    /** Gen 9 reduces a spread move to 0.75x when it actually lands on more than one target. */
    private const val SPREAD_DAMAGE_MULTIPLIER = 0.75
    private val HP_SET_DAMAGE_MOVES = setOf("superfang", "ruination", "naturesmadness", "endeavor", "finalgambit")

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
        moveId: String?,
        category: BattleMoveDamageCategory,
        actor: BattlePokemonStateView,
        inputs: LocalPublicMoveDamageInputs.Resolution,
        state: BattleStateView,
    ): BattleIntegerRange {
        if (category != BattleMoveDamageCategory.PHYSICAL) return attack
        val status = canonical(actor.statusId)
        val ability = LocalPublicAbilityState.effectiveKnownAbility(state, actor)
        val usesActorsAttack = inputs.offensiveStat == LocalPublicMoveDamageInputs.CombatStat.ATTACK &&
            inputs.offensivePokemon.battlePokemonId == actor.battlePokemonId
        val multiplier = when {
            status != null && ability == "guts" && usesActorsAttack -> 1.5
            status != null && ability == "guts" -> 1.0
            canonical(moveId) == "facade" -> 1.0
            status in BURN_STATUS_IDS -> 0.5
            else -> 1.0
        }
        return BattleIntegerRange(
            minimum = (attack.minimum * multiplier).toInt().coerceAtLeast(1),
            maximum = (attack.maximum * multiplier).toInt().coerceAtLeast(1),
        )
    }

    private fun publicOffensiveStat(
        value: BattleIntegerRange,
        moveId: String?,
        details: BattleMoveCandidateView,
        actor: BattlePokemonStateView,
        inputs: LocalPublicMoveDamageInputs.Resolution,
        state: BattleStateView,
    ): BattleIntegerRange {
        val statusModified = publicStatusModifiedAttack(value, moveId, details.damageCategory, actor, inputs, state)
        if (inputs.offensiveStat in DEFENSIVE_OFFENSIVE_STATS) {
            return LocalKnownStatMechanics.offensiveDefence(
                statusModified,
                inputs.offensiveStat,
                inputs.offensivePokemon,
                state,
            )
        }
        return LocalKnownStatMechanics.attack(
            statusModified,
            details.damageCategory,
            inputs.offensivePokemon,
            state,
        )
    }

    private fun canonical(value: String?): String? = value?.let(PublicIds::canonical)

    private const val UNAWARE = "unaware"
    private val UNAWARE_IGNORED_OFFENSIVE_STATS = setOf(
        LocalPublicMoveDamageInputs.CombatStat.ATTACK,
        LocalPublicMoveDamageInputs.CombatStat.DEFENCE,
        LocalPublicMoveDamageInputs.CombatStat.SPECIAL_ATTACK,
    )

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
    private val DEFENSIVE_OFFENSIVE_STATS = setOf(
        LocalPublicMoveDamageInputs.CombatStat.DEFENCE,
        LocalPublicMoveDamageInputs.CombatStat.SPECIAL_DEFENCE,
    )

    /** HP of the same primary defender used by standardDamageFractionRange, including redirection. */
    fun primaryTargetHpFraction(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        actingSide: BattleSide,
    ): Double? = LocalPublicMoveTargets.resolve(
        resolveDynamicMove(candidate, context, actingSide),
        context,
        actingSide,
    ).firstOrNull()?.hpFraction

    private fun resolveDynamicMove(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        actingSide: BattleSide,
    ): BattleActionCandidate {
        val details = candidate.moveDetails ?: return candidate
        val actor = candidate.actorSlot?.let { active(context, actingSide, it) } ?: return candidate
        val typeId = LocalPublicMoveDamageInputs.resolvedTypeId(candidate, actor, context.state) ?: details.typeId
        val effects = details.effects?.let { resolveCallbackEffects(it, candidate, actor, context, actingSide) }
        if (typeId == details.typeId && effects === details.effects) return candidate
        return candidate.copyWith(moveDetails = details.copy(typeId = typeId, effects = effects))
    }

    /**
     * Effects Showdown works out in a move's callbacks, declared with a marker (BattleDeclarativeMoveEffects) and
     * settled here for this user on this board: the weather a Synthesis heals in, the Attack a Strength Sap drains,
     * the user's type that picks Curse's half, and the stage Belly Drum actually adds.
     */
    private fun resolveCallbackEffects(
        effects: BattleMoveEffectsView,
        candidate: BattleActionCandidate,
        actor: BattlePokemonStateView,
        context: BattleDecisionContext,
        actingSide: BattleSide,
    ): BattleMoveEffectsView {
        if (effects.effects.none { it.valueId in CALLBACK_MARKERS || it.kind == BattleMoveEffectKind.VOLATILE_STATUS }) return effects
        val ghost = actor.knownTypeIds.takeIf { it.isNotEmpty() }?.any { PublicIds.canonical(it) == "ghost" }
        var changed = false
        val resolved = effects.effects.mapNotNull { effect ->
            val next = when {
                effect.valueId == BattleDeclarativeMoveEffects.WEATHER_HEAL_SUN ->
                    healFraction(effect, sunHealFraction(actor, context))
                effect.valueId == BattleDeclarativeMoveEffects.WEATHER_HEAL_SAND ->
                    healFraction(effect, if (LocalPublicFieldMechanics.effectiveWeatherId(context.state) in SAND_WEATHER) 2.0 / 3.0 else 0.5)
                effect.valueId == BattleDeclarativeMoveEffects.HEAL_TARGET_ATTACK ->
                    strengthSapTarget(candidate, context, actingSide)
                        ?.let { target -> strengthSapFraction(actor, target) }
                        ?.let { healFraction(effect, it) }
                        ?: effect
                effect.valueId == BattleDeclarativeMoveEffects.NON_GHOST_CURSE -> effect.takeIf { ghost != true }
                effect.valueId == BattleDeclarativeMoveEffects.GHOST_CURSE -> effect.takeIf { ghost != false }
                effect.kind == BattleMoveEffectKind.VOLATILE_STATUS && PublicIds.canonical(effect.valueId.orEmpty()) == "curse" ->
                    effect.takeIf { ghost != false }
                effect.valueId == BattleDeclarativeMoveEffects.MAXIMISE_STAGE -> {
                    val stages = effect.statStages.mapValues { (stat, _) -> 6 - currentStage(actor, stat) }
                        .filterValues { it > 0 }
                    if (stages.isEmpty()) null else BattleMoveEffectView(effect.kind, effect.target, effect.probability,
                        effect.valueId, effect.fractionRange, effect.amountRange, stages)
                }
                else -> effect
            }
            if (next !== effect) changed = true
            next
        }
        if (!changed) return effects
        return BattleMoveEffectsView(effects.coverage, resolved, effects.scriptedBehavior, effects.requirements, effects.mechanicFlags)
    }

    /** Synthesis, Morning Sun and Moonlight: 2/3 in sun, 1/4 in any other weather, 1/2 without. */
    private fun sunHealFraction(actor: BattlePokemonStateView, context: BattleDecisionContext): Double {
        val weather = LocalPublicFieldMechanics.effectiveWeatherId(context.state)
        // A Utility Umbrella holder heals as if there were no sun or rain (Pokemon.effectiveWeather).
        val umbrella = LocalPublicItemState.activeItemId(context.state, actor) == "utilityumbrella"
        return when {
            weather in SUN_WEATHER -> if (umbrella) 0.5 else 2.0 / 3.0
            weather in RAIN_WEATHER -> if (umbrella) 0.5 else 0.25
            weather in SAND_WEATHER || weather in SNOW_WEATHER -> 0.25
            else -> 0.5
        }
    }

    private fun strengthSapTarget(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        actingSide: BattleSide,
    ): BattlePokemonStateView? {
        val explicit = candidate.targets.singleOrNull()
        val foe = if (actingSide == BattleSide.ALLY) BattleSide.OPPONENT else BattleSide.ALLY
        return if (explicit != null) {
            context.state.pokemon.firstOrNull { it.side == explicit.side && it.activeSlot == explicit.slot && !it.fainted }
        } else {
            context.state.pokemon.singleOrNull { it.side == foe && it.activeSlot != null && !it.fainted }
        }
    }

    /** Strength Sap heals by the target's Attack with its stage, as a fraction of the user's maximum HP. */
    private fun strengthSapFraction(actor: BattlePokemonStateView, target: BattlePokemonStateView): Double? {
        val attack = target.combatStats?.attack ?: return null
        val maxHp = actor.combatStats?.maxHp ?: return null
        val stage = currentStage(target, "atk")
        val multiplier = if (stage >= 0) (2.0 + stage) / 2.0 else 2.0 / (2.0 - stage)
        val midpointAttack = (attack.minimum + attack.maximum) / 2.0
        val midpointHp = (maxHp.minimum + maxHp.maximum) / 2.0
        if (midpointHp <= 0.0) return null
        return (kotlin.math.floor(midpointAttack * multiplier) / midpointHp).coerceIn(0.0, 1.0)
    }

    /** States carry a stage under its short or its long name, depending on what wrote it. */
    private fun currentStage(pokemon: BattlePokemonStateView, stat: String): Int {
        val names = STAGE_NAMES[PublicIds.canonical(stat)] ?: setOf(PublicIds.canonical(stat))
        return pokemon.statStages.entries.filter { PublicIds.canonical(it.key) in names }.sumOf { it.value }.coerceIn(-6, 6)
    }

    private val STAGE_NAMES = listOf(
        setOf("atk", "attack"), setOf("def", "defense", "defence"), setOf("spa", "specialattack"),
        setOf("spd", "specialdefense", "specialdefence"), setOf("spe", "speed"),
    ).flatMap { names -> names.map { it to names } }.toMap()

    private fun healFraction(effect: BattleMoveEffectView, fraction: Double): BattleMoveEffectView =
        BattleMoveEffectView(effect.kind, effect.target, effect.probability, effect.valueId,
            BattleFractionRange(fraction, fraction), effect.amountRange, effect.statStages)

    private val CALLBACK_MARKERS = setOf(
        BattleDeclarativeMoveEffects.WEATHER_HEAL_SUN, BattleDeclarativeMoveEffects.WEATHER_HEAL_SAND,
        BattleDeclarativeMoveEffects.HEAL_TARGET_ATTACK, BattleDeclarativeMoveEffects.NON_GHOST_CURSE,
        BattleDeclarativeMoveEffects.GHOST_CURSE, BattleDeclarativeMoveEffects.MAXIMISE_STAGE,
    )
    private val SUN_WEATHER = setOf("sun", "sunnyday", "harshsunlight", "desolateland")
    private val RAIN_WEATHER = setOf("rain", "raindance", "heavyrain", "primordialsea")
    private val SAND_WEATHER = setOf("sand", "sandstorm")
    private val SNOW_WEATHER = setOf("hail", "snow", "snowscape")


    private fun active(context: BattleDecisionContext, side: BattleSide, slot: Int): BattlePokemonStateView? =
        context.state.pokemon.firstOrNull {
            it.side == side && it.activeSlot == slot && !it.fainted
        }

    private fun BattleActionCandidate.copyWith(
        componentActions: List<BattleActionCandidate> = this.componentActions,
        moveDetails: BattleMoveCandidateView? = this.moveDetails,
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
