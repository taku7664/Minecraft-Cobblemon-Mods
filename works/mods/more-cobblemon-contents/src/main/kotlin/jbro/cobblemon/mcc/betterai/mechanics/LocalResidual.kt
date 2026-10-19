package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleShowdownHeldEffects
import jbro.cobblemon.mcc.internal.ai.BattleStateView

/**
 * The share of maximum HP a Pokemon's status takes at the end of each of the coming turns, read from the status's and
 * its ability's code ([BattleShowdownHeldEffects]): poison an eighth, burn a sixteenth, Toxic a sixteenth per stage
 * from the stage it has reached; nothing under an ability that takes no damage but from moves (Magic Guard), and a heal
 * under one that heals from the status instead (Poison Heal: negative shares). Null when the status takes nothing.
 * Weather and items are not read.
 */
internal object LocalResidual {
    fun schedule(state: BattleStateView, pokemon: BattlePokemonStateView, turns: Int): DoubleArray? {
        val status = pokemon.statusId?.takeIf { it.isNotBlank() } ?: return null
        if (turns <= 0) return null
        val ability = LocalPublicAbilityState.effectiveKnownAbility(state, pokemon)
        BattleShowdownHeldEffects.statusHealShare(ability, status)?.let { heal -> return DoubleArray(turns) { -heal } }
        if (BattleShowdownHeldEffects.blocksIndirectDamage(ability)) return null
        val residual = BattleShowdownHeldEffects.statusResidual(status) ?: return null
        if (!residual.grows) return DoubleArray(turns) { residual.share }
        val done = LocalBadPoisonCounter.seed(state)[pokemon.battlePokemonId] ?: 0
        return DoubleArray(turns) { k -> residual.share * minOf(done + k + 1, LocalBadPoisonCounter.MAXIMUM_BAD_POISON_TURN) }
    }
}
