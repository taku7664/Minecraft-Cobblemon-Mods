package jbro.cobblemon.mcc.betterai.engine.hooks

import jbro.cobblemon.mcc.betterai.engine.effects.BaseConditions

/** Every group of ported handlers, installed once when the first hook is looked up. */
object EngineHookSets {
    val all: List<HookSet> = listOf(
        BaseConditions,
        jbro.cobblemon.mcc.betterai.engine.effects.TopMoves,
        jbro.cobblemon.mcc.betterai.engine.effects.TopAbilities,
        jbro.cobblemon.mcc.betterai.engine.effects.TopItems,
        jbro.cobblemon.mcc.betterai.engine.effects.PortMovesB,
        jbro.cobblemon.mcc.betterai.engine.effects.PortItemsB,
        jbro.cobblemon.mcc.betterai.engine.effects.PortAbilitiesA,
        jbro.cobblemon.mcc.betterai.engine.effects.PortAbilitiesA2,
        jbro.cobblemon.mcc.betterai.engine.effects.PortAbilitiesADeps,
        jbro.cobblemon.mcc.betterai.engine.effects.PortMovesA,
        jbro.cobblemon.mcc.betterai.engine.effects.PortMovesA2,
        jbro.cobblemon.mcc.betterai.engine.effects.PortAbilitiesB,
        jbro.cobblemon.mcc.betterai.engine.effects.PortItemsC,
        jbro.cobblemon.mcc.betterai.engine.effects.MaxMoves,
    )
}
