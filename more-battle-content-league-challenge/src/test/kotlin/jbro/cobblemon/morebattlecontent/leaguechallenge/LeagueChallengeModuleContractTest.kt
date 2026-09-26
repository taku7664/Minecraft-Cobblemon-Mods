package jbro.cobblemon.morebattlecontent.leaguechallenge

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class LeagueChallengeModuleContractTest {
    private val resources = Path.of("src/main/resources")

    @Test
    fun `metadata declares the agreed module identity and entrypoints`() {
        val metadata = json(resources.resolve("fabric.mod.json"))

        assertEquals("cobblemon_more_battle_content_league_challenge", metadata["id"].asString)
        assertEquals("Cobblemon: More Battle Content - League Challenge", metadata["name"].asString)
        assertEquals("*", metadata["environment"].asString)
        assertEquals(
            "jbro.cobblemon.morebattlecontent.leaguechallenge.MoreBattleContentLeagueChallenge",
            entrypoint(metadata, "main")
        )
        assertEquals(
            "jbro.cobblemon.morebattlecontent.leaguechallenge.client.MoreBattleContentLeagueChallengeClient",
            entrypoint(metadata, "client")
        )
    }

    @Test
    fun `system declares approved badge and level cap providers and required MBC API version`() {
        val dependencies = json(resources.resolve("fabric.mod.json")).getAsJsonObject("depends")

        assertEquals(">=1.6.22 <2.0.0", dependencies["cobblemon_more_battle_content"].asString)
        assertEquals(">=1.8.1 <1.9.0", dependencies["cobblemon"].asString)
        assertEquals(">=1.6.1 <1.7.0", dependencies["pokebadges"].asString)
        assertEquals(">=1.2.0 <1.3.0", dependencies["cobbled_level_control"].asString)
    }

    @Test
    fun `english and korean translations expose the same bootstrap keys`() {
        val english = language("en_us")
        val korean = language("ko_kr")

        assertEquals(english.keySet(), korean.keySet())
        assertTrue(english.has("screen.cobblemon_more_battle_content_league_challenge.dev.title"))
        assertTrue(english.has("command.cobblemon_more_battle_content_league_challenge.dev.opened"))
        english.keySet().forEach { key ->
            val englishValue = english[key].asString
            val koreanValue = korean[key].asString
            assertTrue(englishValue.isNotBlank(), "en_us has a blank value for $key")
            assertTrue(koreanValue.isNotBlank(), "ko_kr has a blank value for $key")
            assertFalse(Regex("[가-힣]").containsMatchIn(englishValue), "en_us contains Korean text for $key")
            assertEquals(
                placeholders(englishValue),
                placeholders(koreanValue),
                "Translation placeholders differ for $key"
            )
        }
    }

    private fun entrypoint(metadata: JsonObject, type: String): String =
        metadata.getAsJsonObject("entrypoints")
            .getAsJsonArray(type)[0]
            .asJsonObject["value"]
            .asString

    private fun language(code: String): JsonObject = json(
        resources.resolve("assets/cobblemon_more_battle_content_league_challenge/lang/$code.json")
    )

    private fun placeholders(value: String): List<String> = Regex("%(?:\\d+\\$)?[a-zA-Z]")
        .findAll(value)
        .map { match -> match.value.last().lowercase() }
        .toList()

    private fun json(path: Path): JsonObject = JsonParser.parseString(Files.readString(path)).asJsonObject
}
