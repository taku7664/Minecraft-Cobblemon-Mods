package jbro.cobblemon.morebattlecontent.betterai

import java.io.StringReader
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.betterai.state.LocalOpponentBuildUsage
import jbro.cobblemon.morebattlecontent.betterai.state.LocalOpponentBuildUsageTable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalOpponentBuildUsageTest {
    @Test
    fun `parses complete public build marginals and explicit no-item usage`() {
        val table = LocalOpponentBuildUsageTable.parse(StringReader(SNAPSHOT))

        val usage = requireNotNull(table.forPokemon("cobblemon:flutter_mane", "normal"))
        assertEquals(1.0, usage.abilityRates.getValue("protosynthesis"), 1e-12)
        assertEquals(0.75, usage.itemRates.getValue("boosterenergy"), 1e-12)
        assertEquals(0.25, usage.noItemRate, 1e-12)
        assertEquals(0.6, usage.spreads.first().rate, 1e-12)
        assertEquals(0.0, usage.unresolvedSpreadRate, 1e-12)
        assertEquals("timid", usage.spreads.first().natureId)
        assertEquals(
            mapOf("hp" to 4, "atk" to 0, "def" to 0, "spa" to 252, "spd" to 0, "spe" to 252),
            usage.spreads.first().evs,
        )
        assertEquals(0.7, usage.teraTypeRates.getValue("fairy"), 1e-12)
        assertEquals("gen9bssregj", table.source?.format)
        assertEquals(1, table.speciesCount)
    }

    @Test
    fun `falls back from a public form key without inventing a missing species`() {
        val table = LocalOpponentBuildUsageTable.parse(StringReader(SNAPSHOT))

        assertEquals(1.0, requireNotNull(
            table.forPokemon("flutter-mane", "mega")?.abilityRates?.get("protosynthesis"),
        ), 1e-12)
        assertNull(table.forPokemon("missingno", null))
    }

    @Test
    fun `rejects malformed spreads and non-normalized marginals`() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalOpponentBuildUsageTable.parse(StringReader(SNAPSHOT.replace("Timid:4/0/0/252/0/252", "Timid:bad")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalOpponentBuildUsageTable.parse(StringReader(SNAPSHOT.replace("\"Booster Energy\": 0.75", "\"Booster Energy\": 0.5")))
        }
    }

    @Test
    fun `loads distinct committed singles and doubles build snapshots`() {
        val singles = LocalOpponentBuildUsage.forFormat(BattleFormat.SINGLE)
        val doubles = LocalOpponentBuildUsage.forFormat(BattleFormat.DOUBLE)

        assertTrue(singles.loaded)
        assertTrue(doubles.loaded)
        assertEquals("gen9bssregj", singles.source?.format)
        assertEquals("gen9vgc2025regj", doubles.source?.format)
        assertTrue(singles.speciesCount >= 400)
        assertTrue(doubles.speciesCount >= 400)
        assertEquals(1.0, requireNotNull(
            singles.forPokemon("flutter-mane", null)?.abilityRates?.get("protosynthesis"),
        ), 1e-12)
        assertEquals(1.0, requireNotNull(
            doubles.forPokemon("flutter-mane", null)?.abilityRates?.get("protosynthesis"),
        ), 1e-12)
        assertTrue(
            singles.forPokemon("flutter-mane", null)?.itemRates?.get("boosterenergy") !=
                doubles.forPokemon("flutter-mane", null)?.itemRates?.get("boosterenergy"),
        )
    }

    private companion object {
        val SNAPSHOT = """
            {
              "schemaVersion": 1,
              "source": {
                "provider": "Smogon Pokemon Showdown usage stats",
                "format": "gen9bssregj",
                "month": "2025-12",
                "cutoff": 1500,
                "battleCount": 26139,
                "url": "https://example.invalid/source.json",
                "rawSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
              },
              "species": {
                "flutter-mane": {
                  "abilities": {"Protosynthesis": 1.0},
                  "items": {"Booster Energy": 0.75, "Nothing": 0.25},
                  "spreads": {
                    "Timid:4/0/0/252/0/252": 0.6,
                    "Modest:4/0/0/252/0/252": 0.4
                  },
                  "unresolvedSpreadRate": 0.0,
                  "teraTypes": {"Fairy": 0.7, "Water": 0.3}
                }
              }
            }
        """.trimIndent()
    }
}
