package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.evaluation.LocalPublicPositionFacts
import jbro.cobblemon.mcc.betterai.state.LocalEntryAbilityProjector
import jbro.cobblemon.mcc.betterai.state.LocalSwitchEntryEffectProjector

/** Revival is a replacement request, but it neither switches the user out nor enters a bench target. */
internal object LocalRevivalBlessing {
    fun applies(action: BattleActionCandidate) = "revival_blessing" in action.tags
    fun target(state: BattleStateView, side: BattleSide, action: BattleActionCandidate) =
        state.pokemon.firstOrNull { it.battlePokemonId == action.switchPokemonId && it.side == side && it.fainted }
    fun restoredHp(target: BattlePokemonStateView): Double {
        val hp = target.combatStats?.maxHp
        return if (hp != null && hp.minimum == hp.maximum) maxOf(1, hp.minimum / 2).toDouble() / hp.minimum else 0.5
    }
    fun project(state: BattleStateView, side: BattleSide, action: BattleActionCandidate, entryAbility: Boolean): BattleStateView {
        val target = target(state, side, action) ?: return state
        val restored = state.copyState(pokemon = state.pokemon.map {
            if (it.battlePokemonId == target.battlePokemonId) it.copyState(
                hpFraction = restoredHp(it), fainted = false, statusId = null, statStages = emptyMap(),
                knownVolatileEffectIds = emptySet(), actionConstraints = BattlePokemonActionConstraintView.empty(),
            ) else it
        })
        if (target.activeSlot == null) return restored
        val entered = LocalSwitchEntryEffectProjector.project(restored, target.battlePokemonId)
        return if (entryAbility) LocalEntryAbilityProjector.project(entered, target.battlePokemonId) else entered
    }
    fun score(action: BattleActionCandidate, context: BattleDecisionContext, tuning: LocalDecisionTuning): Double {
        val target = target(context.state, BattleSide.ALLY, action) ?: return 0.0
        val hp = restoredHp(target)
        val restored = target.copyState(hpFraction = hp, fainted = false, statusId = null)
        val exposure = LocalPublicPositionFacts.defensiveExposure(restored, context, action.actorSlot, tuning)
            ?: tuning.neutralHitHpFraction
        val survival = LocalPublicPositionFacts.survivalPosition(hp, exposure, tuning)
        return (tuning.livingPokemonValue + hp + survival * tuning.leafPressureWeight) * tuning.boardToScore
    }
}
