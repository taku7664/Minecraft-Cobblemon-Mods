package jbro.cobblemon.morebattlecontent.betterai

import kotlin.math.ln
import kotlin.math.sqrt

internal enum class RootAllocationPolicy { UNIFORM, UCB }

internal data class RootAllocationResult(
    val chosen: String,
    val visits: Map<String, Int>,
    val means: Map<String, Double>,
)

/** Test-only root bandit, not MCTS or the product selector. No access to reference values. */
internal object RootAllocationProbe {
    fun run(
        actionIds: List<String>,
        samples: Int,
        policy: RootAllocationPolicy,
        sample: (String) -> Double,
    ): RootAllocationResult {
        require(actionIds.isNotEmpty() && actionIds.distinct().size == actionIds.size)
        require(samples >= actionIds.size)
        val ids = actionIds.sorted()
        val visits = ids.associateWith { 0 }.toMutableMap()
        val means = ids.associateWith { 0.0 }.toMutableMap()
        repeat(samples) { step ->
            val id = if (step < ids.size || policy == RootAllocationPolicy.UNIFORM) {
                ids[step % ids.size]
            } else {
                // Fixed exploration scale of one board unit, NOT a calibrated confidence interval.
                ids.maxBy { means.getValue(it) + sqrt(2.0 * ln(step.toDouble()) / visits.getValue(it)) }
            }
            val value = sample(id)
            require(value.isFinite())
            val count = visits.getValue(id) + 1
            visits[id] = count
            means[id] = means.getValue(id) + (value - means.getValue(id)) / count
        }
        return RootAllocationResult(ids.maxBy { means.getValue(it) }, visits.toMap(), means.toMap())
    }
}
