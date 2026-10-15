package jbro.cobblemon.mcc.api.battle

/** Optional, battle-copy-only cap. It never raises an underlevelled Pokemon. */
object ManagedPlayerLevel {
    fun forBattle(original: Int, cap: Int?): Int {
        require(original in 1..100)
        require(cap == null || cap in 1..100)
        return if (cap == null) original else minOf(original, cap)
    }
}
