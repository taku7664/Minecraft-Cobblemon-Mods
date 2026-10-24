package jbro.cobblemon.mcc.betterai

import java.util.UUID
import kotlin.random.Random
import jbro.cobblemon.mcc.betterai.calculation.PublicFutureActionFactory
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalDoublesSlotFlattenTest {
    @Test
    fun `decision evidence capture restores nested and exceptional collectors`() {
        val records = jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals
        records.begin()
        try {
            val outer = records.capture {
                val inner = records.capture { records.record("inner", mapOf("value" to 2.0)); 7 }
                assertEquals(7, inner.value)
                inner.entries.forEach { (k, v) -> records.record(k, v) }
                try { records.capture<Unit> { throw IllegalStateException("fixture") } } catch (_: IllegalStateException) { }
                records.record("outer", mapOf("value" to 3.0))
            }
            assertEquals(emptyMap<String, Map<String, Double>>(), records.take())
            outer.entries.forEach { (k, v) -> records.record(k, v) }
            assertEquals(mapOf("inner" to mapOf("value" to 2.0), "outer" to mapOf("value" to 3.0)), records.take())
        } finally { records.clear() }
    }

    @Test
    fun `direct slot enumeration preserves brute force first appearances and switch conflicts`() {
        val random = Random(76491)
        repeat(1000) { trial ->
            val slots = (0..1).map { slot ->
                List(random.nextInt(0, 12)) { index ->
                    val switch = random.nextInt(0, 4)
                    BattleActionCandidate("$slot:${random.nextInt(0, 8)}", if (switch == 0) BattleActionKind.WAIT else BattleActionKind.SWITCH,
                        actorSlot = slot, switchPokemonId = if (switch == 0) null else UUID(0, switch.toLong()))
                }
            }
            val expected = slots[0].flatMap { first -> slots[1].filter { second ->
                first.switchPokemonId == null || first.switchPokemonId != second.switchPokemonId
            }.flatMap { second -> listOf(first, second) } }.distinctBy { it.actionId }
            // Candidate equality is reference equality: this also checks which duplicate supplies the value.
            assertEquals(expected, PublicFutureActionFactory.flattenLegalSlotActions(slots), "trial=$trial")
        }
    }
}
