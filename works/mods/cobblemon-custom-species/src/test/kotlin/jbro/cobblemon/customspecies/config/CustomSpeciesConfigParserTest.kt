package jbro.cobblemon.customspecies.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CustomSpeciesConfigParserTest {
    private val parser = CustomSpeciesConfigParser()

    @Test
    fun `parses every supported override operation from the single config file`() {
        val config = parser.parse(
            """
            {
              "schema": 1,
              "overrides": [{
                "species": "cobblemon:charizard",
                "form": "base",
                "base_stats": {"attack": 100, "special_attack": 120},
                "abilities": {"add": ["h:toughclaws"], "remove": ["h:solarpower"]},
                "moves": {
                  "add": ["tm:scaleshot", "72:blastburn"],
                  "remove": ["tm:toxic"],
                  "remove_moves": ["growl"]
                }
              }]
            }
            """.trimIndent()
        )

        val override = config.overrides.single()
        assertEquals("cobblemon:charizard", override.species)
        assertEquals(FormSelector.Base, override.form)
        assertEquals(100, override.baseStats[StatKey.ATTACK])
        assertEquals(listOf("tm:scaleshot", "72:blastburn"), override.moves.add)
        assertEquals(listOf("growl"), override.moves.removeMoves)
    }

    @Test
    fun `rejects an unknown field instead of silently ignoring a typo`() {
        assertThrows(ConfigValidationException::class.java) {
            parser.parse("""{"schema":1,"overrides":[],"override":[]}""")
        }
    }

    @Test
    fun `rejects duplicate species and form targets because one file has no priority order`() {
        assertThrows(ConfigValidationException::class.java) {
            parser.parse(
                """
                {"schema":1,"overrides":[
                  {"species":"cobblemon:rotom","form":"wash","moves":{"add":["tm:surf"]}},
                  {"species":"cobblemon:rotom","form":"wash","base_stats":{"speed":100}}
                ]}
                """.trimIndent()
            )
        }
    }

    @Test
    fun `rejects replace mixed with incremental ability operations`() {
        assertThrows(ConfigValidationException::class.java) {
            parser.parse(
                """{"schema":1,"overrides":[{"species":"cobblemon:eevee","form":"*","abilities":{"replace":["r:runaway"],"add":["h:anticipation"]}}]}"""
            )
        }
    }
}
