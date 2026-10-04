package jbro.cobblemon.mcc

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URL

/**
 * MCC ships `assets/cobblemon/lang` with the battle lines Cobblemon 1.8.1 is missing, such as Orichalcum Pulse's
 * `-activate`, which otherwise shows as a raw key. Those files only fill gaps: English never redefines a line that
 * Cobblemon or Mega Showdown (which MCC requires) already writes, and a Korean line either matches an English one
 * of ours or translates a dependency's English line whose Korean is missing.
 */
class CobblemonBattleLanguageGapTest {
    private val english = ours("en_us")
    private val korean = ours("ko_kr")
    private val dependencyEnglish = dependencies("en_us")

    @Test
    fun `every english line has a korean line with the same arguments`() {
        assertTrue(english.has("cobblemon.battle.activate.orichalcumpulse"))
        english.keySet().forEach { key ->
            assertTrue(key.startsWith("cobblemon.battle."), "Only battle lines belong here: $key")
            assertTrue(korean.has(key), "ko_kr lacks $key")
            assertFalse(HANGUL.containsMatchIn(english[key].asString), "en_us contains Korean text for $key")
            assertEquals(placeholders(english[key].asString), placeholders(korean[key].asString), "Arguments differ for $key")
        }
    }

    @Test
    fun `korean-only lines translate a dependency's english line`() {
        korean.keySet().filterNot(english::has).forEach { key ->
            val source = dependencyEnglish.firstOrNull { it.has(key) }
            assertTrue(source != null, "ko_kr has $key, which no English file defines")
            assertEquals(placeholders(source!![key].asString), placeholders(korean[key].asString), "Arguments differ for $key")
        }
        korean.keySet().forEach { key -> assertTrue(HANGUL.containsMatchIn(korean[key].asString), "ko_kr is untranslated for $key") }
    }

    @Test
    fun `english never overrides a line a dependency already has`() {
        assertTrue(dependencyEnglish.isNotEmpty(), "Cobblemon's language files are not on the test classpath")
        dependencyEnglish.forEach { dependency ->
            val overridden = english.keySet().filter(dependency::has)
            assertTrue(overridden.isEmpty(), "en_us redefines a dependency's lines: $overridden")
        }
    }

    private fun ours(code: String): JsonObject = parse(resources(code).single { it.isOurs() })

    /** Every other mod's file for the namespace; one that does not parse (Mega Showdown's Korean) is skipped, as the game does. */
    private fun dependencies(code: String): List<JsonObject> =
        resources(code).filterNot { it.isOurs() }.mapNotNull { runCatching { parse(it) }.getOrNull() }

    private fun resources(code: String): List<URL> =
        javaClass.classLoader.getResources("assets/cobblemon/lang/$code.json").toList()

    private fun URL.isOurs(): Boolean = protocol != "jar" || path.contains("more-cobblemon-contents")

    private fun parse(url: URL): JsonObject = url.openStream().reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }

    /** The arguments a line uses; Korean word order may use one more or fewer times than English. */
    private fun placeholders(value: String): Set<String> = PLACEHOLDER.findAll(value).map { it.value }.toSet()

    companion object {
        private val HANGUL = Regex("[가-힣]")
        private val PLACEHOLDER = Regex("%(?:\\d+\\$)?[a-zA-Z]")
    }
}
