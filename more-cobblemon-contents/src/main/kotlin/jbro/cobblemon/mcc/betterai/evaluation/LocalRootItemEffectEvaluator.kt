package jbro.cobblemon.mcc.betterai.evaluation

import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.mechanics.*
import jbro.cobblemon.mcc.betterai.state.LocalEndTurnStateProjector
import jbro.cobblemon.mcc.internal.ai.*
import java.util.UUID

/**
 * Prices publicly known item transitions through the mechanics already modelled by the local path.
 * An item has no universal flat price: its holder's public attacks, order and current residuals
 * determine its value. Unrevealed items and attacks are not filled with a guessed hidden set.
 */
internal object LocalRootItemEffectEvaluator {
    internal data class Score(val total: Double = 0.0, val statStageUtility: Double = 0.0,
        /** The subset already re-priced by immediate material/status/order/stage projection. */
        val itemUtility: Double = 0.0)

    /** Null means this is not an item-swap move; zero means its known swap has no priced effect. */
    fun swapScore(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        accuracy: Double,
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
    ): Score? {
        if (PublicIds.canonical(candidate.moveId.orEmpty()) !in ITEM_SWAPS) return null
        val actor = actor(candidate, context) ?: return Score()
        val target = LocalPublicMoveTargets.resolve(candidate, context, BattleSide.ALLY).singleOrNull() ?: return Score()
        val ignoresAbility = LocalPublicAbilityMechanics.ignoresTargetAbility(candidate, actor, target, context.state)
        if (!LocalPublicItemTransferRules.canSwap(context.state, actor, target, ignoresAbility,
                LocalSubstituteRules.bypasses(candidate, actor, context.state)) ||
            LocalPublicMechanicsKernel.projectMove(candidate, context).publiclyNullified
        ) return Score()
        if (actor.canonicalKnownHeldItemId == target.canonicalKnownHeldItemId) return Score()
        val after = LocalPublicItemTransferRules.swap(context.state, actor, target)
        val value = itemTransitionValue(context.state, after, context, LocalProjectedActionCalculationCache(), tuning)
        return value.copy(total = value.total * accuracy.coerceIn(0.0, 1.0),
            itemUtility = value.itemUtility * accuracy.coerceIn(0.0, 1.0))
    }

    /** Known on-hit items are resolved after actual body damage, including survival and decoys. */
    fun damagingScore(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        accuracy: Double,
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
    ): Score = damagingScore(candidate, context, accuracy, tuning, null)

    private fun damagingScore(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        accuracy: Double,
        tuning: LocalDecisionTuning,
        targetIds: Set<UUID>?,
    ): Score {
        val details = candidate.moveDetails ?: return Score()
        if (details.damageCategory == BattleMoveDamageCategory.STATUS || accuracy <= 0.0) return Score()
        val actor = actor(candidate, context) ?: return Score()
        val moveId = PublicIds.canonical(candidate.moveId.orEmpty())
        val cache = LocalProjectedActionCalculationCache()
        var total = 0.0
        var stages = 0.0
        var projectedItems = 0.0
        LocalPublicMoveTargets.resolve(candidate, context, BattleSide.ALLY).forEachIndexed { index, target ->
            if (targetIds != null && target.battlePokemonId !in targetIds) return@forEachIndexed
            val item = LocalPublicItemState.activeItemId(context.state, target)
            if (moveId != "knockoff" && item !in HIT_REACTIVE_ITEMS) return@forEachIndexed
            if (moveId == "knockoff" && target.canonicalKnownHeldItemId == null && item !in HIT_REACTIVE_ITEMS) return@forEachIndexed
            val action = targetHit(candidate, target, index, context)
            val targeted = context.copy(candidates = listOf(action))
            val mechanics = LocalPublicMechanicsKernel.projectMove(action, targeted)
            if (mechanics.publiclyNullified) return@forEachIndexed
            val rolls = damageHypotheses(action, targeted, mechanics.knownDamageMultiplier)
            if (rolls.isEmpty()) return@forEachIndexed
            val ignoreAbility = LocalPublicAbilityMechanics.ignoresTargetAbility(action, actor, target, context.state)
            val bypassesSubstitute = LocalSubstituteRules.bypasses(action, actor, context.state)
            val hits = LocalDeclaredMultiHit.representativeCount(action, actor, context.state)
            // Repeated integer rolls share the same public post-hit state and need one value read.
            rolls.groupingBy { it }.eachCount().forEach { (damage, count) ->
                val (hit, after) = itemHit(context.state, actor, target, action, damage, ignoreAbility, bypassesSubstitute, hits)
                if (hit.directDamageFraction <= 0.0) return@forEach
                val struck = hit.state.pokemon.first { it.battlePokemonId == target.battlePokemonId }
                val probability = count.toDouble() / rolls.size * accuracy.coerceIn(0.0, 1.0)
                val stageValue = LocalStatStageMarginalEvaluator.evaluate(hit.state, after, context, cache, tuning).score
                // The item price is isolated from the policy's stages, which have their own owner.
                val withoutReactionStages = after.copyState(pokemon = after.pokemon.map {
                    if (it.battlePokemonId == target.battlePokemonId) it.copyState(statStages = struck.statStages) else it
                })
                val itemValue = itemTransitionValue(hit.state, withoutReactionStages, context, cache, tuning)
                total += (stageValue + itemValue.total) * probability
                stages += stageValue * probability
                projectedItems += (stageValue + itemValue.itemUtility) * probability
            }
        }
        return Score(total, stages, projectedItems)
    }

