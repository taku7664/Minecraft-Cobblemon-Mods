package jbro.cobblemon.mcc.betterai.matchup

/** Optional per-thread measurement, inactive during normal gameplay. Counts actual calculator entries. */
internal object LocalHpMatchupAudit {
    data class Counts(var tacticalCalls: Int = 0, var rollCalculations: Int = 0, var hpHits: Int = 0)
    private val active = ThreadLocal<Counts?>()
    fun begin() { active.set(Counts()) }
    fun take(): Counts = (active.get() ?: Counts()).also { active.remove() }
    fun tacticalCall() { active.get()?.let { it.tacticalCalls++ } }
    fun rollCalculation() { active.get()?.let { it.rollCalculations++ } }
    fun reused() { active.get()?.let { it.hpHits++ } }
}
