package jbro.cobblemon.morebattlecontent.betterai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.ln
import kotlin.math.sqrt

class LocalHeadToHeadEvidenceTest {
    @Test
    fun `tuning and difficulty runners both preserve turn limited pair outcomes`() {
        val tuning = jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning.CURRENT
        val tier = jbro.cobblemon.morebattlecontent.api.ai.BattleDifficultyProfiles.INTRODUCTORY
        val tallies = listOf(
            LocalSelfPlayMeasurement.headToHead("tuning", tuning, tuning, battles = 1, seed = 42, maximumTurns = 1),
            LocalSelfPlayMeasurement.tierDuel("tier", tier, tier, battles = 1, seed = 42, maximumTurns = 1),
        )
        tallies.forEach { tally ->
            assertEquals(2, tally.battles)
            assertEquals(2, tally.incomplete)
            assertEquals(0, tally.draws)
            assertEquals(0.0, tally.scoreLower)
            assertEquals(1.0, tally.scoreUpper)
        }
    }

    @Test
    fun `pair evidence retains split wins draws and unfinished games`() {
        val tally = LocalHeadToHeadTally("mixed", listOf(
            LocalHeadToHeadPair("sweep", LocalDuelOutcome.WIN, LocalDuelOutcome.WIN),
            LocalHeadToHeadPair("split", LocalDuelOutcome.WIN, LocalDuelOutcome.LOSS),
            LocalHeadToHeadPair("draw", LocalDuelOutcome.DRAW, LocalDuelOutcome.INCOMPLETE),
        ))
        assertEquals(6, tally.battles)
        assertEquals(3, tally.challengerWins)
        assertEquals(1, tally.defenderWins)
        assertEquals(1, tally.draws)
        assertEquals(1, tally.incomplete)
        assertEquals(2, tally.undecided)
        assertEquals(3.5 / 6, tally.scoreLower, 1e-12)
        assertEquals(4.5 / 6, tally.scoreUpper, 1e-12)
        assertTrue(tally.row().contains("incomplete=1"))
        assertTrue(tally.row().contains("NO_AUTOMATIC_ADOPTION"))
        assertFalse(tally.row().contains("+-"))
    }

    @Test
    fun `confidence uses number of team pairs and includes every split pair`() {
        val tally = LocalHeadToHeadTally("split", List(100) {
            LocalHeadToHeadPair("p$it", LocalDuelOutcome.WIN, LocalDuelOutcome.LOSS)
        })
        assertEquals(0.5, tally.scoreLower)
        assertEquals(0.5, tally.scoreUpper)
        assertEquals(0.5 - sqrt(ln(40.0) / 200), tally.intervalLower, 1e-12)
        assertEquals(0.5 + sqrt(ln(40.0) / 200), tally.intervalUpper, 1e-12)
        assertTrue(tally.row().contains("pairs=100"))
    }

    @Test
    fun `unfinished battles cannot masquerade as draws or precise half scores`() {
        val tally = LocalHeadToHeadTally("unfinished", List(100) {
            LocalHeadToHeadPair("p$it", LocalDuelOutcome.INCOMPLETE, LocalDuelOutcome.INCOMPLETE)
        })
        assertEquals(0, tally.draws)
        assertEquals(200, tally.incomplete)
        assertEquals(0.0, tally.scoreLower)
        assertEquals(1.0, tally.scoreUpper)
        assertEquals(0.0, tally.intervalLower)
        assertEquals(1.0, tally.intervalUpper)
    }

    @Test
    fun `invalid or duplicate evidence is rejected and caller mutation is isolated`() {
        val pair = LocalHeadToHeadPair("p", LocalDuelOutcome.WIN, LocalDuelOutcome.LOSS)
        assertThrows(IllegalArgumentException::class.java) { LocalHeadToHeadTally("empty", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { LocalHeadToHeadTally("duplicate", listOf(pair, pair)) }
        assertThrows(IllegalArgumentException::class.java) {
            LocalHeadToHeadPair("", LocalDuelOutcome.WIN, LocalDuelOutcome.LOSS)
        }
        val input = mutableListOf(pair)
        val tally = LocalHeadToHeadTally("snapshot", input)
        input.clear()
        assertEquals(2, tally.battles)
    }

    @Test
    fun `report adapter distinguishes swapped wins actual draws and turn limits`() {
        val definition = LocalTacticalScenarioDefinition("p", listOf("a"), listOf("b"), 42)
        fun report(winner: String?, stalled: Boolean = false) =
            LocalTacticalScenarioReport(definition, emptyList(), winner, stalled, 0, 0, 0, 0)
        val win = LocalHeadToHeadPair.fromReports(report("cycle"), report("offense"))
        assertEquals(LocalDuelOutcome.WIN, win.asCycle)
        assertEquals(LocalDuelOutcome.WIN, win.asOffense)
        val unresolved = LocalHeadToHeadPair.fromReports(report(null), report(null, true))
        assertEquals(LocalDuelOutcome.DRAW, unresolved.asCycle)
        assertEquals(LocalDuelOutcome.INCOMPLETE, unresolved.asOffense)
        assertThrows(IllegalArgumentException::class.java) {
            LocalHeadToHeadPair.fromReports(report("cycle", true), report(null))
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalHeadToHeadPair.fromReports(report("unexpected"), report(null))
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalHeadToHeadPair.fromReports(report(null), report(null).copy(definition = definition.copy(seed = 43)))
        }
    }
}
