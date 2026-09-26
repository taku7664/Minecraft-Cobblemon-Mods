package jbro.cobblemon.morebattlecontent.betterai.simulation

import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleLocalOpponentStatSpreadView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicStatRanges
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerTier
import jbro.cobblemon.morebattlecontent.betterai.mechanics.copyState

/** Tier-specific IV/EV knowledge, shared by public fallback scoring and native build worlds. */
internal object LocalOpponentStatAssumption {
    private val statIds = listOf("hp", "atk", "def", "spa", "spd", "spe")

    fun ivs(tier: BattleTrainerTier, exact: BattleLocalOpponentStatSpreadView?): Map<String, Int> =
        if (tier == BattleTrainerTier.ADVANCED || tier == BattleTrainerTier.BOSS) {
            exact?.ivs ?: perfectIvs()
        } else perfectIvs()

    fun evs(
        tier: BattleTrainerTier,
        baseStats: Map<String, Int>,
        exact: BattleLocalOpponentStatSpreadView?,
    ): Map<String, Int> = if (tier == BattleTrainerTier.BOSS && exact != null) {
        exact.evs
    } else {
        val top = statIds.sortedWith(compareByDescending<String> { baseStats.getValue(it) }.thenBy { statIds.indexOf(it) })
            .take(2).toSet()
        statIds.associateWith { if (it in top) 252 else 0 }
    }

    fun applyToPublicState(context: BattleDecisionContext, tier: BattleTrainerTier): BattleDecisionContext {
        val preview = context.opponentTeamPreview ?: return context
        val changed = context.state.pokemon.map { pokemon ->
            val level = pokemon.level
            if (pokemon.side != BattleSide.OPPONENT || level == null) return@map pokemon
            val matching = preview.pokemon.filter { slot ->
                slot.speciesId.equals(pokemon.speciesId, true) &&
                    (slot.formId == null || slot.formId.equals(pokemon.formId, true))
            }
            val slot = matching.singleOrNull() ?: return@map pokemon
            val base = slot.buildCandidatePool?.baseStats?.takeIf { it.isNotEmpty() } ?: return@map pokemon
            val exact = context.localOpponentStatSpreads[slot.previewSlotId]
            val stats = BattlePublicStatRanges.fromAssumedSpread(
                level, base, ivs(tier, exact), evs(tier, base, exact),
            )
            pokemon.copyState(combatStats = stats)
        }
        return context.copy(state = context.state.copyState(pokemon = changed))
    }

    private fun perfectIvs(): Map<String, Int> = statIds.associateWith { 31 }
}