    /** Replaces independent prices when two damaging actions share one publicly known item holder. */
    fun compositeCorrection(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
    ): Score {
        val actions = candidate.componentActions.filter {
            it.kind == BattleActionKind.USE_MOVE && it.moveDetails?.damageCategory != BattleMoveDamageCategory.STATUS
        }
        if (actions.size != 2) return Score()
        val targetActions = actions.flatMap { action ->
            LocalPublicMoveTargets.resolve(action, context, BattleSide.ALLY).map { it.battlePokemonId to action }
        }.groupBy({ it.first }, { it.second })
        val shared = targetActions.filter { (id, targeting) ->
            val holder = context.state.pokemon.first { it.battlePokemonId == id }
            targeting.size > 1 && holder.canonicalKnownHeldItemId != null &&
                (targeting.any { PublicIds.canonical(it.moveId.orEmpty()) == "knockoff" } ||
                    LocalPublicItemState.activeItemId(context.state, holder) in HIT_REACTIVE_ITEMS)
        }.keys
        if (shared.isEmpty()) return Score()
        val independent = actions.map { action -> damagingScore(action, context,
            LocalPublicAccuracy.probability(action, context, BattleSide.ALLY), tuning, shared) }
        val firstChance = LocalPublicTurnOrder.actsFirstProbability(context.state, BattleSide.ALLY, actions[0],
            BattleSide.ALLY, actions[1]) ?: 0.5
        val orders = listOf(actions to firstChance, actions.reversed() to 1.0 - firstChance)
        val cache = LocalProjectedActionCalculationCache()
        val original = context.state.pokemon.associateBy { it.battlePokemonId }
        var total = 0.0
        var stages = 0.0
        var items = 0.0
        orders.filter { it.second > 0.0 }.forEach { (order, chance) ->
            var branches = listOf(ItemBranch(context.state, chance))
            order.forEach { action -> branches = merge(branches.flatMap { itemBranches(it, action, context, shared) }, cache) }
            branches.forEach { branch ->
                // Keep the complete joint body damage in both counterfactuals. A holder knocked out
                // by the pair has no future recovery or attacking pressure to remove or boost.
                val withoutItems = branch.state.copyState(pokemon = branch.state.pokemon.map {
                    if (it.battlePokemonId !in shared) it else it.copyState(
                        knownHeldItemId = requireNotNull(original[it.battlePokemonId]).knownHeldItemId,
                        statStages = requireNotNull(original[it.battlePokemonId]).statStages,
                    )
                })
                val value = itemTransitionValue(withoutItems, branch.state, context, cache, tuning)
                val stageValue = LocalStatStageMarginalEvaluator.transitionValue(withoutItems, branch.state,
                    context, cache, tuning).score
                total += value.total * branch.probability
                stages += stageValue * branch.probability
                items += value.itemUtility * branch.probability
            }
        }
        return Score(total - independent.sumOf { it.total }, stages - independent.sumOf { it.statStageUtility },
            items - independent.sumOf { it.itemUtility })
    }

    private data class ItemBranch(val state: BattleStateView, val probability: Double)

