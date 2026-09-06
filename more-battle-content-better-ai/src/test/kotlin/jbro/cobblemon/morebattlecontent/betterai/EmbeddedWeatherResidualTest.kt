package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.JsonObject
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
class EmbeddedWeatherResidualTest {
    @Test
    fun `sand damage immunities recovery order and expiry match native turns`(@TempDir directory: Path) {
        val cases = EmbeddedShowdownOracle.weatherResidual(directory).getAsJsonArray("cases")
        assertEquals(120, cases.size())
        assertEquals(120, cases.map { it.asJsonObject["id"].asString }.distinct().size)
        val mismatches = cases.mapNotNull { entry ->
            val case = entry.asJsonObject
            val input = case.getAsJsonObject("input")
            val own = pokemon(input.getAsJsonObject("own"), BattleSide.ALLY)
            val opponent = pokemon(input.getAsJsonObject("opponent"), BattleSide.OPPONENT)
            val state = BattleStateView(UUID(0, 903), BattleFormat.SINGLE, 1, listOf(own, opponent),
                BattleFieldStateView.empty().let { field -> BattleFieldStateView(
                    weather = BattleTimedEffectView(input["weather"].asString, input["remainingTurns"].asInt),
                    terrain = null, roomEffects = emptyList(), globalEffects = emptyList(), sideConditions = field.sideConditions) },
                mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1), emptyList(), emptyList())
            val result = LocalEndTurnStateProjector.project(state)
            val actual = result.pokemon.first()
            val native = case.getAsJsonObject("result")
            val expectedHp = native["hp"].asDouble / input.getAsJsonObject("own")["maxHp"].asInt
            val expectedFainted = native["fainted"].asBoolean
            val expectedWeather = native["weather"].takeUnless { it.isJsonNull }?.asString
            val expectedDuration = native["remainingTurns"].takeUnless { it.isJsonNull }?.asInt
            if (abs(expectedHp - actual.hpFraction) <= 1e-12 && expectedFainted == actual.fainted &&
                result.remainingPokemonBySide.getValue(BattleSide.ALLY) == (if (expectedFainted) 0 else 1) &&
                result.field.weather?.effectId == expectedWeather && result.field.weather?.remainingTurns == expectedDuration) null
            else "${case["id"]}: expectedHp=$expectedHp actualHp=${actual.hpFraction} native=$native"
        }
        println("WEATHER_RESIDUAL cases=${cases.size()} mismatches=${mismatches.size}")
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }

    private fun pokemon(input: JsonObject, side: BattleSide): BattlePokemonStateView {
        fun nullable(name: String) = input[name]?.takeUnless { it.isJsonNull }?.asString
        val maxHp = input["maxHp"].asInt
        return BattlePokemonStateView(battlePokemonId = UUID(0, if (side == BattleSide.ALLY) 901 else 902),
            side = side, activeSlot = 0, speciesId = input["speciesId"].asString, formId = null,
            level = input["level"].asInt, hpFraction = input["hp"].asDouble / maxHp,
            statusId = nullable("status"), statStages = emptyMap(), knownMoveIds = emptySet(),
            knownAbilityId = nullable("ability"), knownHeldItemId = nullable("item"), fainted = false,
            knownTypeIds = input.getAsJsonArray("types").map { it.asString }.toSet(),
            combatStats = if (side == BattleSide.ALLY) BattleCombatStatRangesView.exact(maxHp, 100, 100, 100, 100, 100) else null)
    }
}
