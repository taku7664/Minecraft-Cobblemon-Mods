package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.calculation.PublicFutureActionFactory
import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.mcc.betterai.state.LocalSwitchStateProjector
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView

/** Two Pokemon placed in front of each other, benched ones switched in through the public switch projection. */
internal object LocalMatchupPosition {
    fun face(
        context: BattleDecisionContext,
        first: BattlePokemonStateView,
        second: BattlePokemonStateView,
        cache: LocalProjectedActionCalculationCache,
    ): BattleDecisionContext? {
        val withFirst = enter(context, first, cache) ?: return null
        return enter(withFirst, second, cache)
    }

    /**
     * [pokemon] switched in as the public projection has it: hazards and entry abilities applied, stages
     * reset. Already active, it is left where it is. [slot] picks the slot in doubles; otherwise the first.
     */
    fun enter(
        source: BattleDecisionContext,
        pokemon: BattlePokemonStateView,
        cache: LocalProjectedActionCalculationCache,
        slot: Int? = null,
    ): BattleDecisionContext? {
        val state = source.state
        if (state.pokemon.any { it.battlePokemonId == pokemon.battlePokemonId && it.activeSlot != null }) return source
        // Single-slot actions: in doubles the turn-level actions pair both slots, so no plain switch is among them.
        val action = cache.slotActions(state, pokemon.side, source.publicActionCatalog, includeMoveHypotheses = false) {
            PublicFutureActionFactory.slotActions(state, pokemon.side, source.publicActionCatalog)
        }.firstOrNull {
            it.kind == BattleActionKind.SWITCH && it.switchPokemonId == pokemon.battlePokemonId && (slot == null || it.actorSlot == slot)
        }
            // After an entry-hazard knockout there is nobody alive in front. The current-turn action factory has no
            // switch for this projected position; its replacement still enters by the same public switch projection.
            ?: if (!pokemon.fainted && pokemon.hpFraction > 0.0 && state.pokemon.any {
                it.side == pokemon.side && it.activeSlot == (slot ?: 0) && (it.fainted || it.hpFraction <= 0.0)
            } && state.pokemon.none {
                it.side == pokemon.side && it.activeSlot == (slot ?: 0) && !it.fainted && it.hpFraction > 0.0
            }) jbro.cobblemon.mcc.internal.ai.BattleActionCandidate("matchup:replacement:${pokemon.battlePokemonId}",
                BattleActionKind.SWITCH, actorSlot = slot ?: 0, switchPokemonId = pokemon.battlePokemonId) else return null
        val calculated = cache.getOrCalculate(state, pokemon.side, action, source.publicActionCatalog) {
            PublicBattleTacticalCalculator.calculate(source.copy(state = state, candidates = listOf(action)), pokemon.side)
        }
        val outgoingIds = state.pokemon.filter { it.side == pokemon.side && it.activeSlot == action.actorSlot }
            .mapTo(hashSetOf()) { it.battlePokemonId }
        val entered = LocalSwitchStateProjector.project(state, pokemon.side, calculated.candidates.single())
        return source.copy(state = entered, publicActionCatalog = source.publicActionCatalog.afterSwitch(outgoingIds))
    }
}
