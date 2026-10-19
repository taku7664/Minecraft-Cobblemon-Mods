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
        // Read from the Pokemon's own forms and the ability's code, naming neither (2026-10-20): the game names it
        // `showdown:aegislash`, which a plain id check never matched, so a Shield-form Aegislash's attacks were scored on
        // its Shield Attack (dev server, 2026-10-20). An unrevealed ability reads as its only possible one.
        val ability = actor.knownAbilityId?.takeIf { it.isNotBlank() }
            ?: jbro.cobblemon.mcc.betterai.state.LocalEntryAbilityProjector.publicPossibleAbilities(state, actor).singleOrNull()
        val forms = jbro.cobblemon.mcc.internal.ai.BattleShowdownHeldEffects.stanceForms(ability) ?: return state
        if (forms.attackForm.isEmpty()) return state
        fun attackForm(formId: String?) = canonical(formId).contains(forms.attackForm)
        if (actor.knownFormStates.values.none { attackForm(it.formId) }) return state
        val wantsStatusForm = forms.statusFormMove != null && canonical(action.moveId) == forms.statusFormMove
        val wantsAttackForm = action.moveDetails?.damageCategory != BattleMoveDamageCategory.STATUS
        if (!wantsStatusForm && !wantsAttackForm) return state
        val form = actor.knownFormStates.values.firstOrNull { known ->
            if (wantsStatusForm) !attackForm(known.formId) else attackForm(known.formId)
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
            knownBaseAbilityId = actor.knownBaseAbilityId,
        )
        val pokemon = state.pokemon.map { if (it.battlePokemonId == actor.battlePokemonId) updated else it }
        return state.derive(
            pokemon = pokemon,
        )
    }

    private fun canonical(value: String?): String = PublicIds.canonical(value.orEmpty())
}
