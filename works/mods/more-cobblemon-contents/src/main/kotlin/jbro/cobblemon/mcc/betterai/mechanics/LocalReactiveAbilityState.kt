package jbro.cobblemon.mcc.betterai.mechanics

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.state.LocalTraitStateBranch

/** Public per-action state for abilities whose modifiers depend on an earlier event. */
internal object LocalReactiveAbilityState {
    const val ENTERED_THIS_TURN = "better_ai:entered_this_turn"
    const val ANALYTIC_ACTIVE = "better_ai:analytic_active"
    const val SLOW_START_TURNS = "better_ai:slow_start_turns="
    const val ILLUSION_AS = "better_ai:illusion_as="
    const val TRACE_GAVE_UP = "better_ai:trace_gave_up"
    const val CUSTAP_CHECKED = "better_ai:custap_checked"
    const val CUSTAP_PRIORITY = "better_ai:custap_priority"

    fun prepareTurn(state: BattleStateView, actions: List<Pair<BattleSide, BattleActionCandidate>>): List<LocalTraitStateBranch> {
        var branches = listOf(LocalTraitStateBranch(state, 1.0))
        for ((side, action) in actions.filter { it.second.kind == BattleActionKind.USE_MOVE }) branches = branches.flatMap { parent ->
            beforeAction(parent.state, side, action).map { it.copy(probability = parent.probability * it.probability) }
        }
        return branches
    }

    fun beforeAction(state: BattleStateView, side: BattleSide, action: BattleActionCandidate): List<LocalTraitStateBranch> {
        val holder = state.pokemon.firstOrNull { it.side == side && it.activeSlot == action.actorSlot && !it.fainted && it.hpFraction > 0.0 }
            ?: return listOf(LocalTraitStateBranch(state, 1.0))
        val ability = LocalPublicAbilityState.effectiveKnownAbility(state, holder)
        if (CUSTAP_CHECKED in holder.knownVolatileEffectIds || holder.knownHeldItemId?.let(PublicIds::canonical) != "custapberry")
            return listOf(LocalTraitStateBranch(state, 1.0))
        val checked = state.copyState(pokemon = state.pokemon.map {
            if (it.battlePokemonId == holder.battlePokemonId) it.copyState(knownVolatileEffectIds = it.knownVolatileEffectIds + CUSTAP_CHECKED) else it
        })
        if (LocalPublicItemState.activeItemId(state, holder) != "custapberry" ||
            holder.hpFraction > (if (ability == "gluttony") .5 else .25) || !LocalBerryMechanics.canEat(state, holder))
            return listOf(LocalTraitStateBranch(checked, 1.0))
        val priority = checked.copyState(pokemon = checked.pokemon.map {
            if (it.battlePokemonId == holder.battlePokemonId) it.copyState(knownVolatileEffectIds = it.knownVolatileEffectIds + CUSTAP_PRIORITY) else it
        })
        val consumed = LocalBerryMechanics.eatExternalBerry(LocalBerryMechanics.consume(priority, holder.battlePokemonId, "custapberry"),
            holder.battlePokemonId, "custapberry")
        // Quick Draw's earlier fractional callback may supply +0.1, in which case Custap is not eaten.
        return if (ability == "quickdraw" && action.moveDetails?.damageCategory != BattleMoveDamageCategory.STATUS)
            listOf(LocalTraitStateBranch(priority, .3), LocalTraitStateBranch(consumed, .7))
        else listOf(LocalTraitStateBranch(consumed, 1.0))
    }

    fun slowStartActive(holder: BattlePokemonStateView): Boolean = holder.knownVolatileEffectIds.any {
        PublicIds.canonical(it) == "slowstart" || it.startsWith(SLOW_START_TURNS) && (it.substringAfter(SLOW_START_TURNS).toIntOrNull() ?: 0) > 0
    }

    fun absorb(state: BattleStateView, targetId: UUID, moveType: String?): BattleStateView {
        val holder = state.pokemon.firstOrNull { it.battlePokemonId == targetId } ?: return state
        if (LocalPublicAbilityState.effectiveKnownAbility(state, holder) != "flashfire" || moveType?.let(PublicIds::canonical) != "fire") return state
        return state.copyState(pokemon = state.pokemon.map {
            if (it.battlePokemonId == targetId) it.copyState(knownVolatileEffectIds = it.knownVolatileEffectIds + "flashfire") else it
        })
    }

    fun afterAction(before: BattleStateView, after: BattleStateView, actorId: UUID?, action: BattleActionCandidate): BattleStateView {
        val actingPokemon = before.pokemon.firstOrNull { it.battlePokemonId == actorId }
        val resolvedType = actingPokemon?.let { LocalPublicMoveDamageInputs.resolvedTypeId(action, it, before) }
            ?: action.moveDetails?.typeId
        val electric = action.kind == BattleActionKind.USE_MOVE && resolvedType?.let(PublicIds::canonical) == "electric"
        val next = after.pokemon.map { holder ->
            val previous = before.pokemon.firstOrNull { it.battlePokemonId == holder.battlePokemonId }
            var effects = holder.knownVolatileEffectIds
            val ability = LocalPublicAbilityState.effectiveKnownAbility(after, holder)
            // Ability replacement runs the old ability's End callback; suppression alone does not.
            val currentAbility = holder.knownAbilityId?.let(PublicIds::canonical)
            if (currentAbility != "flashfire") effects = effects.filterNot { PublicIds.canonical(it) == "flashfire" }.toSet()
            if (currentAbility != "slowstart") effects = effects.filterNot {
                PublicIds.canonical(it) == "slowstart" || it.startsWith(SLOW_START_TURNS)
            }.toSet()
            if (currentAbility != "unburden") effects = effects - "unburden"
            if (ability == "unburden" && holder.activeSlot != null && previous?.canonicalKnownHeldItemId != null && holder.canonicalKnownHeldItemId == null)
                effects = effects + "unburden"
            if (holder.canonicalKnownHeldItemId != null) effects = effects - "unburden"
            if (holder.battlePokemonId == actorId && electric) effects = effects.filterNot { PublicIds.canonical(it) == "charge" }.toSet()
            if (effects == holder.knownVolatileEffectIds) holder else holder.copyState(knownVolatileEffectIds = effects)
        }
        return LocalBerryMechanics.afterUpdate(after.copyState(pokemon = next))
    }
}
