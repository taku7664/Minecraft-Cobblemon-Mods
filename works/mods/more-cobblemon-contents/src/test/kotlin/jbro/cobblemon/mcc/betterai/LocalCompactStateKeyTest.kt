package jbro.cobblemon.mcc.betterai

import com.google.gson.JsonParser
import jbro.cobblemon.mcc.betterai.calculation.LocalDecisionCalculationScope
import jbro.cobblemon.mcc.betterai.mechanics.LocalBattleStateFingerprint
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class LocalCompactStateKeyTest {
    private fun state(): BattleStateView {
        val input = javaClass.getResourceAsStream("/oracle/low-hp-belly-drum-input.json")!!.bufferedReader().use {
            JsonParser.parseReader(it).asJsonObject
        }
        return EmbeddedTeamInput.context(input, UUID(0, 43), 5, 0).state
    }

    @Test fun `compact keys retain every byte of full HP free and action band keys`() {
        val source = state()
        val keys = LocalBattleStateFingerprint()
        for (member in source.pokemon) for (hp in listOf(0.0, 0.01, 0.49, 0.5, 0.99, 1.0)) {
            val projected = source.copyState(pokemon = source.pokemon.map {
                if (it === member) it.copyState(hpFraction = hp, fainted = hp == 0.0) else it
            })
            assertEquals(keys.of(projected), keys.keyOf(projected).toString())
            assertEquals(keys.ofWithoutHp(projected), keys.keyWithoutHp(projected).toString())
            assertEquals(keys.ofActionInputs(projected), keys.keyOfActionInputs(projected).toString())
            val reordered = projected.copyState(pokemon = projected.pokemon.reversed())
            assertEquals(keys.keyOf(projected), LocalBattleStateFingerprint().keyOf(reordered))
        }
    }

    @Test fun `hash collisions never equate different keys`() {
        val a = LocalBattleStateFingerprint.Key(listOf("Aa"))
        val b = LocalBattleStateFingerprint.Key(listOf("BB"))
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, b)
        assertEquals(2, hashMapOf(a to 1, b to 2).size)
    }

    @Test fun `full keys distinguish HP and ranks while HP free keys retain ranks and fainting`() {
        val state = state()
        val keys = LocalBattleStateFingerprint()
        val user = state.pokemon.first()
        fun change(hp: Double, rank: Int = 0) = state.copyState(pokemon = state.pokemon.map {
            if (it === user) it.copyState(hpFraction = hp, fainted = hp == 0.0, statStages = mapOf("attack" to rank)) else it
        })
        assertNotEquals(keys.keyOf(change(0.2)), keys.keyOf(change(0.8)))
        assertEquals(keys.keyWithoutHp(change(0.2)), keys.keyWithoutHp(change(0.8)))
        assertNotEquals(keys.keyWithoutHp(change(0.2)), keys.keyWithoutHp(change(0.2, 1)))
        assertNotEquals(keys.keyWithoutHp(change(0.2)), keys.keyWithoutHp(change(0.0)))
    }

    @Test fun `different fingerprint instances share evidence only within the same decision`() {
        val state = state()
        val field = LocalBattleStateFingerprint.Key::class.java.getDeclaredField("pieces").apply { isAccessible = true }
        fun evidence(key: LocalBattleStateFingerprint.Key) = (field.get(key) as List<*>).last()
        LocalDecisionCalculationScope.with {
            val a = LocalBattleStateFingerprint().keyOf(state)
            val b = LocalBattleStateFingerprint().keyOf(state.copyState(turn = state.turn + 1))
            assertSame(evidence(a), evidence(b))
            assertNotEquals(a, b)
        }
    }
}
