package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.PublicIds

/** Public Lum Berry consumption when Showdown runs Update after residuals and faint resolution. */
internal object LocalPublicStatusBerry {
    fun afterUpdate(state: BattleStateView): BattleStateView {
        var changed = false
        val pokemon = state.pokemon.map { holder ->
            if (holder.activeSlot == null || holder.fainted || holder.hpFraction <= 0.0 ||
                LocalPublicItemState.activeItemId(state, holder) != "lumberry") return@map holder
            val confused = holder.knownVolatileEffectIds.any { PublicIds.canonical(it) == "confusion" }
            if (holder.statusId.isNullOrEmpty() && !confused) return@map holder
            val unnerved = state.pokemon.any { foe ->
                foe.side != holder.side && foe.activeSlot != null && !foe.fainted && foe.hpFraction > 0.0 &&
                    LocalPublicAbilityState.effectiveKnownAbility(state, foe) in UNNERVE_ABILITIES
            }
            if (unnerved) return@map holder
            changed = true
            holder.copyState(statusId = null, knownHeldItemId = null,
                knownVolatileEffectIds = holder.knownVolatileEffectIds.filterNot { PublicIds.canonical(it) == "confusion" }.toSet())
        }
        return if (changed) state.copyState(pokemon = pokemon) else state
    }

    private val UNNERVE_ABILITIES = setOf("unnerve", "asoneglastrier", "asonespectrier")
}
