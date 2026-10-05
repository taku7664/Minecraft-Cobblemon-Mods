package jbro.cobblemon.mcc.betterai.evaluation

import jbro.cobblemon.mcc.betterai.evaluation.LocalItemTransitionEvaluator.Score
import jbro.cobblemon.mcc.betterai.mechanics.*
import jbro.cobblemon.mcc.internal.ai.*
import java.util.UUID

/**
 * Root ownership of publicly known item effects, using the same action paths for individual and joint choices.
 * Codex 5fac96da, d40573ab, 7ce6ffb8, under measurement: nothing is priced without [LocalDecisionTuning.itemTransitionValue].
 */
internal object LocalRootItemEffectEvaluator {
    /** Null means this is not an item-swap move; zero means its known swap has no priced effect. */
    fun swapScore(candidate: BattleActionCandidate, context: BattleDecisionContext, accuracy: Double,
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT): Score? {
        if (!tuning.itemTransitionValue || !isSwap(candidate)) return null
        val actor = actor(candidate, context) ?: return Score()
        val target = LocalPublicMoveTargets.resolve(candidate, context, BattleSide.ALLY).singleOrNull() ?: return Score()
        val cache = LocalProjectedActionCalculationCache()
        return price(LocalPublicItemEffectProjector.project(candidate, context, accuracy = accuracy), context,
            setOf(actor.battlePokemonId, target.battlePokemonId), cache, tuning)
    }

    /** Known on-hit items are resolved after actual body damage, including survival and decoys. */
    fun damagingScore(candidate: BattleActionCandidate, context: BattleDecisionContext, accuracy: Double,
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT): Score =
        damagingScore(candidate, context, accuracy, tuning, null)

    private fun damagingScore(candidate: BattleActionCandidate, context: BattleDecisionContext, accuracy: Double,
        tuning: LocalDecisionTuning, targetIds: Set<UUID>?): Score {
        if (!tuning.itemTransitionValue) return Score()
        val details = candidate.moveDetails ?: return Score()
        if (details.damageCategory == BattleMoveDamageCategory.STATUS || accuracy <= 0.0) return Score()
        val knockOff = PublicIds.canonical(candidate.moveId.orEmpty()) == "knockoff"
        val affected = LocalPublicMoveTargets.resolve(candidate, context, BattleSide.ALLY).filter { target ->
            (targetIds == null || target.battlePokemonId in targetIds) &&
                (knockOff && target.canonicalKnownHeldItemId != null ||
                    LocalPublicItemState.activeItemId(context.state, target) in HIT_REACTIVE_ITEMS)
        }.mapTo(hashSetOf()) { it.battlePokemonId }
        if (affected.isEmpty()) return Score()
        val cache = LocalProjectedActionCalculationCache()
        return price(LocalPublicItemEffectProjector.project(candidate, context, affected, accuracy), context, affected, cache, tuning)
    }

