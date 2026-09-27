package jbro.cobblemon.mcc.betterai.engine.hooks

import jbro.cobblemon.mcc.betterai.engine.effects.BaseConditions

/** Every group of ported handlers, installed once when the first hook is looked up. */
object EngineHookSets {
    val all: List<HookSet> = listOf(
        BaseConditions,
        jbro.cobblemon.mcc.betterai.engine.effects.TopMoves,
        jbro.cobblemon.mcc.betterai.engine.effects.TopAbilities,
        jbro.cobblemon.mcc.betterai.engine.effects.TopItems,
        jbro.cobblemon.mcc.betterai.engine.effects.PortAbilitiesB,
        jbro.cobblemon.mcc.betterai.engine.effects.PortItemsC,
    )
}
