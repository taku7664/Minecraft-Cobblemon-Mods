package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.BattleStateView

/** Compatibility entrypoint for public berry Update after actions or residuals. */
internal object LocalPublicStatusBerry {
    fun afterUpdate(state: BattleStateView): BattleStateView = LocalBerryMechanics.afterUpdate(state)
}
