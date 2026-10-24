package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.simulation.EngineRuntimeDex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class EngineRuntimeDexSnapshotTest {
    @Test
    fun `one preparation snapshot reads the species supplier once and releases it afterwards`() {
        val previous = EngineRuntimeDex.outsideGameSpecies
        var reads = 0
        EngineRuntimeDex.outsideGameSpecies = { reads++; null }
        try {
            repeat(32) { EngineRuntimeDex.current() }
            assertEquals(32, reads)
            reads = 0
            EngineRuntimeDex.withDecisionSnapshot {
                val first = EngineRuntimeDex.current()
                repeat(32) { assertSame(first, EngineRuntimeDex.current()) }
            }
            assertEquals(1, reads)
            EngineRuntimeDex.current()
            assertEquals(2, reads)
            assertThrows(IllegalStateException::class.java) {
                EngineRuntimeDex.withDecisionSnapshot {
                    EngineRuntimeDex.current()
                    error("failed inference")
                }
            }
            EngineRuntimeDex.current()
            assertEquals(4, reads)
        } finally {
            EngineRuntimeDex.outsideGameSpecies = previous
        }
    }
}
