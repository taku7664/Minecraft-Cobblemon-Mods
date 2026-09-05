package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import kotlin.math.ln
import kotlin.math.sqrt
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

/** Exactly two completed orientations. Null is an undecided game, never silently discarded. */
internal data class LocalPairOutcome(val id: String, val asCycleWinner: String?, val asOffenseWinner: String?) {
    init {
        require(id.isNotBlank())
        require(asCycleWinner in setOf(null, "cycle", "offense"))
        require(asOffenseWinner in setOf(null, "cycle", "offense"))
    }
    val scores: List<Double> get() = listOf(score(asCycleWinner, "cycle"), score(asOffenseWinner, "offense"))
    private fun score(winner: String?, challengerSide: String) = when (winner) {
        null -> 0.5
        challengerSide -> 1.0
        else -> 0.0
    }
}

internal data class LocalPairedSummary(
    val pairs: Int, val battles: Int, val wins: Int, val losses: Int, val draws: Int,
    val meanPairScore: Double, val intervalLower: Double, val intervalUpper: Double,
    val intervalMethod: String = "HOEFFDING_95_PAIR_LEVEL_FIXED_SAMPLE",
) {
    companion object {
        fun from(outcomes: List<LocalPairOutcome>): LocalPairedSummary {
            require(outcomes.isNotEmpty())
            require(outcomes.map { it.id }.distinct().size == outcomes.size) { "Duplicate team pairs" }
            val scores = outcomes.flatMap { it.scores }
            val mean = outcomes.map { it.scores.average() }.average()
            // Each pair mean is in [0,1]. Two-sided Hoeffding radius; n is pairs, not games.
            // Conditional on independently sampled pair outcomes and a predeclared sample count.
            // See README for source, sampling assumptions, and why fixed cases are excluded.
            val radius = sqrt(ln(2.0 / 0.05) / (2 * outcomes.size))
            return LocalPairedSummary(outcomes.size, scores.size, scores.count { it == 1.0 },
                scores.count { it == 0.0 }, scores.count { it == 0.5 }, mean,
                (mean - radius).coerceAtLeast(0.0), (mean + radius).coerceAtMost(1.0))
        }
    }
}
