package jbro.cobblemon.morebattlecontent.api.ai

import java.util.Collections

/** Server-only Boss insight. Never attach this to the primary Brain or a public preview. */
class BattleLocalOpponentStatSpreadView(ivs: Map<String, Int>, evs: Map<String, Int>) {
    val ivs: Map<String, Int> = Collections.unmodifiableMap(LinkedHashMap(ivs))
    val evs: Map<String, Int> = Collections.unmodifiableMap(LinkedHashMap(evs))

    init {
        val stats = setOf("hp", "atk", "def", "spa", "spd", "spe")
        require(this.ivs.keys == stats && this.ivs.values.all { it in 0..31 })
        require(this.evs.keys == stats && this.evs.values.all { it in 0..252 } && this.evs.values.sum() <= 510)
    }
}
