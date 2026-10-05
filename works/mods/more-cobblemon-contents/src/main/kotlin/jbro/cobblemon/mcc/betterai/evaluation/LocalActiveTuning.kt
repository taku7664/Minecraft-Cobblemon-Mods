package jbro.cobblemon.mcc.betterai.evaluation

/**
 * The tuning of the decision this thread is making, for readers deep in the evaluation that are not handed one.
 * Only switches under measurement read it, so a duel can set one side's switch without threading it through every
 * caller; a reader outside a decision sees [LocalDecisionTuning.CURRENT].
 */
internal object LocalActiveTuning {
    private val active = ThreadLocal<LocalDecisionTuning?>()

    fun current(): LocalDecisionTuning = active.get() ?: LocalDecisionTuning.CURRENT

    fun <T> with(tuning: LocalDecisionTuning, block: () -> T): T {
        val previous = active.get()
        active.set(tuning)
        try {
            return block()
        } finally {
            active.set(previous)
        }
    }
}