    /** Replace independent prices when two public actions compete for the same known item state. */
    fun compositeCorrection(candidate: BattleActionCandidate, context: BattleDecisionContext,
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT): Score {
        if (!tuning.itemTransitionValue) return Score()
        val actions = candidate.componentActions.filter {
            it.kind == BattleActionKind.USE_MOVE && (isSwap(it) ||
                it.moveDetails?.damageCategory?.let { category -> category != BattleMoveDamageCategory.STATUS } == true)
        }
        if (actions.size != 2) return Score()
        val targetActions = actions.flatMap { action ->
            LocalPublicMoveTargets.resolve(action, context, BattleSide.ALLY).map { it.battlePokemonId to action }
        }.groupBy({ it.first }, { it.second })
        val shared = targetActions.filter { (id, targeting) ->
            val holder = context.state.pokemon.first { it.battlePokemonId == id }
            targeting.size > 1 && holder.knownHeldItemId != null && (targeting.any(::isSwap) ||
                holder.canonicalKnownHeldItemId != null &&
                (targeting.any { PublicIds.canonical(it.moveId.orEmpty()) == "knockoff" } ||
                    LocalPublicItemState.activeItemId(context.state, holder) in HIT_REACTIVE_ITEMS))
        }.keys
        if (shared.isEmpty()) return Score()
        val affected = shared + actions.filter(::isSwap).mapNotNull { actor(it, context)?.battlePokemonId }
        val independent = actions.map { action ->
            val accuracy = LocalPublicAccuracy.probability(action, context, BattleSide.ALLY)
            if (isSwap(action)) swapScore(action, context, accuracy, tuning) ?: Score()
            else damagingScore(action, context, accuracy, tuning, shared)
        }
        val firstChance = LocalPublicTurnOrder.actsFirstProbability(context.state, BattleSide.ALLY, actions[0],
            BattleSide.ALLY, actions[1]) ?: 0.5
        val cache = LocalProjectedActionCalculationCache()
        val paths = listOf(actions to firstChance, actions.reversed() to 1.0 - firstChance)
            .filter { it.second > 0.0 }.flatMap { (order, chance) ->
                var branches = listOf(LocalPublicItemEffectProjector.Path(context.state, chance))
                order.forEach { action ->
                    branches = branches.flatMap { branch ->
                        LocalPublicItemEffectProjector.project(action, context.copy(state = branch.state, candidates = listOf(action)), shared)
                            .map { next -> next.copy(probability = branch.probability * next.probability,
                                damagingStageOwnerIds = branch.damagingStageOwnerIds + next.damagingStageOwnerIds) }
                    }.groupBy { cache.fingerprints.of(it.state) to it.damagingStageOwnerIds }.values.map { same ->
                        same.first().copy(probability = same.sumOf { it.probability })
                    }
                }
                branches
            }
        val joint = price(paths, context, affected, cache, tuning)
        return Score(joint.total - independent.sumOf { it.total },
            joint.statStageUtility - independent.sumOf { it.statStageUtility },
            joint.itemUtility - independent.sumOf { it.itemUtility })
    }

    private fun price(paths: List<LocalPublicItemEffectProjector.Path>, context: BattleDecisionContext,
        affected: Set<UUID>, cache: LocalProjectedActionCalculationCache, tuning: LocalDecisionTuning): Score {
        val original = context.state.pokemon.associateBy { it.battlePokemonId }
        var total = 0.0
        var stages = 0.0
        var items = 0.0
        paths.forEach { path ->
            // Preserve composed body damage in both positions. A jointly knocked-out holder has
            // no future item recovery or attacking pressure to remove or boost.
            val withoutItems = path.state.copyState(pokemon = path.state.pokemon.map {
                if (it.battlePokemonId !in affected) it else it.copyState(
                    knownHeldItemId = requireNotNull(original[it.battlePokemonId]).knownHeldItemId,
                    statStages = requireNotNull(original[it.battlePokemonId]).statStages)
            })
            val value = LocalItemTransitionEvaluator.evaluate(withoutItems, path.state, context, cache, tuning)
            val withoutReactionStages = path.state.copyState(pokemon = path.state.pokemon.map {
                if (it.battlePokemonId !in path.damagingStageOwnerIds) it else
                    it.copyState(statStages = requireNotNull(original[it.battlePokemonId]).statStages)
            })
            val stageValue = LocalStatStageMarginalEvaluator.transitionValue(withoutReactionStages, path.state,
                context, cache, tuning).score
            total += value.total * path.probability
            stages += stageValue * path.probability
            items += value.itemUtility * path.probability
        }
        return Score(total, stages, items)
    }

    private fun actor(candidate: BattleActionCandidate, context: BattleDecisionContext) = context.state.pokemon.firstOrNull {
        it.side == BattleSide.ALLY && it.activeSlot == candidate.actorSlot && !it.fainted && it.hpFraction > 0.0
    }

    private fun isSwap(candidate: BattleActionCandidate) = PublicIds.canonical(candidate.moveId.orEmpty()) in ITEM_SWAPS
    private val ITEM_SWAPS = setOf("trick", "switcheroo")
    private val HIT_REACTIVE_ITEMS = setOf("airballoon", "weaknesspolicy")
}
