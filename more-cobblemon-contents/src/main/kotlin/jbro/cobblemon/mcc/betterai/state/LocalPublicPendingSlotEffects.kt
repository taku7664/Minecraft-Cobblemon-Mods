package jbro.cobblemon.mcc.betterai.state

import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.engine.dex.EngineDex
import jbro.cobblemon.mcc.betterai.mechanics.RecursiveDelayedStrike

/** Rehydrates observed slot effects without binding them to the Pokemon occupying that slot. */
internal object LocalPublicPendingSlotEffects {
    fun seed(state: BattleStateView): Pair<Set<Pair<BattleSide, Int>>, List<RecursiveDelayedStrike>> {
        val wishes = linkedSetOf<Pair<BattleSide, Int>>()
        val strikes = mutableListOf<RecursiveDelayedStrike>()
        state.field.sideConditions.forEach { (side, effects) ->
            effects.forEach { effect ->
                val slot = effect.targetSlot ?: return@forEach
                val remaining = effect.remainingTurns ?: return@forEach
                val id = effect.sourceMoveId?.let(PublicIds::canonical) ?: return@forEach
                if (id == "wish" && remaining == 1) wishes += side to slot
                if (id !in setOf("futuresight", "doomdesire")) return@forEach
                val source = state.pokemon.firstOrNull { it.battlePokemonId == effect.sourcePokemonId } ?: return@forEach
                val data = EngineDex.bundled().move(id) ?: return@forEach
                val details = BattleMoveCandidateView(
                    typeId = data.type, damageCategory = BattleMoveDamageCategory.SPECIAL,
                    power = data.basePower.toDouble(), accuracy = (data.accuracy as? Number)?.toDouble() ?: 100.0,
                    priority = data.priority, currentPp = data.pp,
                    targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
                )
                strikes += RecursiveDelayedStrike(source, source.side, side, slot, id, details, remaining)
            }
        }
        return wishes to strikes
    }

    /** Wish restores half of the wisher's maximum HP, expressed on the current recipient's HP scale. */
    fun wishHealingFractionsBySlot(state: BattleStateView): Map<Pair<BattleSide, Int>, Double> = buildMap {
        state.field.sideConditions.forEach { (side, effects) -> effects.forEach { effect ->
            if (effect.sourceMoveId?.let(PublicIds::canonical) != "wish" || effect.remainingTurns != 1) return@forEach
            val slot = effect.targetSlot ?: return@forEach
            val source = state.pokemon.firstOrNull { it.battlePokemonId == effect.sourcePokemonId } ?: return@forEach
            val target = state.pokemon.firstOrNull { it.side == side && it.activeSlot == slot && !it.fainted } ?: return@forEach
            val sourceHp = source.combatStats?.maxHp ?: return@forEach
            val targetHp = target.combatStats?.maxHp ?: return@forEach
            val sourceMidpoint = (sourceHp.minimum.toLong() + sourceHp.maximum) / 2.0
            val targetMidpoint = (targetHp.minimum.toLong() + targetHp.maximum) / 2.0
            if (targetMidpoint > 0.0) put(side to slot, kotlin.math.floor(sourceMidpoint / 2.0) / targetMidpoint)
        } }
    }

    fun wishSourceMaxHpBySlot(state: BattleStateView): Map<Pair<BattleSide, Int>, Double> = buildMap {
        state.field.sideConditions.forEach { (side, effects) -> effects.forEach { effect ->
            if (effect.sourceMoveId?.let(PublicIds::canonical) != "wish" || effect.remainingTurns != 1) return@forEach
            val slot = effect.targetSlot ?: return@forEach
            val source = state.pokemon.firstOrNull { it.battlePokemonId == effect.sourcePokemonId } ?: return@forEach
            val maximum = source.combatStats?.maxHp ?: return@forEach
            put(side to slot, (maximum.minimum.toLong() + maximum.maximum) / 2.0)
        } }
    }
}
