package jbro.cobblemon.mcc.internal.compat.cobblemon173

import jbro.cobblemon.mcc.internal.ai.BattleDeclarativeMoveEffects
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectsView

/** Keeps the existing compatibility entrypoint on the shared, non-executing parser. */
internal object ShowdownDeclarativeMoveParser {
    fun parse(source: String): Map<String, BattleMoveEffectsView> = BattleDeclarativeMoveEffects.parse(source)
}
