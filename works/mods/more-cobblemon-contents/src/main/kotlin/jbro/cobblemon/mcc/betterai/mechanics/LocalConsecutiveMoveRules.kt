package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals

/** Hard repeat restrictions from move data. Shared Protect probabilities stay in LocalStallingProtectionRules. */
internal object LocalConsecutiveMoveRules {
    private const val CANNOT_USE_TWICE = "cantusetwice"

    fun needsGap(action: BattleActionCandidate, actor: BattlePokemonStateView): Boolean =
        CANNOT_USE_TWICE in flags(action,actor)

    fun canAttempt(action: BattleActionCandidate, state: BattleStateView, actor: BattlePokemonStateView): Boolean {
        if (action.kind != BattleActionKind.USE_MOVE) return true
        val flags = flags(action,actor)
        val gap = CANNOT_USE_TWICE in flags
        val volatile = flags.firstOrNull { it.startsWith(BattleDeclarativeMoveEffects.REPEAT_FAILS_WHILE_VOLATILE_FLAG) }
            ?.removePrefix(BattleDeclarativeMoveEffects.REPEAT_FAILS_WHILE_VOLATILE_FLAG)
        if (!gap && volatile == null) return true
        val entry = state.observedEvents.asSequence().filter {
            it.kind == BattleObservedEventKind.SWITCHED && it.actorPokemonId == actor.battlePokemonId
        }.maxOfOrNull { it.sequence } ?: Long.MIN_VALUE
        val previous = state.observedEvents.asSequence().filter {
            it.kind == BattleObservedEventKind.MOVE_USED && it.actorPokemonId == actor.battlePokemonId && it.sequence > entry
        }.maxByOrNull { it.sequence }?.publicValueId?.let(PublicIds::canonical)
        val id = action.moveId?.let(PublicIds::canonical)
        val same = previous != null && previous == id
        val active = volatile != null && actor.knownVolatileEffectIds.any { PublicIds.canonical(it) == volatile }
        val possible = !(gap && same || active)
        LocalDecisionInternals.record("consecutive:${action.actionId}",mapOf("possible" to LocalDecisionInternals.flag(possible),
            "needsGap" to LocalDecisionInternals.flag(gap),"previousSame" to LocalDecisionInternals.flag(same),
            "blockingVolatile" to LocalDecisionInternals.flag(active)))
        return possible
    }

    private fun flags(action: BattleActionCandidate, actor: BattlePokemonStateView): Set<String> {
        // A repeated base status move can become Max Guard; a repeated attack can become a Max Move.
        if (action.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS &&
            (LocalDynamax.active(actor) || LocalDynamax.isMechanic(action.mechanic?.mechanicId))) return emptySet()
        val used = LocalDynamax.asUsed(action,actor)
        return used.moveDetails?.effects?.mechanicFlags.orEmpty() + BattleShowdownMoveEffects.flags(used.moveId)
    }
}
