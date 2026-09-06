package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import kotlin.random.Random

internal enum class EvaluationSplit { TUNING, HOLDOUT }

/** v1 partitions unordered complete-set team pairs, not species, moves, or individual sets. */
internal object LocalEvaluationCorpus {
    fun key(definition: LocalTacticalScenarioDefinition): String {
        val teams = listOf(definition.cycleSetIds.sorted().joinToString(","),
            definition.offenseSetIds.sorted().joinToString(",")).sorted()
        return LocalBaselineCapture.digest("${definition.format}|${teams.joinToString("|")}".toByteArray(Charsets.UTF_8))
    }

    // Fixed regression bank is separate from random-population statistics. Reserved cases are not
    // recycled from the existing tuning scenario tests. This is not a secrecy boundary.
    fun scenarios(split: EvaluationSplit, format: BattleFormat): List<LocalTacticalScenarioDefinition> =
        LocalSelfPlayMeasurement.definitions(2, if (split == EvaluationSplit.TUNING) 6060201 else 6060299, format)
            .mapIndexed { index, definition -> definition.copy(name = "${split.name.lowercase()}-regression-${index + 1}") }

    fun split(definition: LocalTacticalScenarioDefinition): EvaluationSplit {
        val id = key(definition)
        for (partition in EvaluationSplit.entries) {
            if (scenarios(partition, definition.format).any { key(it) == id }) return partition
        }
        return if (id.take(8).toLong(16) % 5 == 0L) EvaluationSplit.HOLDOUT else EvaluationSplit.TUNING
    }

    fun sample(count: Int, seed: Int, format: BattleFormat, partition: EvaluationSplit): List<LocalTacticalScenarioDefinition> {
        require(count in 1..1000)
        require(format == BattleFormat.SINGLE || format == BattleFormat.DOUBLE)
        val reserved = EvaluationSplit.entries.flatMap { scenarios(it, format) }.map(::key).toSet()
        val accepted = linkedMapOf<String, LocalTacticalScenarioDefinition>()
        val random = Random(seed)
        // Bounded rejection sampling preserves fresh complete legal presets from the existing roster.
        repeat(count * 100) {
            val candidate = LocalSelfPlayMeasurement.definitions(1, random.nextInt(), format).single()
            val id = key(candidate)
            if (id !in reserved && split(candidate) == partition) {
                accepted.putIfAbsent(id, candidate.copy(name = "${partition.name.lowercase()}-${accepted.size + 1}"))
            }
            if (accepted.size == count) return accepted.values.toList()
        }
        error("Could not draw enough distinct team pairs in the requested partition")
    }
}

/** JSON projection of the same evidence used by console comparisons. */
internal data class LocalPairedSummary(
    val pairs: Int, val battles: Int, val wins: Int, val losses: Int, val draws: Int,
    val incomplete: Int, val meanPairScoreLower: Double, val meanPairScoreUpper: Double,
    val intervalLower: Double, val intervalUpper: Double,
    val intervalMethod: String = "HOEFFDING_95_PAIR_LEVEL_FIXED_SAMPLE",
    val intervalAssumption: String = "INDEPENDENT_REPRESENTATIVE_PAIRS_NOT_GUARANTEED_BY_PRNG",
) {
    companion object {
        fun from(outcomes: List<LocalHeadToHeadPair>): LocalPairedSummary {
            val tally = LocalHeadToHeadTally("JSON", outcomes)
            return LocalPairedSummary(outcomes.size, tally.battles, tally.challengerWins,
                tally.defenderWins, tally.draws, tally.incomplete, tally.scoreLower, tally.scoreUpper,
                tally.intervalLower, tally.intervalUpper)
        }
    }
}
