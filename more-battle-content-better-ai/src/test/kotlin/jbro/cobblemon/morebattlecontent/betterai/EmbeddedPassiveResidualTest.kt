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

@EnabledIfSystemProperty(named = "betterai.oracle", matches = "true")
class EmbeddedPassiveResidualTest {
    @Test
    fun `ordered healing status and salt cure match native turns`(@TempDir directory: Path) {
        val oracle = EmbeddedShowdownOracle.passiveResidual(directory)
        val cases = oracle.getAsJsonArray("cases")
        assertEquals(54, cases.size())
        assertEquals(54, cases.map { it.asJsonObject["id"].asString }.distinct().size)
        val mismatches = cases.mapNotNull { entry ->
            val case = entry.asJsonObject
            val input = case.getAsJsonObject("input")
            fun nullable(name: String) = input[name]?.takeUnless { it.isJsonNull }?.asString
            val maxHp = input["maxHp"].asInt
            val id = UUID(0, 802)
            val own = BattlePokemonStateView(battlePokemonId = id, side = BattleSide.ALLY, activeSlot = 0,
                speciesId = input["speciesId"].asString, formId = null, level = input["level"].asInt,
                hpFraction = input["hp"].asDouble / maxHp, statusId = nullable("status"), statStages = emptyMap(),
                knownMoveIds = emptySet(), knownAbilityId = nullable("ability"), knownHeldItemId = nullable("item"),
                fainted = false, knownTypeIds = input.getAsJsonArray("types").map { it.asString }.toSet(),
                combatStats = BattleCombatStatRangesView.exact(maxHp, 100, 100, 100, 100, 100))
            val state = BattleStateView(UUID(0, 803), BattleFormat.SINGLE, 1, listOf(own), BattleFieldStateView.empty(),
                mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1), emptyList(), emptyList())
            val result = LocalEndTurnStateProjector.project(state,
                saltCuredPokemonIds = if (input["saltCure"].asBoolean) setOf(id) else emptySet())
            val actual = result.pokemon.single()
            val native = case.getAsJsonObject("result")
            val expectedHp = native["hp"].asDouble / maxHp
            val expectedFainted = native["fainted"].asBoolean
            if (abs(expectedHp - actual.hpFraction) <= 1e-12 && expectedFainted == actual.fainted &&
                result.remainingPokemonBySide.getValue(BattleSide.ALLY) == (if (expectedFainted) 0 else 1)) null
            else "${case["id"]}: expected=$expectedHp actual=${actual.hpFraction} expectedFainted=$expectedFainted actualFainted=${actual.fainted}"
        }
        println("PASSIVE_RESIDUAL cases=${cases.size()} mismatches=${mismatches.size}")
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }
}
