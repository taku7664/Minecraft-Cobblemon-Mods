package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import jbro.cobblemon.morebattlecontent.api.ai.BattleDeclarativeMoveEffects
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectsView

/** Keeps the existing compatibility entrypoint on the shared, non-executing parser. */
internal object ShowdownDeclarativeMoveParser {
    fun parse(source: String): Map<String, BattleMoveEffectsView> = BattleDeclarativeMoveEffects.parse(source)
}
