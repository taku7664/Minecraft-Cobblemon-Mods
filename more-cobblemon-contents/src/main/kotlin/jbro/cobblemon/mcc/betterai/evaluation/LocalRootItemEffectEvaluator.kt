package jbro.cobblemon.mcc.betterai.evaluation

import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.mechanics.*
import jbro.cobblemon.mcc.betterai.state.LocalEndTurnStateProjector
import jbro.cobblemon.mcc.internal.ai.*

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
            val item = LocalPublicItemState.activeItemId(context.state, target)
            if (moveId != "knockoff" && item !in HIT_REACTIVE_ITEMS) return@forEachIndexed
            if (moveId == "knockoff" && target.canonicalKnownHeldItemId == null && item !in HIT_REACTIVE_ITEMS) return@forEachIndexed
            val action = if (index == 0) candidate else LocalPublicMoveTargets.spreadHitOn(candidate, target, "${candidate.actionId}:item:$index")
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
                val hit = LocalDirectHitMechanics.apply(context.state, actor.battlePokemonId, target.battlePokemonId,
                    damage, emptyList(), ignoreAbility, bypassesSubstitute, hits)
                if (hit.directDamageFraction <= 0.0) return@forEach
                val struck = hit.state.pokemon.first { it.battlePokemonId == target.battlePokemonId }
                var after = hit.state
                if (!struck.fainted && struck.hpFraction > 0.0) {
                    if (item == "airballoon") after = setItem(after, struck, "")
                    if (LocalAfterHitReactions.weaknessPolicyActivates(after, struck, action, actor)) {
                        after = LocalStatStageChange.apply(after, struck.battlePokemonId, null,
                            mapOf("attack" to 2, "special_attack" to 2))
                        after = setItem(after, struck, "")
                    }
                }
                val current = after.pokemon.first { it.battlePokemonId == target.battlePokemonId }
                if (moveId == "knockoff" && LocalPublicItemTransferRules.canRemove(after, current, ignoreAbility)) {
                    after = setItem(after, current, "")
                }
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
