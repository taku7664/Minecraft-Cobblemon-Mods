package jbro.cobblemon.mcc.betterai.evaluation

import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicAbilityMechanics
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicAbilityState
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicItemState
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * What a chance to flinch is worth at the root: the turn it takes from the target.
 *
 * The root priced a hit by its damage alone, so Air Slash from a Serene Grace Togekiss - a 60% chance to
 * cost the slower target its move - scored below Dazzling Gleam for five points of base power, and the
 * Boss drew between them evenly. A flinch only lands when the user moves first and the target survives
 * the hit; what it denies is the target's best play that turn, its damage to the user or the HP a
 * revealed recovery move would restore. Fake Out's certain flinch keeps its own first-turn handling.
 */
internal object LocalRootFlinchValue {
    fun score(candidate: BattleActionCandidate, context: BattleDecisionContext, accuracy: Double, tuning: LocalDecisionTuning): Double {
        val details = candidate.moveDetails ?: return 0.0
        if (details.damageCategory == BattleMoveDamageCategory.STATUS) return 0.0
        val declared = details.effects?.effects.orEmpty().firstOrNull {
            it.kind == BattleMoveEffectKind.VOLATILE_STATUS && it.target == BattleMoveEffectTarget.SELECTED_TARGET &&
                PublicIds.canonical(it.valueId.orEmpty()) == FLINCH
        }?.probability?.coerceIn(0.0, 1.0) ?: return 0.0
        if (declared <= 0.0 || declared >= 1.0) return 0.0
        val state = context.state
        val actor = state.pokemon.firstOrNull {
            it.side == BattleSide.ALLY && it.activeSlot == candidate.actorSlot && !it.fainted
        } ?: return 0.0
        val target = primaryTarget(candidate, context) ?: return 0.0
        val chance = when (LocalPublicAbilityState.effectiveKnownAbility(state, actor)) {
            "sheerforce" -> return 0.0
            "serenegrace" -> (declared * 2.0).coerceAtMost(1.0)
            else -> declared
        }
        val ignoresAbility = LocalPublicAbilityMechanics.ignoresTargetAbility(candidate, actor, target, state)
        if (!ignoresAbility && LocalPublicAbilityState.effectiveKnownAbility(state, target) in FLINCH_IMMUNE_ABILITIES) return 0.0
        if (LocalPublicItemState.activeItemId(state, target) == "covertcloak") return 0.0
        if (target.knownVolatileEffectIds.any { PublicIds.canonical(it) == "substitute" }) return 0.0
        val facts = candidate.facts ?: return 0.0
        val movesFirst = facts.actsFirstProbability ?: return 0.0
        val survives = 1.0 - (facts.standardDamageRollKoProbabilityRange?.let { (it.minimum + it.maximum) / 2.0 } ?: 0.0)
        return accuracy * movesFirst * chance * survives.coerceIn(0.0, 1.0) * deniedTurn(context, actor, target, tuning) * HP_TO_SCORE
    }

    /** The larger of the target's best damage to [actor] and what its revealed recovery would heal. */
    private fun deniedTurn(
        context: BattleDecisionContext,
        actor: BattlePokemonStateView,
        target: BattlePokemonStateView,
        tuning: LocalDecisionTuning,
    ): Double {
        val damage = LocalLookaheadStateEvaluator.attackPressure(
            context.state, target.side, context, tuning = tuning, capDamageToRemainingHp = true,
            includeMoveHypotheses = true, targetPokemonId = actor.battlePokemonId,
        )
        val missing = (1.0 - target.hpFraction).coerceAtLeast(0.0)
        val heal = context.publicActionCatalog.forPokemon(target.battlePokemonId).maxOfOrNull { move ->
            move.details.effects?.effects.orEmpty().filter {
                it.kind == BattleMoveEffectKind.HEAL_FRACTION && it.target == BattleMoveEffectTarget.USER
            }.maxOfOrNull { effect -> effect.fractionRange?.let { (it.minimum + it.maximum) / 2.0 } ?: 0.0 } ?: 0.0
        }?.coerceAtMost(missing) ?: 0.0
        return maxOf(damage, heal)
    }

    private fun primaryTarget(candidate: BattleActionCandidate, context: BattleDecisionContext): BattlePokemonStateView? {
        val opponents = context.state.pokemon.filter {
            it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        }
        val slot = candidate.targets.firstOrNull()?.slot
        return opponents.firstOrNull { it.activeSlot == slot } ?: opponents.singleOrNull()
    }

    private const val FLINCH = "flinch"
    /** Same unit as the root's damage pressure: one full HP bar is a hundred points. */
    private const val HP_TO_SCORE = 100.0
    private val FLINCH_IMMUNE_ABILITIES = setOf("innerfocus", "shielddust")
}
