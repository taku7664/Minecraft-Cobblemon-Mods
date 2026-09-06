package jbro.cobblemon.morebattlecontent.betterai

import java.util.Locale
import kotlin.math.ln
import kotlin.math.sqrt

internal enum class LocalDuelOutcome(val lower: Double, val upper: Double) {
    WIN(1.0, 1.0), LOSS(0.0, 0.0), DRAW(0.5, 0.5), INCOMPLETE(0.0, 1.0),
}

internal data class LocalHeadToHeadPair(
    val id: String,
    val asCycle: LocalDuelOutcome,
    val asOffense: LocalDuelOutcome,
) {
    init { require(id.isNotBlank()) }

    companion object {
        fun fromReports(first: LocalTacticalScenarioReport, second: LocalTacticalScenarioReport): LocalHeadToHeadPair {
            require(first.definition == second.definition) { "Both orientations must use the same scenario and seed" }
            return LocalHeadToHeadPair(LocalEvaluationCorpus.key(first.definition),
                outcome(first, "cycle"), outcome(second, "offense"))
        }

        private fun outcome(report: LocalTacticalScenarioReport, challengerSide: String): LocalDuelOutcome {
            require(report.winner in setOf(null, "cycle", "offense"))
            require(!report.stalled || report.winner == null)
            return when {
                report.stalled -> LocalDuelOutcome.INCOMPLETE
                report.winner == null -> LocalDuelOutcome.DRAW
                report.winner == challengerSide -> LocalDuelOutcome.WIN
                else -> LocalDuelOutcome.LOSS
            }
        }
    }
}

/** Descriptive pair scores and a conditional fixed-sample bound, never an automatic quality verdict. */
internal class LocalHeadToHeadTally(val label: String, pairs: List<LocalHeadToHeadPair>) {
    private val pairs = pairs.toList()
    private val outcomes = this.pairs.flatMap { listOf(it.asCycle, it.asOffense) }
    init {
        require(this.pairs.isNotEmpty()) { "Empty evidence is not a 50 percent result" }
        require(this.pairs.map { it.id }.distinct().size == this.pairs.size) { "Duplicate team pairs" }
    }
    val battles = outcomes.size
    val challengerWins = outcomes.count { it == LocalDuelOutcome.WIN }
    val defenderWins = outcomes.count { it == LocalDuelOutcome.LOSS }
    val draws = outcomes.count { it == LocalDuelOutcome.DRAW }
    val incomplete = outcomes.count { it == LocalDuelOutcome.INCOMPLETE }
    /** Compatibility count only; the report keeps actual draws and unfinished games separate. */
    val undecided = draws + incomplete
    val scoreLower = this.pairs.map { (it.asCycle.lower + it.asOffense.lower) / 2 }.average()
    val scoreUpper = this.pairs.map { (it.asCycle.upper + it.asOffense.upper) / 2 }.average()
    // Two-sided Hoeffding radius for bounded pair means, not independent orientation results.
    // Requires independent representative pair outcomes and a predeclared sample count.
    private val radius = sqrt(ln(40.0) / (2 * this.pairs.size))
    val intervalLower = (scoreLower - radius).coerceAtLeast(0.0)
    val intervalUpper = (scoreUpper + radius).coerceAtMost(1.0)

    fun row(): String = String.format(Locale.ROOT,
        "%s pairs=%d n=%d challenger=%d defender=%d draws=%d incomplete=%d " +
            "pair_score=[%.4f,%.4f] conditional95=[%.4f,%.4f] " +
            "method=HOEFFDING_PAIR_FIXED_SAMPLE " +
            "assumption=INDEPENDENT_REPRESENTATIVE_PAIRS_NOT_GUARANTEED_BY_PRNG NO_AUTOMATIC_ADOPTION",
        label, pairs.size, battles, challengerWins, defenderWins, draws, incomplete,
        scoreLower, scoreUpper, intervalLower, intervalUpper)
}
