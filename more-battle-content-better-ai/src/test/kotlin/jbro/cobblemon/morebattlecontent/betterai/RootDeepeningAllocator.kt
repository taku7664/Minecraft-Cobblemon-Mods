package jbro.cobblemon.morebattlecontent.betterai

internal enum class RootDeepeningPolicy { CANONICAL, SCORE_PRIORITY }
internal enum class RootDepthAcceptance { COMMON_DEPTH, LATEST_COMPLETED }
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
    val selectionScores: Map<String, Double>,
    val selectionDepth: Int?,
)

/** Test-only scheduling. Observations may differ in depth; default selection uses a complete depth. */
internal object RootDeepeningAllocator {
    fun run(
        actionIds: List<String>,
        policy: RootDeepeningPolicy,
        nodeBudget: Int,
        acceptance: RootDepthAcceptance = RootDepthAcceptance.COMMON_DEPTH,
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
        val shallowScores = scores.toMap()
        if (depths.size == ids.size) {
            val order = when (policy) {
                RootDeepeningPolicy.CANONICAL -> ids
                RootDeepeningPolicy.SCORE_PRIORITY -> ids.sortedWith(
                    compareByDescending<String> { scores.getValue(it) }.thenBy { it })
            }
            for (id in order) if (!visit(id, 2)) break
        }
        val covered = depths.size == ids.size
        val complete = covered && depths.values.all { it == 2 }
        val selectionScores = when {
            !covered -> emptyMap()
            acceptance == RootDepthAcceptance.COMMON_DEPTH && !complete -> shallowScores
            else -> scores.toMap()
        }
        val selectionDepth = when {
            !covered -> null
            complete -> 2
            acceptance == RootDepthAcceptance.COMMON_DEPTH || depths.values.all { it == 1 } -> 1
            else -> null // Legacy mixed-depth selection has no single accepted depth.
        }
        val chosen = if (!covered) null else ids.sortedWith(
            compareByDescending<String> { selectionScores.getValue(it) }.thenBy { it }).first()
        return RootDeepeningResult(chosen, scores.toMap(), depths.toMap(), used,
            complete, attempts.toList(), selectionScores, selectionDepth)
    }
}
