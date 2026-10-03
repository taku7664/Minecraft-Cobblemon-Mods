package jbro.cobblemon.mcc.betterai.state

import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicAbilityState
import jbro.cobblemon.mcc.betterai.mechanics.LocalPersistentMoveState
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.betterai.mechanics.LocalRevivalBlessing

/** Applies the public, event-free part of a single switch for scoring and recursive projection. */
internal object LocalSwitchStateProjector {
    fun project(
        state: BattleStateView,
        side: BattleSide,
        action: BattleActionCandidate,
        /** Off when other Pokemon switch in on the same turn: abilities wait until every one is in. */
        entryAbility: Boolean = true,
        passSource: BattlePokemonStateView? = null,
        shedTail: Boolean = false,
    ): BattleStateView {
        if (LocalRevivalBlessing.applies(action)) return LocalRevivalBlessing.project(state, side, action, entryAbility)
        val incomingId = action.switchPokemonId ?: return state
        val incoming = state.pokemon.firstOrNull {
            it.battlePokemonId == incomingId && it.side == side && !it.fainted
        } ?: return state
        val slot = action.actorSlot ?: 0
        val next = state.pokemon.map { pokemon ->
            when {
                pokemon.side == side && pokemon.activeSlot == slot ->
                    pokemon.copyForSwitch(
                        activeSlot = null,
                        hpFraction = projectedSwitchOutHp(state, pokemon),
                        statStages = emptyMap(),
                        formState = pokemon.stanceResetForm(),
                        // Natural Cure heals its status on the way out.
                        statusId = if (LocalPublicAbilityState.effectiveKnownAbility(state, pokemon) == "naturalcure") null
                            else pokemon.statusId,
                        // The copied ability resolves its switch-out effects before the permanent ability returns.
                        knownAbilityId = pokemon.knownBaseAbilityId ?: pokemon.knownAbilityId,
                    )
                pokemon.battlePokemonId == incomingId -> {
                    val hp = incoming.hpFraction
                    pokemon.copyForSwitch(
                        activeSlot = slot,
                        hpFraction = hp,
                        statStages = emptyMap(),
                        fainted = hp <= 0.0,
                    )
                }
                else -> pokemon
            }
        }
        val switched = state.derive(
            pokemon = next,
            remainingPokemonBySide = BattleSide.entries.associateWith { currentSide ->
                val previousKnownLiving = state.pokemon.count {
                    it.side == currentSide && !it.fainted && it.hpFraction > 0.0
                }
                val nextKnownLiving = next.count {
                    it.side == currentSide && !it.fainted && it.hpFraction > 0.0
                }
                (state.remainingPokemonBySide.getValue(currentSide) + nextKnownLiving - previousKnownLiving)
                    .coerceAtLeast(0)
            },
        )
        val transferred = if (passSource != null) LocalPersistentMoveState.pass(switched, passSource, incomingId, shedTail) else switched
        val afterWish = LocalPersistentMoveState.afterSwitch(transferred, incomingId)
        val hpLoss = action.facts?.switchEntryHpLossFraction ?: 0.0
        val damaged = afterWish.copyState(pokemon = afterWish.pokemon.map { pokemon ->
            if (pokemon.battlePokemonId != incomingId) pokemon else {
                val hp = (pokemon.hpFraction - hpLoss).coerceAtLeast(0.0)
                pokemon.copyState(hpFraction = hp, fainted = hp <= 0.0)
            }
        })
        val afterHazards = LocalSwitchEntryEffectProjector.project(damaged, incomingId)
        return if (entryAbility) LocalEntryAbilityProjector.project(afterHazards, incomingId) else afterHazards
    }

    fun projectedSwitchOutHp(state: BattleStateView, pokemon: BattlePokemonStateView): Double =
        if (LocalPublicAbilityState.effectiveKnownAbility(state, pokemon) == "regenerator") {
            (pokemon.hpFraction + 1.0 / 3.0).coerceAtMost(1.0)
        } else {
            pokemon.hpFraction
        }

    private fun canonical(value: String?): String? = value?.let(PublicIds::canonical)

    private fun BattlePokemonStateView.stanceResetForm(): BattlePokemonFormStateView? {
        if (canonical(speciesId) != "aegislash" || canonical(knownAbilityId) != "stancechange") return null
        return knownFormStates.values.firstOrNull { !canonical(it.formId).orEmpty().contains("blade") }
    }

    private fun BattlePokemonStateView.copyForSwitch(
        activeSlot: Int? = this.activeSlot,
        hpFraction: Double = this.hpFraction,
        statStages: Map<String, Int> = this.statStages,
        fainted: Boolean = this.fainted,
        formState: BattlePokemonFormStateView? = null,
        statusId: String? = this.statusId,
        knownAbilityId: String? = this.knownAbilityId,
    ) = BattlePokemonStateView(
        battlePokemonId = battlePokemonId,
        side = side,
        activeSlot = activeSlot,
        speciesId = speciesId,
        formId = formState?.formId ?: formId,
        level = level,
        hpFraction = hpFraction,
        statusId = statusId,
        statStages = statStages,
        knownMoveIds = knownMoveIds,
        knownAbilityId = knownAbilityId,
        knownHeldItemId = knownHeldItemId,
        fainted = fainted,
        knownTypeIds = if (knownTeraTypeId == null) formState?.knownTypeIds ?: knownTypeIds else knownTypeIds,
        combatStats = formState?.combatStats ?: combatStats,
        knownFormStates = knownFormStates,
        actionConstraints = BattlePokemonActionConstraintView.empty(),
        knownVolatileEffectIds = knownVolatileEffectIds.filterTo(linkedSetOf()) { it.startsWith("better_ai:last_consumed_item=") },
        knownBaseStabTypeIds = formState?.knownTypeIds ?: knownBaseStabTypeIds,
        knownTeraTypeId = knownTeraTypeId,
        knownStellarBoostedTypeIds = knownStellarBoostedTypeIds,
        knownBaseAbilityId = knownBaseAbilityId,
    )
}
