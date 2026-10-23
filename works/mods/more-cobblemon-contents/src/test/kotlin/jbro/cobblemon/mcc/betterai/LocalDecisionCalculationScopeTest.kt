package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.calculation.LocalDecisionCalculationScope as Scope
import jbro.cobblemon.mcc.betterai.evaluation.LocalActiveTuning
import jbro.cobblemon.mcc.betterai.simulation.EngineRuntimeDex
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.security.MessageDigest

class LocalDecisionCalculationScopeTest {
    @Test
    fun `reuse is bounded to identical inputs and tuning within one decision`() {
        val input = Any()
        var calls = 0
        fun read(value: Any = input) = Scope.reuse("probe", Scope.Identity(value)) { ++calls }
        Scope.with { scope ->
            assertEquals(1, read())
            assertEquals(1, read())
            assertEquals(2, read(Any()))
            LocalActiveTuning.with(LocalActiveTuning.current().copy(evolvingMatchups = true)) { assertEquals(3, read()) }
            assertEquals(1, read())
            assertEquals(2, scope.hits)
            assertEquals(3, scope.misses)
        }
        assertEquals(4, read())
        assertEquals(5, read())
    }

    @Test
    fun `failed and nested decisions release and restore their scope`() {
        var calls = 0
        fun read() = Scope.reuse("probe") { ++calls }
        Scope.with {
            assertEquals(1, read())
            assertThrows(IllegalStateException::class.java) { Scope.with { assertEquals(2, read()); error("probe") } }
            assertEquals(1, read())
        }
        assertEquals(3, read())
    }

    @Test
    fun `reuse has bounded retention and another decision thread cannot read it`() {
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            Scope.with { scope ->
                assertEquals("outer", Scope.reuse("thread") { "outer" })
                assertEquals("other", pool.submit<String> { Scope.reuse("thread") { "other" } }.get())
                assertEquals("outer", Scope.reuse("thread") { "wrong" })
                repeat(512) { index -> assertEquals(index, Scope.reuse("entry", index) { index }) }
                assertTrue(scope.values.size <= 128)
                assertEquals(0, Scope.reuse("entry", 0) { 0 })
            }
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `species hash keeps separators byte order and the exact first sixteen hex characters`() {
        val digest = EngineRuntimeDex.javaClass.getDeclaredMethod("digest", List::class.java).apply { isAccessible = true }
        val random = java.util.Random(748192)
        repeat(1024) {
            val data = List(random.nextInt(6)) { random.nextLong().toString() + "종\u0000" }
            val md = MessageDigest.getInstance("SHA-256")
            data.forEach { md.update(it.toByteArray(Charsets.UTF_8)); md.update(0) }
            val expected = md.digest().joinToString("") { "%02x".format(it) }.take(16)
            assertEquals(expected, digest.invoke(EngineRuntimeDex, data))
        }
    }

    @Test
    fun `species generation is read once and reload is visible in the next decision even after failure`() {
        val previous = EngineRuntimeDex.outsideGameSpecies
        var calls = 0
        var data: List<String>? = emptyList()
        EngineRuntimeDex.outsideGameSpecies = { calls++; data }
        try {
            assertThrows(IllegalStateException::class.java) {
                EngineRuntimeDex.withDecisionSnapshot {
                    val initial = EngineRuntimeDex.current()
                    data = null
                    assertSame(initial, EngineRuntimeDex.current())
                    assertEquals(1, calls)
                    error("probe")
                }
            }
            EngineRuntimeDex.withDecisionSnapshot {
                assertEquals("bundled", EngineRuntimeDex.current().first.substringBefore("+abilities="))
                assertEquals(2, calls)
            }
            EngineRuntimeDex.current()
            assertEquals(3, calls)
        } finally { EngineRuntimeDex.outsideGameSpecies = previous }
    }
}
