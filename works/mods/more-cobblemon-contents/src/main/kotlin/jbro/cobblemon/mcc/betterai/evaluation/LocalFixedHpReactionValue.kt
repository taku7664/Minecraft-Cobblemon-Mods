package jbro.cobblemon.mcc.betterai.evaluation

import jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals
import jbro.cobblemon.mcc.betterai.calculation.*
import jbro.cobblemon.mcc.betterai.mechanics.*
import jbro.cobblemon.mcc.internal.ai.*

/** An isolated HP tick has the same value on every successful damage roll. Other reactions
 * and HP-changing move effects retain the full projector; this does not approximate their value.
 */
internal object LocalFixedHpReactionValue {
    fun read(action: BattleActionCandidate, context: BattleDecisionContext, tuning: LocalDecisionTuning): LocalItemTransitionEvaluator.Score? {
        val state = context.state
        val actor = state.pokemon.firstOrNull { it.side == BattleSide.ALLY && it.activeSlot == action.actorSlot && !it.fainted }
            ?: return null
        if (LocalPublicItemState.activeItemId(state, actor) != "lifeorb" || LocalTruant.loafs(state, actor)) return null
        // No drain/recoil, pivot, self-destruct, per-hit accuracy or other declared state change.
        if (action.moveDetails?.effects?.effects.orEmpty().isNotEmpty()) return null
        val target = LocalPublicMoveTargets.resolve(action, context, BattleSide.ALLY).singleOrNull() ?: return null
        if (target.fainted || target.hpFraction <= 0.0 || target.battlePokemonId == actor.battlePokemonId ||
            target.knownVolatileEffectIds.isNotEmpty() || actor.knownVolatileEffectIds.isNotEmpty()) return null
        if (LocalMoveImpactProjector.mayReact(action, context, BattleSide.ALLY, ignoreActorItem = true)) return null
        if (LocalPublicMechanicsKernel.projectMove(action, context, BattleSide.ALLY).publiclyNullified) return null
        // Reuse the actual integer/baseMaxHP/Dynamax/suppression rules, once. A possible recoil KO
        // still belongs to the full projection, with its own knockout price.
        val after = LocalAfterHitReactions.afterMove(state, actor.battlePokemonId, action, connected = true)
            .pokemon.first { it.battlePokemonId == actor.battlePokemonId }
        if (after.fainted || after.hpFraction <= 0.0) return null
        val loss = after.hpFraction - actor.hpFraction
        val outcomes = PublicMoveOutcomeBranchProjector.withChanceModel(LocalChanceModel.EXACT_ROLLS) {
            PublicMoveOutcomeBranchProjector.project(action, context, BattleSide.ALLY)
        }
        val connected = outcomes.filter { it.hit && it.damageFraction > 0.0 }.sumOf { it.probability }.coerceIn(0.0, 1.0)
        val hp = connected * loss * tuning.boardToScore
        LocalDecisionInternals.record("fixedHp:${action.actionId}", mapOf("beforeHp" to actor.hpFraction,
            "afterHp" to after.hpFraction, "connected" to connected, "hpDelta" to loss,
            "boardToScore" to tuning.boardToScore, "value" to hp))
        LocalDecisionInternals.record("hitReaction:${action.actionId}", mapOf("hp" to hp, "knockout" to 0.0,
            "stage" to 0.0, "item" to 0.0, "status" to 0.0, "branches" to 1.0, "value" to hp))
        return LocalItemTransitionEvaluator.Score(hp, 0.0, hp)
    }
}
