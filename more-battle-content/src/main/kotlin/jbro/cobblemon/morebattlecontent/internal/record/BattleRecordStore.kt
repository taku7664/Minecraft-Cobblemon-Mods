package jbro.cobblemon.morebattlecontent.internal.record

internal class BattleRecordStore(initialRecords: Collection<BattleRecordStats> = emptyList()) {
    private val records = LinkedHashMap<BattleRecordKey, BattleRecordStats>()

    init {
        initialRecords.forEach { record ->
            require(records.put(record.key, record.copyWithDetachedMetrics()) == null) {
                "Duplicate battle record key: ${record.key}"
            }
        }
    }

    @Synchronized
    fun get(key: BattleRecordKey): BattleRecordStats =
        records[key]?.copyWithDetachedMetrics() ?: BattleRecordStats(key)

    @Synchronized
    fun recordOutcome(key: BattleRecordKey, outcome: BattleRecordOutcome): BattleRecordStats =
        recordCompletedBattle(BattleRecordCompletion(key, outcome))

    @Synchronized
    fun recordCompletedBattle(completion: BattleRecordCompletion): BattleRecordStats =
        recordCompletedBattles(listOf(completion)).single()

    @Synchronized
    fun recordCompletedBattles(completions: List<BattleRecordCompletion>): List<BattleRecordStats> {
        require(completions.isNotEmpty()) { "At least one battle record completion is required" }
        require(completions.map(BattleRecordCompletion::key).distinct().size == completions.size) {
            "A record batch cannot update the same player category twice"
        }
        val updates = completions.map { completion -> completion.key to calculateCompletion(completion) }
        updates.forEach { (key, stats) -> records[key] = stats }
        return updates.map { it.second.copyWithDetachedMetrics() }
    }

    private fun calculateCompletion(completion: BattleRecordCompletion): BattleRecordStats {
        val progressMetrics = completion.progressMetrics.toMap()
        val bestMetrics = completion.bestMetrics.toMap()
        require(progressMetrics.values.all { it >= 0 }) { "Progress metrics must be non-negative" }
        require(bestMetrics.values.all { it >= 0 }) { "Best metrics must be non-negative" }

        val before = records[completion.key] ?: BattleRecordStats(completion.key)
        val afterOutcome = when (completion.outcome) {
            BattleRecordOutcome.WIN -> {
                val streak = Math.addExact(before.currentWinStreak, 1)
                before.copy(
                    totalWins = Math.addExact(before.totalWins, 1),
                    currentWinStreak = streak,
                    bestWinStreak = maxOf(before.bestWinStreak, streak),
                )
            }

            BattleRecordOutcome.LOSS -> before.copy(
                totalLosses = Math.addExact(before.totalLosses, 1),
                currentWinStreak = 0,
            )
        }

        val mergedBestMetrics = bestMetrics.entries.fold(afterOutcome.bestMetrics) { metrics, (metricId, candidate) ->
            val current = metrics[metricId]
            if (current == null || candidate > current) metrics + (metricId to candidate) else metrics
        }
        val after = afterOutcome.copy(
            progressMetrics = afterOutcome.progressMetrics + progressMetrics,
            bestMetrics = mergedBestMetrics,
        )
        return after
    }

    @Synchronized
    fun setProgressMetric(key: BattleRecordKey, metricId: BattleRecordMetricId, value: Long): BattleRecordStats {
        require(value >= 0) { "Progress metric value must be non-negative" }
        val before = records[key] ?: BattleRecordStats(key)
        val after = before.copy(progressMetrics = before.progressMetrics + (metricId to value))
        records[key] = after
        return after.copyWithDetachedMetrics()
    }

    @Synchronized
    fun submitBestMetric(key: BattleRecordKey, metricId: BattleRecordMetricId, candidate: Long): BattleRecordStats {
        require(candidate >= 0) { "Best metric candidate must be non-negative" }
        val before = records[key] ?: BattleRecordStats(key)
        val currentBest = before.bestMetrics[metricId]
        if (currentBest != null && currentBest >= candidate) return before.copyWithDetachedMetrics()

        val after = before.copy(bestMetrics = before.bestMetrics + (metricId to candidate))
        records[key] = after
        return after.copyWithDetachedMetrics()
    }

    @Synchronized
    fun all(category: BattleRecordCategory? = null): List<BattleRecordStats> = records.values
        .asSequence()
        .filter { category == null || it.key.category == category }
        .sortedWith(
            compareBy<BattleRecordStats>(
                { it.key.category.contentId },
                { it.key.category.formatId },
                { it.key.playerId.toString() },
            ),
        )
        .map { it.copyWithDetachedMetrics() }
        .toList()

    private fun BattleRecordStats.copyWithDetachedMetrics(): BattleRecordStats = copy(
        progressMetrics = progressMetrics.toMap(),
        bestMetrics = bestMetrics.toMap(),
    )
}
