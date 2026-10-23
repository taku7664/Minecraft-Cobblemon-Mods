package jbro.cobblemon.mcc.betterai

import java.nio.file.Files
import java.nio.file.Path
import jbro.cobblemon.mcc.betterai.config.*
import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LocalOptimizationConfigTest {
    @TempDir lateinit var directory: Path
    private val path get() = directory.resolve("betterai-optimizations.json")

    @Test fun `missing and empty configs keep both optimizations disabled`() {
        assertEquals(LocalDecisionTuning.CURRENT, LocalOptimizationConfigStore.loadOrCreate(path).applyTo(LocalDecisionTuning.CURRENT))
        Files.writeString(path, "{}")
        assertEquals(LocalOptimizationConfig(), LocalOptimizationConfigStore.loadOrCreate(path))
        assertFalse(LocalDecisionTuning.CURRENT.simpleFixedHpReactions)
        assertNull(LocalDecisionTuning.CURRENT.poisonEvaluationTurns)
    }
    @Test fun `each option is independent and saved changes affect the next snapshot`() {
        val first = LocalOptimizationConfig(simpleFixedHpReactions = true)
        LocalOptimizationConfigStore.save(path, first)
        val snapshot = LocalOptimizationConfigStore.loadOrCreate(path).applyTo(LocalDecisionTuning.CURRENT)
        assertTrue(snapshot.simpleFixedHpReactions); assertNull(snapshot.poisonEvaluationTurns)
        LocalOptimizationConfigStore.save(path, LocalOptimizationConfig(simplePoisonEvaluation = true, poisonEvaluationTurns = 3))
        val next = LocalOptimizationConfigStore.loadOrCreate(path).applyTo(LocalDecisionTuning.CURRENT)
        assertFalse(next.simpleFixedHpReactions); assertEquals(3, next.poisonEvaluationTurns)
        assertTrue(snapshot.simpleFixedHpReactions); assertNull(snapshot.poisonEvaluationTurns)
    }
    @Test fun `saving preserves unowned JSON fields and rejects malformed opt ins`() {
        Files.writeString(path, """{"futureOption":{"keep":true}}""")
        LocalOptimizationConfigStore.save(path, LocalOptimizationConfig())
        assertTrue(Files.readString(path).contains("futureOption"))
        for (invalid in listOf("""{"simpleFixedHpReactions":"true"}""", """{"poisonEvaluationTurns":0}""",
            """{"poisonEvaluationTurns":2.5}""")) {
            Files.writeString(path, invalid)
            assertThrows(Exception::class.java) { LocalOptimizationConfigStore.loadOrCreate(path) }
            assertEquals(invalid, Files.readString(path))
        }
    }
}
