package jbro.cobblemon.mcc.betterai.matchup

/** Optional per-thread measurement, inactive during normal gameplay. Counts actual calculator entries. */
internal object LocalHpMatchupAudit {
    data class Counts(var tacticalCalls: Int = 0, var rollCalculations: Int = 0, var hpHits: Int = 0,
        var futureCalls: Int = 0, var futureTacticalCalls: Int = 0, var futureRollCalculations: Int = 0,
        var seedRollCalculations: Int = 0, var seeds: Int = 0, var simpleExchanges: Int = 0,
        @Transient var futureDepth: Int = 0, @Transient var seedDepth: Int = 0)
    private val active = ThreadLocal<Counts?>()
    fun begin() { active.set(Counts()) }
    fun take(): Counts = (active.get() ?: Counts()).also { active.remove() }
    fun tacticalCall() { active.get()?.let { it.tacticalCalls++; if (it.futureDepth > 0 && it.seedDepth == 0) it.futureTacticalCalls++ } }
    fun rollCalculation() { active.get()?.let { it.rollCalculations++; if (it.seedDepth > 0) it.seedRollCalculations++ else if (it.futureDepth > 0) it.futureRollCalculations++ } }
    fun <T> future(block: () -> T): T {
        val counts = active.get() ?: return block()
        counts.futureCalls++; counts.futureDepth++
        try { return block() } finally { counts.futureDepth-- }
    }
    fun <T> seed(block: () -> T): T {
        val counts = active.get() ?: return block()
        counts.seeds++; counts.seedDepth++
        try { return block() } finally { counts.seedDepth-- }
    }
    fun simpleExchange() { active.get()?.let { it.simpleExchanges++ } }
    fun reused() { active.get()?.let { it.hpHits++ } }
}
