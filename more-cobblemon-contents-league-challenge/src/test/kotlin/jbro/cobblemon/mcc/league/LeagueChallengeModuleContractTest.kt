package jbro.cobblemon.mcc.league

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

        assertEquals("more_cobblemon_contents_league_challenge", metadata["id"].asString)
        assertEquals("More Cobblemon Contents: League Challenge", metadata["name"].asString)
        assertEquals("*", metadata["environment"].asString)
        assertEquals(
            "jbro.cobblemon.mcc.league.MoreCobblemonContentsLeagueChallenge",
            entrypoint(metadata, "main")
        )
        assertEquals(
            "jbro.cobblemon.mcc.league.client.MoreCobblemonContentsLeagueChallengeClient",
            entrypoint(metadata, "client")
        )
    }

    @Test
    fun `system declares approved badge and level cap providers and required MCC API version`() {
        val dependencies = json(resources.resolve("fabric.mod.json")).getAsJsonObject("depends")

        assertEquals(">=0.1.0 <1.0.0", dependencies["more_cobblemon_contents"].asString)
        assertEquals(">=1.8.1 <1.9.0", dependencies["cobblemon"].asString)
        assertEquals(">=1.6.1 <1.7.0", dependencies["pokebadges"].asString)
        assertEquals(">=1.2.0 <1.3.0", dependencies["cobbled_level_control"].asString)
    }

    @Test
    fun `client toolkit comes from the core without making dedicated servers depend on a client mod`() {
        val dependencies = json(resources.resolve("fabric.mod.json")).getAsJsonObject("depends")
        assertFalse(dependencies.has("cobblemon_ui"))
        val bundle = "include(project(\":cobblemon-ui\"))"
        assertFalse(Files.readString(Path.of("build.gradle.kts")).contains(bundle))
        assertTrue(Files.readString(Path.of("../more-cobblemon-contents/build.gradle.kts")).contains(bundle))
        val client = Files.readString(Path.of("src/main/kotlin/jbro/cobblemon/mcc/league/client/MoreCobblemonContentsLeagueChallengeClient.kt"))
        assertTrue(client.indexOf("LeagueHomeController.register()") < client.indexOf("DevelopmentEnvironmentGate.shouldRegister"))
    }

    @Test
    fun `english and korean translations expose the same bootstrap keys`() {
        val english = language("en_us")
        val korean = language("ko_kr")

        assertEquals(english.keySet(), korean.keySet())
        assertTrue(english.has("block.more_cobblemon_contents_league_challenge.league_terminal"))
        assertTrue(english.has("screen.more_cobblemon_contents_league_challenge.live.header_rank"))
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
        resources.resolve("assets/more_cobblemon_contents_league_challenge/lang/$code.json")
    )

    private fun placeholders(value: String): List<String> = Regex("%(?:\\d+\\$)?[a-zA-Z]")
        .findAll(value)
        .map { match -> match.value.last().lowercase() }
        .toList()

    private fun json(path: Path): JsonObject = JsonParser.parseString(Files.readString(path)).asJsonObject

    @Test
    fun `the League terminal cannot be crafted`() {
        // Terminals are placed by operators; a crafting recipe would let anyone set one up.
        org.junit.jupiter.api.Assertions.assertFalse(java.nio.file.Files.exists(java.nio.file.Path.of("src/main/resources/data/more_cobblemon_contents_league_challenge/recipe/league_terminal.json")))
    }
}