    private fun itemBranches(
        branch: ItemBranch,
        candidate: BattleActionCandidate,
        source: BattleDecisionContext,
        shared: Set<UUID>,
    ): List<ItemBranch> {
        val context = source.copy(state = branch.state, candidates = listOf(candidate))
        val actor = actor(candidate, context) ?: return listOf(branch)
        val targets = LocalPublicMoveTargets.resolve(candidate, context, BattleSide.ALLY)
            .withIndex().filter { it.value.battlePokemonId in shared }
        if (targets.isEmpty()) return listOf(branch)
        val accuracy = LocalPublicAccuracy.probability(candidate, context, BattleSide.ALLY).coerceIn(0.0, 1.0)
        var hits = if (accuracy > 0.0) listOf(branch.copy(probability = branch.probability * accuracy)) else emptyList()
        targets.forEach { (index, target) ->
            val action = targetHit(candidate, target, index, context)
            hits = hits.flatMap { hitBranch ->
                val targeted = source.copy(state = hitBranch.state, candidates = listOf(action))
                val mechanics = LocalPublicMechanicsKernel.projectMove(action, targeted)
                if (mechanics.publiclyNullified) return@flatMap listOf(hitBranch)
                val rolls = damageHypotheses(action, targeted, mechanics.knownDamageMultiplier)
                if (rolls.isEmpty()) return@flatMap listOf(hitBranch)
                val current = hitBranch.state.pokemon.first { it.battlePokemonId == target.battlePokemonId }
                val ignoresAbility = LocalPublicAbilityMechanics.ignoresTargetAbility(action, actor, current, hitBranch.state)
                rolls.groupingBy { it }.eachCount().map { (damage, count) ->
                    val after = itemHit(hitBranch.state, actor, current, action, damage, ignoresAbility,
                        LocalSubstituteRules.bypasses(action, actor, hitBranch.state),
                        LocalDeclaredMultiHit.representativeCount(action, actor, hitBranch.state)).second
                    ItemBranch(after, hitBranch.probability * count / rolls.size)
                }
            }
        }
        return hits + if (accuracy < 1.0) listOf(branch.copy(probability = branch.probability * (1.0 - accuracy))) else emptyList()
    }

    private fun targetHit(candidate: BattleActionCandidate, target: BattlePokemonStateView, index: Int,
        context: BattleDecisionContext): BattleActionCandidate {
        if (index == 0 && (candidate.targets.isNotEmpty() || candidate.facts?.spreadTargets.isNullOrEmpty())) return candidate
        val targeted = LocalPublicMoveTargets.spreadHitOn(candidate, target, "${candidate.actionId}:item:$index")
        val original = candidate.facts
        val extra = original?.spreadTargets?.firstOrNull { it.side == target.side && it.slot == target.activeSlot }
        val complete = original?.standardDamageModel != null && extra?.standardDamageFractionRange != null &&
            extra.standardDamageRollKoProbabilityRange != null && extra.standardKnockoutAssessment != null
        val facts = if (extra == null || original == null) null else original.copy(
            typeChartMultiplier = extra.typeChartMultiplier,
            standardDamageModel = if (complete) original.standardDamageModel else null,
            standardDamageFractionRange = if (complete) extra.standardDamageFractionRange else null,
            standardDamageRollKoProbabilityRange = if (complete) extra.standardDamageRollKoProbabilityRange else null,
            standardKnockoutAssessment = if (complete) extra.standardKnockoutAssessment else null,
            spreadTargets = emptyList(),
        )
        return BattleActionCandidate(targeted.actionId, targeted.kind, targeted.actorSlot, targeted.moveSlot, targeted.moveId,
            targets = targeted.targets, mechanic = targeted.mechanic, moveDetails = targeted.moveDetails, facts = facts,
            tags = if (LocalPublicMoveTargets.spreadMultiplier(candidate, context, BattleSide.ALLY) < 1.0)
                targeted.tags else candidate.tags)
    }

    private fun merge(branches: List<ItemBranch>, cache: LocalProjectedActionCalculationCache): List<ItemBranch> =
        branches.groupBy { cache.fingerprints.of(it.state) }.values.map { same ->
            ItemBranch(same.first().state, same.sumOf { it.probability })
        }

    private fun itemHit(
        state: BattleStateView,
        actor: BattlePokemonStateView,
        target: BattlePokemonStateView,
        action: BattleActionCandidate,
        damage: Double,
        ignoresAbility: Boolean,
        bypassesSubstitute: Boolean,
        hitCount: Int,
    ): Pair<LocalAppliedDirectHit, BattleStateView> {
        val hit = LocalDirectHitMechanics.apply(state, actor.battlePokemonId, target.battlePokemonId,
            damage, emptyList(), ignoresAbility, bypassesSubstitute, hitCount)
        if (hit.directDamageFraction <= 0.0) return hit to hit.state
        val struck = hit.state.pokemon.first { it.battlePokemonId == target.battlePokemonId }
        var after = hit.state
        if (!struck.fainted && struck.hpFraction > 0.0) {
            if (LocalPublicItemState.activeItemId(after, struck) == "airballoon") after = setItem(after, struck, "")
            if (LocalAfterHitReactions.weaknessPolicyActivates(after, struck, action, actor)) {
                after = LocalStatStageChange.apply(after, struck.battlePokemonId, null, mapOf("attack" to 2, "special_attack" to 2))
                after = setItem(after, struck, "")
            }
        }
        val current = after.pokemon.first { it.battlePokemonId == target.battlePokemonId }
        if (PublicIds.canonical(action.moveId.orEmpty()) == "knockoff" &&
            LocalPublicItemTransferRules.canRemove(after, current, ignoresAbility)) after = setItem(after, current, "")
        return hit to after
    }

