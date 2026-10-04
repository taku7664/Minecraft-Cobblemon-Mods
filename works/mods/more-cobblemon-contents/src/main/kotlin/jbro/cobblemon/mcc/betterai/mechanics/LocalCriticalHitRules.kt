package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.*

/** A resolved critical hit is a mechanics input, never a score bonus. */
internal object LocalCriticalHitRules {
    fun confirmed(candidate: BattleActionCandidate, actor: BattlePokemonStateView?, target: BattlePokemonStateView?, state: BattleStateView): Boolean =
        ("criticalhit" in candidate.tags || candidate.moveDetails?.effects?.effects.orEmpty().any {
            it.kind == BattleMoveEffectKind.ALWAYS_CRITICAL
        } || actor?.knownVolatileEffectIds.orEmpty().any { PublicIds.canonical(it) == "laserfocus" }) &&
            !blocked(candidate, actor, target, state)

    fun blocked(candidate: BattleActionCandidate, actor: BattlePokemonStateView?, target: BattlePokemonStateView?, state: BattleStateView): Boolean =
        LocalPublicAbilityState.effectiveKnownAbility(state, target) in setOf("battlearmor", "shellarmor") &&
            !LocalPublicAbilityMechanics.ignoresTargetAbility(candidate, actor, target, state)

    fun asCritical(candidate: BattleActionCandidate) = BattleActionCandidate(
        candidate.actionId, candidate.kind, candidate.actorSlot, candidate.moveSlot, candidate.moveId,
        candidate.targets, candidate.switchPokemonId, candidate.componentActionIds, candidate.componentActions,
        candidate.mechanic, candidate.moveDetails, null, candidate.tags + "criticalhit",
    )
}
