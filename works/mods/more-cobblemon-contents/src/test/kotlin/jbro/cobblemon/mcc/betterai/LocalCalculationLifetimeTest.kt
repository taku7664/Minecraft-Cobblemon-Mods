package jbro.cobblemon.mcc.betterai

import com.google.gson.JsonParser
import jbro.cobblemon.mcc.betterai.calculation.LocalDecisionCalculationScope as Scope
import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class LocalCalculationLifetimeTest {
    private fun context(): BattleDecisionContext {
        val input = javaClass.getResourceAsStream("/oracle/low-hp-belly-drum-input.json")!!.bufferedReader().use {
            JsonParser.parseReader(it).asJsonObject
        }
        return EmbeddedTeamInput.context(input, UUID(0, 43), 5, 0)
    }

    @Test fun `tables are decision owned and are not created outside a decision`() {
        val owner = Any()
        assertNull(Scope.table<String, Any>(owner))
        lateinit var table: MutableMap<String, Any>
        lateinit var retained: Scope.Scope
        Scope.with { scope ->
            retained = scope
            table = Scope.table(owner)!!
            table["board"] = Any()
            assertSame(table, Scope.table<String, Any>(owner))
            assertNotSame(table, Scope.table<String, Any>(Any()))
            Scope.reuse("probe") { Any() }
        }
        assertTrue(table.isEmpty())
        assertTrue(retained.tables.isEmpty())
        assertTrue(retained.values.isEmpty())
        assertNull(Scope.table<String, Any>(owner))
    }

    @Test fun `failed nested scopes release their tables and restore outer ownership`() {
        val owner = Any()
        lateinit var failedTable: MutableMap<String, Any>
        Scope.with {
            val outer = Scope.table<String, Any>(owner)!!
            outer["keep"] = Any()
            assertThrows(IllegalStateException::class.java) {
                Scope.with {
                    failedTable = Scope.table(owner)!!
                    assertNotSame(outer, failedTable)
                    failedTable["drop"] = Any()
                    error("probe")
                }
            }
            assertTrue(failedTable.isEmpty())
            assertSame(outer, Scope.table<String, Any>(owner))
            assertTrue(outer.containsKey("keep"))
        }
    }

    @Test fun `tactical memo releases contexts at the end of the decision`() {
        val context = context()
        val attack = context.candidates.first { it.kind == BattleActionKind.USE_MOVE &&
            it.moveDetails?.damageCategory in listOf(BattleMoveDamageCategory.PHYSICAL, BattleMoveDamageCategory.SPECIAL) }
        lateinit var scope: Scope.Scope
        Scope.with {
            scope = it
            val first = PublicBattleTacticalCalculator.conservativeDamageRollFractions(attack, context, BattleSide.ALLY)
            assertNotNull(first)
            assertSame(first, PublicBattleTacticalCalculator.conservativeDamageRollFractions(attack, context, BattleSide.ALLY))
            assertTrue(it.tables.values.any { table -> table.isNotEmpty() })
        }
        assertTrue(scope.tables.isEmpty())
    }

    @Test fun `closing a projection cache releases all retained inputs and allows a fresh calculation`() {
        val context = context()
        val action = context.candidates.first()
        val cache = LocalProjectedActionCalculationCache()
        var calculations = 0
        fun read() = cache.getOrCalculate(context.state, BattleSide.ALLY, action, context.publicActionCatalog) {
            calculations++; context
        }
        assertSame(context, read()); assertSame(context, read())
        assertEquals(1, calculations)
        cache.close()
        assertEmptyMaps(cache)
        assertEmptyMaps(cache.fingerprints)
        assertSame(context, read())
        assertEquals(2, calculations)
        cache.close()
    }

    @Test fun `exceptional projection exit still releases retained boards`() {
        val context = context()
        val cache = LocalProjectedActionCalculationCache()
        assertThrows(IllegalStateException::class.java) {
            cache.use {
                it.getOrCalculate(context.state, BattleSide.ALLY, context.candidates.first()) { context }
                it.fingerprints.keyOf(context.state)
                error("probe")
            }
        }
        assertEmptyMaps(cache)
        assertEmptyMaps(cache.fingerprints)
    }

    @Test fun `turn projection fingerprint table is released with its decision`() {
        val state = context().state
        val projector = jbro.cobblemon.mcc.betterai.outcome.PublicSingleTurnProjector
        val method = projector.javaClass.getDeclaredMethod("fingerprint", BattleStateView::class.java).apply { isAccessible = true }
        lateinit var retained: MutableMap<*, *>
        Scope.with { scope ->
            val first = method.invoke(projector, state)
            assertSame(first, method.invoke(projector, state))
            retained = scope.tables.values.single()
            assertEquals(1, retained.size)
        }
        assertTrue(retained.isEmpty())
    }

    @Test fun `last state memo releases normal and failed calculation references`() {
        val state = context().state
        val memo = jbro.cobblemon.mcc.betterai.mechanics.LocalStateMemo { Any() }
        val last = memo.javaClass.getDeclaredField("last").apply { isAccessible = true }
        Scope.with {
            assertSame(memo[state], memo[state])
            assertNotNull(last.get(memo))
        }
        assertNull(last.get(memo))
        assertThrows(IllegalStateException::class.java) {
            Scope.with { memo[state]; error("probe") }
        }
        assertNull(last.get(memo))
    }

    @Test fun `ending an older scope cannot erase another active scope memo`() {
        val state = context().state
        val other = state.derive(turn = state.turn + 1)
        val memo = jbro.cobblemon.mcc.betterai.mechanics.LocalStateMemo { Any() }
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        val ready = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        lateinit var future: java.util.concurrent.Future<Any>
        try {
            lateinit var newer: Any
            Scope.with {
                memo[state]
                future = pool.submit<Any> {
                    Scope.with {
                        newer = memo[other]
                        ready.countDown()
                        check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        assertSame(newer, memo[other])
                        newer
                    }
                }
                assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS))
            }
            assertSame(newer, memo[other])
            release.countDown()
            assertSame(newer, future.get(10, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    private fun assertEmptyMaps(owner: Any) {
        owner.javaClass.declaredFields.filter { java.util.Map::class.java.isAssignableFrom(it.type) }.forEach {
            it.isAccessible = true
            assertTrue((it.get(owner) as Map<*, *>).isEmpty(), it.name)
        }
    }
}