    private fun damageHypotheses(candidate: BattleActionCandidate, context: BattleDecisionContext, multiplier: Double): List<Double> {
        val range = candidate.facts?.standardDamageFractionRange
        // Formula-produced facts permit the real sixteen public damage rolls. Directly supplied
        // facts (including external provider/test facts) retain their explicit bounds instead of
        // being silently replaced by a different calculation.
        if (range == null || BattleCalculationBasis.SHOWDOWN_GEN9_FORMULA in candidate.facts?.basis.orEmpty()) {
            PublicBattleTacticalCalculator.conservativeDamageRollFractions(candidate, context, BattleSide.ALLY)?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        range ?: return emptyList()
        return if (range.minimum == range.maximum) listOf(range.minimum * multiplier)
            else listOf(range.minimum * multiplier, range.maximum * multiplier)
    }

    private fun itemTransitionValue(
        before: BattleStateView,
        after: BattleStateView,
        context: BattleDecisionContext,
        cache: LocalProjectedActionCalculationCache,
        tuning: LocalDecisionTuning,
    ): Score {
        if (before.pokemon.zip(after.pokemon).none { (old, next) -> old.knownHeldItemId != next.knownHeldItemId }) return Score()
        val beforeResidual = LocalEndTurnStateProjector.project(before)
        val afterResidual = LocalEndTurnStateProjector.project(after)
        val residualValue = LocalBoardMaterial.evaluate(afterResidual) - LocalBoardMaterial.evaluate(beforeResidual)
        // Orbs can change a public status at the residual event. Preserve that known status but
        // restore HP for pressure comparison, so residual HP itself is billed only once.
        fun pressureState(base: BattleStateView, residual: BattleStateView) = base.copyState(pokemon = base.pokemon.map { pokemon ->
            val next = residual.pokemon.first { it.battlePokemonId == pokemon.battlePokemonId }
            pokemon.copyState(statusId = next.statusId, statStages = next.statStages)
        })
        val beforePressureState = pressureState(before, beforeResidual)
        val afterPressureState = pressureState(after, afterResidual)
        val pressureValue = positionPressure(afterPressureState, context, cache, tuning) - positionPressure(beforePressureState, context, cache, tuning)
        val speedValue = LocalStatStageMarginalEvaluator.speedTransitionBoardDelta(beforePressureState, afterPressureState, tuning)
        // An acquired White Herb can also restore stages. That pressure is re-priced by the
        // turn projection, unlike a Choice item's pressure, and belongs to this item owner.
        val restoredStagePressure = LocalStatStageMarginalEvaluator.transitionValue(
            beforePressureState, afterPressureState, context, cache, tuning,
        ).pressureBoardDelta
        val statusValue = LocalImmediateTurnScorer.positionEffectValue(afterResidual, context) -
            LocalImmediateTurnScorer.positionEffectValue(beforeResidual, context)
        return Score(total = (residualValue + pressureValue + speedValue + statusValue) * SCORE_PER_HP_BAR,
            itemUtility = (residualValue + speedValue + statusValue + restoredStagePressure) * SCORE_PER_HP_BAR)
    }

    private fun positionPressure(state: BattleStateView, context: BattleDecisionContext,
        cache: LocalProjectedActionCalculationCache, tuning: LocalDecisionTuning): Double =
        LocalLookaheadStateEvaluator.attackPressure(state, BattleSide.ALLY, context, cache, tuning = tuning, capDamageToRemainingHp = true) -
            LocalLookaheadStateEvaluator.attackPressure(state, BattleSide.OPPONENT, context, cache, tuning = tuning, capDamageToRemainingHp = true)

    private fun actor(candidate: BattleActionCandidate, context: BattleDecisionContext) = context.state.pokemon.firstOrNull {
        it.side == BattleSide.ALLY && it.activeSlot == candidate.actorSlot && !it.fainted
    }

    private fun setItem(state: BattleStateView, pokemon: BattlePokemonStateView, item: String) =
        state.copyState(pokemon = state.pokemon.map { if (it.battlePokemonId == pokemon.battlePokemonId) it.copyState(knownHeldItemId = item) else it })

    private const val SCORE_PER_HP_BAR = 100.0
    private val ITEM_SWAPS = setOf("trick", "switcheroo")
    private val HIT_REACTIVE_ITEMS = setOf("airballoon", "weaknesspolicy")
}
