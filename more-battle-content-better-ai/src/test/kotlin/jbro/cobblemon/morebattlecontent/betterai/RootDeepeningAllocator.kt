package jbro.cobblemon.morebattlecontent.betterai

internal enum class RootDeepeningPolicy { CANONICAL, SCORE_PRIORITY }
internal data class RootDeepeningReading(
    val score: Double?,
    val nodes: Int,
    val executionProbability: Double? = null,
    val worstResponseHpRetention: Double? = null,
    val publicResponseIncomplete: Boolean? = null,
)
internal data class RootDeepeningAttempt(val actionId: String, val depth: Int, val reading: RootDeepeningReading)
internal data class RootDeepeningResult(
    val chosen: String?,
    val scores: Map<String, Double>,
    val depths: Map<String, Int>,
    val nodes: Int,
    val targetDepthComplete: Boolean,
    val attempts: List<RootDeepeningAttempt>,
)

/** Test-only two-depth scheduling; completed deeper scores replace shallow scores, never average them. */
internal object RootDeepeningAllocator {
    fun run(
        actionIds: List<String>,
        policy: RootDeepeningPolicy,
        nodeBudget: Int,
        evaluate: (String, Int, Int) -> RootDeepeningReading,
    ): RootDeepeningResult {
        require(actionIds.isNotEmpty() && actionIds.distinct().size == actionIds.size && nodeBudget > 0)
        val ids = actionIds.sorted()
        val scores = linkedMapOf<String, Double>()
        val depths = linkedMapOf<String, Int>()
        val attempts = mutableListOf<RootDeepeningAttempt>()
        var used = 0
        fun visit(id: String, depth: Int): Boolean {
            val remaining = nodeBudget - used
            if (remaining <= 0) return false
            val reading = evaluate(id, depth, remaining)
            require(reading.nodes in 0..remaining)
            require(reading.score == null || reading.score.isFinite())
            require(reading.executionProbability == null ||
                (reading.executionProbability.isFinite() && reading.executionProbability in 0.0..1.0))
            require(reading.worstResponseHpRetention == null ||
                (reading.worstResponseHpRetention.isFinite() && reading.worstResponseHpRetention in 0.0..1.0))
            used += reading.nodes
            attempts += RootDeepeningAttempt(id, depth, reading)
            val score = reading.score ?: return false
            scores[id] = score
            depths[id] = depth
            return true
        }
        for (id in ids) if (!visit(id, 1)) break
        if (depths.size == ids.size) {
            val order = when (policy) {
                RootDeepeningPolicy.CANONICAL -> ids
                RootDeepeningPolicy.SCORE_PRIORITY -> ids.sortedWith(
                    compareByDescending<String> { scores.getValue(it) }.thenBy { it })
            }
            for (id in order) if (!visit(id, 2)) break
        }
        // Deliberately provisional when depths differ: no claim of a certified depth-2 optimum.
        val chosen = if (depths.size != ids.size) null else ids.sortedWith(
            compareByDescending<String> { scores.getValue(it) }.thenBy { it }).first()
        return RootDeepeningResult(chosen, scores.toMap(), depths.toMap(), used,
            depths.size == ids.size && depths.values.all { it == 2 }, attempts.toList())
    }
}
