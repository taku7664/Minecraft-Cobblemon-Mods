package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.ln
import kotlin.math.sqrt

class LocalPairedEvaluationTest {
    @Test
    fun `JSON summary preserves unfinished evidence instead of awarding draws`() {
        val summary = LocalPairedSummary.from(listOf(
            LocalHeadToHeadPair("mixed", LocalDuelOutcome.DRAW, LocalDuelOutcome.INCOMPLETE),
        ))
        assertEquals(1, summary.draws)
        assertEquals(1, summary.incomplete)
        assertEquals(0.25, summary.meanPairScoreLower)
        assertEquals(0.75, summary.meanPairScoreUpper)
        val json = com.google.gson.Gson().toJsonTree(summary).asJsonObject
        assertFalse(json.has("meanPairScore"))
        assertEquals(1, json["incomplete"].asInt)
        assertEquals("INDEPENDENT_REPRESENTATIVE_PAIRS_NOT_GUARANTEED_BY_PRNG", json["intervalAssumption"].asString)
    }

    @Test
    fun `holdout requires explicit opt in before writing output`(@TempDir temporary: Path) {
        val output = temporary.resolve("should-not-exist")
        assertThrows(IllegalArgumentException::class.java) {
            LocalPairedEvaluationCapture.main(arrayOf(temporary.toString(), output.toString(), "1", "42", "2",
                "SINGLE", "INTRODUCTORY", "HOLDOUT", "CURRENT", "CURRENT", "false"))
        }
        assertFalse(Files.exists(output))
    }

    @Test
    fun `regression failures describe missing evidence and stalled progression`() {
        val definition = LocalSelfPlayMeasurement.definitions(1, 42).single()
        val report = LocalTacticalScenarioReport(definition, emptyList(), null, true, 0, 0, 0, 0)
        assertEquals(listOf("NO_TURNS", "NO_MOVE_EVIDENCE", "STALLED"), LocalPairedEvaluationCapture.failureConditions(report))
    }

    @Test
    fun `split identity ignores seed orientation and lead ordering`() {
        val original = LocalSelfPlayMeasurement.definitions(1, 42).single()
        val swapped = original.copy(name = "other", seed = 999,
            cycleSetIds = original.offenseSetIds.reversed(), offenseSetIds = original.cycleSetIds.reversed())
        assertEquals(LocalEvaluationCorpus.key(original), LocalEvaluationCorpus.key(swapped))
        assertEquals(LocalEvaluationCorpus.split(original), LocalEvaluationCorpus.split(swapped))
        assertNotEquals(LocalEvaluationCorpus.key(original), LocalEvaluationCorpus.key(original.copy(format = BattleFormat.DOUBLE)))
    }

    @Test
    fun `random cases are deterministic disjoint and exclude named regression cases`() {
        for (format in listOf(BattleFormat.SINGLE, BattleFormat.DOUBLE)) {
            val tuning = LocalEvaluationCorpus.sample(8, 42, format, EvaluationSplit.TUNING)
            val holdout = LocalEvaluationCorpus.sample(8, 42, format, EvaluationSplit.HOLDOUT)
            assertEquals(tuning, LocalEvaluationCorpus.sample(8, 42, format, EvaluationSplit.TUNING))
            assertEquals(8, tuning.map(LocalEvaluationCorpus::key).toSet().size)
            assertTrue(tuning.all { LocalEvaluationCorpus.split(it) == EvaluationSplit.TUNING })
            assertTrue(holdout.all { LocalEvaluationCorpus.split(it) == EvaluationSplit.HOLDOUT })
            assertTrue(tuning.map(LocalEvaluationCorpus::key).toSet().intersect(holdout.map(LocalEvaluationCorpus::key).toSet()).isEmpty())
            val fixtures = EvaluationSplit.entries.flatMap { LocalEvaluationCorpus.scenarios(it, format) }
            assertTrue((tuning + holdout).none { sampled -> fixtures.any { LocalEvaluationCorpus.key(it) == LocalEvaluationCorpus.key(sampled) } })
        }
    }

    @Test
    fun `pair summary keeps split wins and draws and uses pairs not battles`() {
        val result = LocalPairedSummary.from(listOf(
            LocalHeadToHeadPair("sweep", LocalDuelOutcome.WIN, LocalDuelOutcome.WIN),
            LocalHeadToHeadPair("split", LocalDuelOutcome.WIN, LocalDuelOutcome.LOSS),
            LocalHeadToHeadPair("draw", LocalDuelOutcome.DRAW, LocalDuelOutcome.DRAW),
        ))
        assertEquals(3, result.pairs)
        assertEquals(6, result.battles)
        assertEquals(3, result.wins)
        assertEquals(1, result.losses)
        assertEquals(2, result.draws)
        assertEquals(0, result.incomplete)
        assertEquals(2.0 / 3, result.meanPairScoreLower, 1e-12)
        assertEquals(result.meanPairScoreLower, result.meanPairScoreUpper)
        assertEquals(0.0, result.intervalLower)
        assertEquals(1.0, result.intervalUpper)
        val many = LocalPairedSummary.from(List(100) { LocalHeadToHeadPair("p$it", LocalDuelOutcome.WIN, LocalDuelOutcome.LOSS) })
        assertEquals(0.5 - sqrt(ln(40.0) / 200), many.intervalLower, 1e-12)
        assertEquals(0.5 + sqrt(ln(40.0) / 200), many.intervalUpper, 1e-12)
    }

    @Test
    fun `empty duplicate or invalid pair outcomes cannot become evidence`() {
        assertThrows(IllegalArgumentException::class.java) { LocalPairedSummary.from(emptyList()) }
        val pair = LocalHeadToHeadPair("duplicate", LocalDuelOutcome.DRAW, LocalDuelOutcome.DRAW)
        assertThrows(IllegalArgumentException::class.java) { LocalPairedSummary.from(listOf(pair, pair)) }
        assertThrows(IllegalArgumentException::class.java) { LocalHeadToHeadPair("", LocalDuelOutcome.WIN, LocalDuelOutcome.LOSS) }
        assertThrows(IllegalArgumentException::class.java) { LocalEvaluationCorpus.sample(0, 42, BattleFormat.SINGLE, EvaluationSplit.TUNING) }
    }
}
