package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.*

/** Applies Aegislash's publicly known form transition before move effects and damage are projected. */
internal object LocalStanceChangeStateProjector {
    fun beforeMove(
        state: BattleStateView,
        side: BattleSide,
        action: BattleActionCandidate,
    ): BattleStateView {
        if (action.kind != BattleActionKind.USE_MOVE) return state
        val actor = state.pokemon.singleOrNull {
            it.side == side && it.activeSlot == action.actorSlot && !it.fainted && it.hpFraction > 0.0
        } ?: return state
        if (canonical(actor.speciesId) != "aegislash" || canonical(actor.knownAbilityId) != "stancechange") {
            return state
        }
        val wantsShield = canonical(action.moveId) == "kingsshield"
        val wantsBlade = action.moveDetails?.damageCategory != BattleMoveDamageCategory.STATUS
        if (!wantsShield && !wantsBlade) return state
        val form = actor.knownFormStates.values.firstOrNull { known ->
            val blade = canonical(known.formId).contains("blade")
            if (wantsShield) !blade else blade
        } ?: return state
        if (canonical(actor.formId) == canonical(form.formId)) return state

        val updated = BattlePokemonStateView(
            battlePokemonId = actor.battlePokemonId,
            side = actor.side,
            activeSlot = actor.activeSlot,
            speciesId = actor.speciesId,
            formId = form.formId,
            level = actor.level,
            hpFraction = actor.hpFraction,
            statusId = actor.statusId,
            statStages = actor.statStages,
            knownMoveIds = actor.knownMoveIds,
            knownAbilityId = actor.knownAbilityId,
            knownHeldItemId = actor.knownHeldItemId,
            fainted = actor.fainted,
            knownTypeIds = if (actor.knownTeraTypeId == null) form.knownTypeIds else actor.knownTypeIds,
            combatStats = form.combatStats,
            knownFormStates = actor.knownFormStates,
            actionConstraints = actor.actionConstraints,
            knownVolatileEffectIds = actor.knownVolatileEffectIds,
            knownBaseStabTypeIds = form.knownTypeIds,
            knownTeraTypeId = actor.knownTeraTypeId,
            knownStellarBoostedTypeIds = actor.knownStellarBoostedTypeIds,
            knownSubstituteHpFractionRange = actor.knownSubstituteHpFractionRange,
        )
        val pokemon = state.pokemon.map { if (it.battlePokemonId == actor.battlePokemonId) updated else it }
        return state.derive(
            pokemon = pokemon,
        )
    }

    private fun canonical(value: String?): String = PublicIds.canonical(value.orEmpty())
}
