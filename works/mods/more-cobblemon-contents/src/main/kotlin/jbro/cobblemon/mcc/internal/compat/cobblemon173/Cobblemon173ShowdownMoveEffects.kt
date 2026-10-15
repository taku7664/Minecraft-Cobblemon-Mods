package jbro.cobblemon.mcc.internal.compat.cobblemon173

import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectsView
import jbro.cobblemon.mcc.internal.ai.BattleShowdownMoveEffects

/** Reads the exact Pokemon Showdown move bundle shipped inside Cobblemon ([BattleShowdownMoveEffects]). */
internal object Cobblemon173ShowdownMoveEffects {
    fun resolve(moveId: String): BattleMoveEffectsView? = BattleShowdownMoveEffects.resolve(moveId)
}
