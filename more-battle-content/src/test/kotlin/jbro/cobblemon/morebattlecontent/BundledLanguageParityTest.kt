package jbro.cobblemon.morebattlecontent

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BundledLanguageParityTest {
    @Test
    fun `english and korean bundles expose the same nonblank keys and placeholders`() {
        val english = language("en_us")
        val korean = language("ko_kr")

        assertEquals(english.keySet(), korean.keySet())
        english.keySet().forEach { key ->
            val englishValue = english[key].asString
            val koreanValue = korean[key].asString
            assertTrue(englishValue.isNotBlank(), "en_us has a blank value for $key")
            assertTrue(koreanValue.isNotBlank(), "ko_kr has a blank value for $key")
            assertFalse(HANGUL.containsMatchIn(englishValue), "en_us contains Korean text for $key")
            assertEquals(
                placeholders(englishValue),
                placeholders(koreanValue),
                "Translation placeholders differ for $key"
            )
        }
    }

    private fun language(code: String): JsonObject = requireNotNull(
        javaClass.getResourceAsStream("/assets/cobblemon_more_battle_content/lang/$code.json")
    ) { "Missing bundled language: $code" }.reader().use { JsonParser.parseReader(it).asJsonObject }

    private fun placeholders(value: String): List<String> = PLACEHOLDER.findAll(value).map { match ->
        match.value.last().lowercase()
    }.toList()

    companion object {
        private val HANGUL = Regex("[가-힣]")
        private val PLACEHOLDER = Regex("%(?:\\d+\\$)?[a-zA-Z]")
    }
}
