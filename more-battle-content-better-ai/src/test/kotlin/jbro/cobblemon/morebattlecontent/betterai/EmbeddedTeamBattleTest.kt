package jbro.cobblemon.morebattlecontent.betterai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import jbro.cobblemon.morebattlecontent.api.ai.*
import java.util.UUID

@EnabledIfSystemProperty(named = "betterai.oracle", matches = "true")
class EmbeddedTeamBattleTest {
    @Test
    fun `sampled complete teams receive both local brains through native battle end`(@TempDir directory: Path) {
        val audit = EmbeddedPresetAudit.run(directory.resolve("audit"), teamPairs = 1)
        val pair = audit.getAsJsonObject("teamSampling").getAsJsonArray("pairs")[0].asJsonObject
        val result = EmbeddedTeamBattle.run(directory.resolve("audit/engine"), pair, directory.resolve("battle"))
        assertEquals("COMPLETE", result["status"].asString)
        assertTrue(result["decisions"].asInt > 2)
        assertTrue(result.getAsJsonObject("decisionsBySide")["p1"].asInt > 0)
        assertTrue(result.getAsJsonObject("decisionsBySide")["p2"].asInt > 0)
        assertTrue(result["forcedSwitchDecisions"].asInt > 0)
        assertEquals(0, result["illegalChoices"].asInt)
        assertTrue(result.getAsJsonArray("publicLog").none { it.asString.contains("pp_update") })
    }

    @Test
    fun `unrevealed bench changes remain outside opponent input and illegal actions are never replaced`(@TempDir directory: Path) {
        val audit = EmbeddedPresetAudit.run(directory.resolve("audit"), teamPairs = 1)
        val pair = audit.getAsJsonObject("teamSampling").getAsJsonArray("pairs")[0].asJsonObject
        val changed = pair.deepCopy()
        changed.getAsJsonObject("p2").getAsJsonArray("sets")[2].asJsonObject.apply {
            getAsJsonArray("moves").set(0, JsonPrimitive("splash"))
            addProperty("item", "Heavy-Duty Boots")
            getAsJsonObject("ivs").addProperty("atk", 0)
        }
        fun first(value: JsonObject, name: String): JsonObject = EmbeddedTeamBattle.NativeSession(
            directory.resolve("audit/engine"), value, directory.resolve(name), 2).use { it.read() }
        val original = first(pair, "original")
        val variant = first(changed, "changed")
        fun request(frame: JsonObject, side: String) = frame.getAsJsonArray("requests").map { it.asJsonObject }.single { it["side"].asString == side }
        assertEquals(request(original, "p1"), request(variant, "p1"))
        assertNotEquals(request(original, "p2"), request(variant, "p2"))
        for (side in listOf("p1", "p2")) {
            val context = EmbeddedTeamInput.context(request(original, side), UUID(0, 1), original["turn"].asInt, 0)
            assertEquals(3, context.state.pokemon.count { it.side == BattleSide.ALLY })
            assertEquals(1, context.state.pokemon.count { it.side == BattleSide.OPPONENT })
            assertTrue(context.state.pokemon.filter { it.side == BattleSide.OPPONENT }.all {
                it.combatStats?.knowledge == BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE && it.knownMoveIds.isEmpty()
            })
            assertEquals(2, context.candidates.count { it.kind == BattleActionKind.SWITCH })
            assertTrue(context.candidates.filter { it.kind == BattleActionKind.SWITCH }.all { candidate ->
                context.state.pokemon.any { it.side == BattleSide.ALLY && it.battlePokemonId == candidate.switchPokemonId }
            })
            assertEquals(4, context.state.pokemon.map { it.battlePokemonId }.distinct().size,
                "Native 20-character nickname truncation must not merge UUID identities")
        }
        val observations = request(original, "p1").deepCopy()
        val opponentIdent = observations.getAsJsonArray("publicLog").map { it.asString }
            .first { it.startsWith("|switch|p2a:") }.split('|')[2]
        observations.getAsJsonArray("publicLog").apply {
            add("|-start|$opponentIdent|typechange|Water")
            add("|-boost|$opponentIdent|atk|2")
            add("|-damage|$opponentIdent|50/100 brn")
        }
        val observed = EmbeddedTeamInput.context(observations, UUID(0, 1), original["turn"].asInt, 0)
            .state.pokemon.single { it.side == BattleSide.OPPONENT }
        assertEquals(setOf("water"), observed.knownTypeIds)
        assertEquals(2, observed.statStages["attack"])
        assertEquals(0.5, observed.hpFraction)
        assertEquals("brn", observed.statusId)
        EmbeddedTeamBattle.NativeSession(directory.resolve("audit/engine"), pair, directory.resolve("invalid"), 2).use { native ->
            native.read()
            native.send(JsonObject().apply { add("choices", JsonObject().apply {
                addProperty("p1", "forfeit"); addProperty("p2", "move 1")
            }) })
            val failure = assertThrows(IllegalStateException::class.java) { native.read() }
            assertTrue(failure.message!!.contains("Action not exposed"))
        }
    }
}
