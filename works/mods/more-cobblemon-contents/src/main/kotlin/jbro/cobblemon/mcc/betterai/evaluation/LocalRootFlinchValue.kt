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
 * revealed recovery move would restore. Fake Out's certain flinch counts the same on its first turn out.
 */
internal object LocalRootFlinchValue {
    fun score(candidate: BattleActionCandidate, context: BattleDecisionContext, accuracy: Double, tuning: LocalDecisionTuning): Double {
        val state = context.state
        val actor = state.pokemon.firstOrNull {
            it.side == BattleSide.ALLY && it.activeSlot == candidate.actorSlot && !it.fainted
        } ?: return 0.0
        val target = primaryTarget(candidate, context) ?: return 0.0
        // A certain flinch (Fake Out) counts like any other while it can land; once its first turn is spent it fails and
        // keeps only its own penalty. Left out entirely, it took no turn from anything and lost to any harder hit
        // (2026-10-13).
        // A certain flinch on a condition its data does not declare (Upper Hand: only into a priority move) is left out,
        // as every certain flinch was before.
        if ((declared(candidate) ?: return 0.0) >= 1.0 && (!certain(candidate) ||
            LocalTacticalSituationalEvaluator.expiredFirstActiveTurnPenalty(candidate, context) > 0.0)) return 0.0
        val chance = chance(candidate, actor, target, state).takeIf { it > 0.0 } ?: return 0.0
        val facts = candidate.facts ?: return 0.0
        val movesFirst = facts.actsFirstProbability ?: return 0.0
        val survives = 1.0 - (facts.standardDamageRollKoProbabilityRange?.let { (it.minimum + it.maximum) / 2.0 } ?: 0.0)
        // The turn it takes only puts the target's move off unless that move would knock the user out now
        // (LocalPokemonWorth.urgency): a slower Scrafty's Fake Out at 70.6 beat its Ice Punch, four times into Garchomp, for
        // a hit Garchomp landed the next turn anyway (2026-10-20).
        return accuracy * movesFirst * chance * survives.coerceIn(0.0, 1.0) * deniedTurn(context, actor, target, tuning) * HP_TO_SCORE *
            LocalPokemonWorth.urgency(target.battlePokemonId)
    }

    /**
     * The chance one landed hit of [candidate] flinches [target], when it hits before [target] moves: the move's declared
     * chance, doubled by Serene Grace and gone with Sheer Force, none through Inner Focus, Shield Dust, a Covert Cloak or
     * a Substitute or a Dynamax. Fake Out's certain flinch is a chance of one. Zero for a move that does not flinch.
     */
    fun chance(
        candidate: BattleActionCandidate,
        actor: BattlePokemonStateView,
        target: BattlePokemonStateView,
        state: jbro.cobblemon.mcc.internal.ai.BattleStateView,
    ): Double {
        val declared = declared(candidate)?.takeIf { it > 0.0 } ?: return 0.0
        val chance = when (LocalPublicAbilityState.effectiveKnownAbility(state, actor)) {
            "sheerforce" -> return 0.0
            "serenegrace" -> (declared * 2.0).coerceAtMost(1.0)
            else -> declared
        }
        val ignoresAbility = LocalPublicAbilityMechanics.ignoresTargetAbility(candidate, actor, target, state)
        if (!ignoresAbility && LocalPublicAbilityState.effectiveKnownAbility(state, target) in FLINCH_IMMUNE_ABILITIES) return 0.0
        if (jbro.cobblemon.mcc.internal.ai.BattleShowdownHeldEffects.shieldsSecondaries(LocalPublicItemState.activeItemId(state, target))) return 0.0
        if (target.knownVolatileEffectIds.any { PublicIds.canonical(it) == "substitute" }) return 0.0
        // A Dynamaxed Pokemon cannot flinch (Showdown's dynamax condition refuses the volatile): Fake Out into one is its
        // damage alone. Read as a flinch, the AI Faked Out a Dynamaxed foe for a free turn it never got (dev server,
        // 2026-10-20).
        if (jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax.active(target)) return 0.0
        // An unrevealed ability counts at the share of the possible ones that let the flinch through, all alike (as the
        // drops of G-915): a Mudsdale may be the Inner Focus one. Read as none, Fake Out was a certain flinch into it.
        if (!ignoresAbility && target.knownAbilityId == null) {
            val possible = jbro.cobblemon.mcc.betterai.state.LocalEntryAbilityProjector.publicPossibleAbilities(state, target)
            if (possible.isNotEmpty()) return chance * possible.count { it !in FLINCH_IMMUNE_ABILITIES } / possible.size
        }
        return chance
    }

    /**
     * Whether [candidate]'s data declares a flinch on every hit, before the target's ability or item, and the one
     * condition it may fail on is a declared one: its user's first turn out (Fake Out). Upper Hand flinches as surely but
     * only into a priority move, a condition its data does not declare; read as certain, it freed every turn.
     */
    fun certain(candidate: BattleActionCandidate): Boolean = (declared(candidate) ?: 0.0) >= 1.0 &&
        candidate.moveDetails?.effects?.effects.orEmpty().any { it.kind == BattleMoveEffectKind.FIRST_ACTIVE_TURN_ONLY }

    /**
     * Whether [candidate]'s data declares a flinch on every hit. Every such attack works under a condition its data may not
     * state (Fake Out on its user's first turn out, Upper Hand only into a priority move), so it can never hold a target
     * turn after turn.
     */
    fun declaredEveryHit(candidate: BattleActionCandidate): Boolean = (declared(candidate) ?: 0.0) >= 1.0

    private fun declared(candidate: BattleActionCandidate): Double? {
        val details = candidate.moveDetails ?: return null
        if (details.damageCategory == BattleMoveDamageCategory.STATUS) return null
        return details.effects?.effects.orEmpty().firstOrNull {
            it.kind == BattleMoveEffectKind.VOLATILE_STATUS && it.target == BattleMoveEffectTarget.SELECTED_TARGET &&
                PublicIds.canonical(it.valueId.orEmpty()) == FLINCH
        }?.probability?.coerceIn(0.0, 1.0)
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
