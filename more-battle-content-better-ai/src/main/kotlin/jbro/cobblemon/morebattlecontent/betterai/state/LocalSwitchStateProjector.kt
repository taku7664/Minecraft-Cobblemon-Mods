package jbro.cobblemon.morebattlecontent.betterai.state

import jbro.cobblemon.morebattlecontent.api.ai.*

/** Applies the public, event-free part of a single switch for scoring and recursive projection. */
internal object LocalSwitchStateProjector {
    fun project(
        state: BattleStateView,
        side: BattleSide,
        action: BattleActionCandidate,
    ): BattleStateView {
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
                        hpFraction = projectedSwitchOutHp(pokemon),
                        statStages = emptyMap(),
                        formState = pokemon.stanceResetForm(),
                    )
                pokemon.battlePokemonId == incomingId -> {
                    val hp = (incoming.hpFraction - (action.facts?.switchEntryHpLossFraction ?: 0.0)).coerceAtLeast(0.0)
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
        val switched = BattleStateView(
            battleId = state.battleId,
            format = state.format,
            turn = state.turn,
            pokemon = next,
            field = state.field,
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
            observedEvents = state.observedEvents,
            inferences = state.inferences,
        )
        return LocalEntryAbilityProjector.project(switched, incomingId)
    }

    fun projectedSwitchOutHp(pokemon: BattlePokemonStateView): Double =
        if (canonical(pokemon.knownAbilityId) == "regenerator") {
            (pokemon.hpFraction + 1.0 / 3.0).coerceAtMost(1.0)
        } else {
            pokemon.hpFraction
        }

    private fun canonical(value: String?): String? = value
        ?.substringAfter(':')
        ?.lowercase()
        ?.filter(Char::isLetterOrDigit)

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
        knownTypeIds = formState?.knownTypeIds ?: knownTypeIds,
        combatStats = formState?.combatStats ?: combatStats,
        knownFormStates = knownFormStates,
        actionConstraints = BattlePokemonActionConstraintView.empty(),
    )
}
