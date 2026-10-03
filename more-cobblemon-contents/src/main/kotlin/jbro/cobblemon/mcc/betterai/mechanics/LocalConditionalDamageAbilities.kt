package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.*

internal object LocalConditionalDamageAbilities {
    fun randomSniper(action: BattleActionCandidate, context: BattleDecisionContext, side: BattleSide): Boolean =
        action.moveDetails?.damageCategory != BattleMoveDamageCategory.STATUS &&
            action.moveDetails?.effects?.effects.orEmpty().none { it.kind == BattleMoveEffectKind.ALWAYS_CRITICAL } &&
            context.state.pokemon.firstOrNull { it.side == side && it.activeSlot == action.actorSlot && !it.fainted }
                ?.let { LocalPublicAbilityState.effectiveKnownAbility(context.state, it) == "sniper" } == true
    fun critical(action: BattleActionCandidate, state: BattleStateView, actor: BattlePokemonStateView?, target: BattlePokemonStateView): Boolean {
        if ("projected_noncritical" in action.tags) return false
        val requested = "projected_critical" in action.tags || action.moveDetails?.effects?.effects.orEmpty()
            .any { it.kind == BattleMoveEffectKind.ALWAYS_CRITICAL }
        return requested && (LocalPublicAbilityState.effectiveKnownAbility(state, target) !in setOf("battlearmor", "shellarmor") ||
            actor != null && LocalPublicAbilityMechanics.ignoresTargetAbility(action, actor, target, state))
    }
    fun marked(action: BattleActionCandidate, tag: String) = BattleActionCandidate(
        action.actionId, action.kind, action.actorSlot, action.moveSlot, action.moveId, action.targets,
        action.switchPokemonId, action.componentActionIds, action.componentActions, action.mechanic,
        action.moveDetails, null, action.tags + tag)
    fun analytic(action: BattleActionCandidate, actor: BattlePokemonStateView, context: BattleDecisionContext): Double {
        if ("projected_last_move" in action.tags) return 5325.0 / 4096.0
        if ("projected_not_last_move" in action.tags) return 1.0
        val lastProbability = context.state.pokemon.filter {
            it.battlePokemonId != actor.battlePokemonId && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        }.fold(1.0) { probability, other ->
            // Root estimate against ordinary replies. Scheduled projections supply the actual queue.
            val reply = context.candidates.asSequence().flatMap {
                if (it.kind == BattleActionKind.COMPOSITE) it.componentActions.asSequence() else sequenceOf(it)
            }.firstOrNull { other.side == actor.side && it.actorSlot == other.activeSlot && it.actionId != action.actionId }
                ?: BattleActionCandidate("order-probe", BattleActionKind.USE_MOVE, actorSlot = other.activeSlot, moveSlot = 0,
                    moveId = "order-probe", moveDetails = BattleMoveCandidateView("normal", BattleMoveDamageCategory.STATUS, 0.0, 100.0, 0, 1))
            probability * (1.0 - (LocalPublicTurnOrder.actsFirstProbability(context.state, actor.side, action, other.side, reply) ?: 0.5))
        }
        return 1.0 + (5325.0 / 4096.0 - 1.0) * lastProbability
    }
}
