package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.state.LocalEndTurnStateProjector
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.math.abs

class EmbeddedStatusResidualTest {
    @Test
    fun `unknown max HP still applies recovery and damage in event order`() {
        val full = state("psn", 1.0, null, item = "leftovers")
        assertEquals(0.875, LocalEndTurnStateProjector.project(full).pokemon.single().hpFraction)
        val combined = state("psn", 0.5, null, ability = "poisonheal", item = "leftovers")
        assertEquals(0.5625, LocalEndTurnStateProjector.project(combined,
            saltCuredPokemonIds = setOf(POKEMON)).pokemon.single().hpFraction)
    }

    @Test
    fun `fractional expectations are not rounded to a fabricated integer HP`() {
        val source = state("psn", 0.1234, 199)
        assertEquals(0.1234 - 24.0 / 199,
            LocalEndTurnStateProjector.project(source).pokemon.single().hpFraction, 1e-15)
    }

    @Test
    fun `repeated integer ticks cannot leave a floating point ghost alive`() {
        for ((status, hp) in listOf("psn" to 72, "brn" to 36)) {
            var projected = state(status, hp / 199.0, 199)
            repeat(3) { projected = LocalEndTurnStateProjector.project(projected) }
            assertEquals(0.0, projected.pokemon.single().hpFraction, status)
            assertTrue(projected.pokemon.single().fainted, status)
            assertEquals(0, projected.remainingPokemonBySide.getValue(BattleSide.ALLY))
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "betterai.oracle", matches = "true")
    fun `native poison burn and toxic damage match exact HP and faint boundaries`(@TempDir directory: Path) {
        val oracle = EmbeddedShowdownOracle.statusResidual(directory)
        val cases = oracle.getAsJsonArray("cases")
        assertEquals(24, cases.size())
        assertEquals(24, cases.map { it.asJsonObject["id"].asString }.distinct().size)
        val mismatches = cases.mapNotNull { entry ->
            val case = entry.asJsonObject
            val input = case.getAsJsonObject("input")
            val native = case.getAsJsonObject("result")
            val maxHp = input["maxHp"].asInt
            val state = state(input["status"].asString, input["hp"].asDouble / maxHp, maxHp)
            val projected = LocalEndTurnStateProjector.project(state,
                mapOf(POKEMON to input["toxicTurn"].asInt))
            val own = projected.pokemon.single()
            val expected = native["hp"].asDouble / maxHp
            if (abs(own.hpFraction - expected) <= 1e-12 && own.fainted == native["fainted"].asBoolean &&
                projected.remainingPokemonBySide.getValue(BattleSide.ALLY) == if (own.fainted) 0 else 1) null
            else "${case["id"]}: expected=$expected actual=${own.hpFraction} fainted=${own.fainted}"
        }
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }

    @Test
    fun `regular poison aliases preserve fractional fallback without exact public max HP`() {
        for (status in listOf("psn", "poison", "cobblemon:poisoned")) {
            for (maxHp in listOf<Int?>(null, 200)) {
                val source = state(status, 1.0, maxHp, ranged = maxHp != null)
                assertEquals(0.875, LocalEndTurnStateProjector.project(source).pokemon.single().hpFraction, 1e-12)
            }
        }
    }

    @Test
    fun `known damage prevention and inactive pokemon still bypass poison`() {
        for (ability in listOf("magicguard", "poisonheal")) {
            val source = state("psn", 1.0, 235, ability = ability)
            assertEquals(1.0, LocalEndTurnStateProjector.project(source).pokemon.single().hpFraction)
        }
        assertEquals(1.0, LocalEndTurnStateProjector.project(state("psn", 1.0, 235, active = false))
            .pokemon.single().hpFraction)
    }

    private fun state(status: String, hp: Double, maxHp: Int?, ranged: Boolean = false,
        ability: String? = null, active: Boolean = true, item: String? = null): BattleStateView {
        val stats = maxHp?.let {
            BattleCombatStatRangesView(BattleIntegerRange(it, if (ranged) it + 50 else it),
                BattleIntegerRange(100, 100), BattleIntegerRange(100, 100), BattleIntegerRange(100, 100),
                BattleIntegerRange(100, 100), BattleIntegerRange(100, 100), BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE)
        }
        return BattleStateView(UUID(0, 800), BattleFormat.SINGLE, 1,
            listOf(BattlePokemonStateView(battlePokemonId = POKEMON, side = BattleSide.ALLY,
                activeSlot = if (active) 0 else null, speciesId = "fixture:status", formId = null, level = 50,
                hpFraction = hp, statusId = status, statStages = emptyMap(), knownMoveIds = emptySet(),
                knownAbilityId = ability, knownHeldItemId = item, fainted = false, combatStats = stats)),
            BattleFieldStateView.empty(), mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1), emptyList(), emptyList())
    }

    private companion object { val POKEMON = UUID(0, 801) }
}
