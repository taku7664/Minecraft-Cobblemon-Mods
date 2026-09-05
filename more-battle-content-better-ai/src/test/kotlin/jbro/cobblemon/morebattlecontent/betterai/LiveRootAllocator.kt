package jbro.cobblemon.morebattlecontent.betterai

import java.util.Random
import kotlin.math.ln
import kotlin.math.sqrt

internal data class LiveRootSample(val value: Double, val koProbability: Double)
internal data class LiveRootResult(
    val chosen: String?,
    val attemptedSamples: Int,
    val completedSamples: Int,
    val coverageComplete: Boolean,
    val visits: Map<String, Int>,
    val means: Map<String, Double>,
    val worstObservedKoProbability: Map<String, Double>,
    val elapsedMillis: Double,
)

/** Test-only allocation. Coverage is over generated public replies, not hidden or future replies. */
internal object LiveRootAllocator {
    fun run(
        actionIds: List<String>, replyIds: List<String>, policy: RootAllocationPolicy,
        maxSamples: Int, timeMillis: Long, seed: Int,
        clockNanos: () -> Long = System::nanoTime,
        startedAtNanos: Long? = null,
        sample: (String, String, () -> Boolean) -> LiveRootSample?,
    ): LiveRootResult {
        require(actionIds.isNotEmpty() && actionIds.distinct().size == actionIds.size)
        require(replyIds.isNotEmpty() && replyIds.distinct().size == replyIds.size)
        require(maxSamples > 0 && timeMillis > 0)
        val start = startedAtNanos ?: clockNanos()
        fun hasTime() = (clockNanos() - start) / 1_000_000 < timeMillis
        val actions = actionIds.sorted()
        val replies = replyIds.sorted()
        val streams = actions.mapIndexed { i, id -> id to Random(seed.toLong() * 1_000_003 + i) }.toMap()
        val visits = actions.associateWith { 0 }.toMutableMap()
        val means = actions.associateWith { 0.0 }.toMutableMap()
        val risks = mutableMapOf<String, Double>()
        val guardSize = actions.size.toLong() * replies.size
        var attempted = 0
        var completed = 0
        while (attempted < maxSamples && hasTime()) {
            val guarding = completed < guardSize
            val action = when {
                guarding -> actions[completed / replies.size]
                policy == RootAllocationPolicy.UNIFORM -> actions[completed % actions.size]
                else -> actions.maxBy { means.getValue(it) + sqrt(2.0 * ln(completed.toDouble()) / visits.getValue(it)) }
            }
            val reply = if (guarding) replies[completed % replies.size]
                else replies[streams.getValue(action).nextInt(replies.size)]
            attempted++
            val value = sample(action, reply, ::hasTime) ?: break
            if (!hasTime()) break // A late partial calculation must not enter the statistics.
            require(value.value.isFinite() && value.koProbability.isFinite() && value.koProbability in 0.0..1.0)
            val count = visits.getValue(action) + 1
            visits[action] = count
            means[action] = means.getValue(action) + (value.value - means.getValue(action)) / count
            risks[action] = maxOf(risks[action] ?: 0.0, value.koProbability)
            completed++
        }
        val covered = completed >= guardSize
        return LiveRootResult(if (covered) actions.maxBy { means.getValue(it) } else null,
            attempted, completed, covered, visits, means, risks, (clockNanos() - start) / 1_000_000.0)
    }
}
