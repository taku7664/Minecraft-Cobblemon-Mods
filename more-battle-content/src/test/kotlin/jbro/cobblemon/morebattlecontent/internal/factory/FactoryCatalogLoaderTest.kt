package jbro.cobblemon.morebattlecontent.internal.factory

import com.google.gson.JsonParser
import java.io.StringReader
import jbro.cobblemon.morebattlecontent.api.ai.BattleTeamRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FactoryCatalogLoaderTest {
    private val firstChoiceRandom = object : FactoryCatalogRandom {
        override fun nextLong(bound: Long): Long = 0
        override fun nextInt(bound: Int): Int = 0
    }

    @Test
    fun `schema three loads move slots item candidates and all standard natures`() {
        val loaded = FactoryCatalogLoader.load(StringReader(validSchemaThreeJson())) as FactoryCatalogLoadResult.Loaded
        val template = loaded.catalog
            .rentalPool(FactoryPoolWindow(FactoryPoolGroup.ADVANCED, setOf(1)))
            .single { it.setId == "garchomp_1" }

        assertEquals(
            listOf(
                listOf("cobblemon:swords_dance", "cobblemon:protect"),
                listOf("cobblemon:earthquake", "cobblemon:stomping_tantrum"),
                listOf("cobblemon:dragon_claw", "cobblemon:outrage"),
                listOf("cobblemon:rock_slide", "cobblemon:poison_jab"),
            ),
            template.moveSlots,
        )
        assertEquals(listOf("cobblemon:lum_berry", "cobblemon:yache_berry"), template.heldItemIds)
        assertEquals(25, template.natureIds.size)
        assertEquals(25, template.natureIds.distinct().size)
    }

    @Test
    fun `schema three rejects malformed candidate pools and legacy fixed fields`() {
        assertRejected(
            validSchemaThreeJson().replace(
                "[\"cobblemon:swords_dance\",\"cobblemon:protect\"]",
                "[]",
            ),
            FactoryCatalogIssueCode.INVALID_VALUE,
        )
        assertRejected(
            validSchemaThreeJson().replace(
                "[\"cobblemon:earthquake\",\"cobblemon:stomping_tantrum\"]",
                "[\"cobblemon:earthquake\",\"cobblemon:swords_dance\"]",
            ),
            FactoryCatalogIssueCode.DUPLICATE_ID,
        )
        assertRejected(
            validSchemaThreeJson().replace("\"nature_pool\":\"all\"", "\"nature_pool\":\"competitive\""),
            FactoryCatalogIssueCode.INVALID_VALUE,
        )
        assertRejected(
            validSchemaThreeJson().replace(
                "\"nature_pool\":\"all\"",
                "\"nature_pool\":\"all\",\"nature_id\":\"cobblemon:jolly\"",
            ),
            FactoryCatalogIssueCode.UNKNOWN_FIELD,
        )
    }

    @Test
    fun `schema two requires and preserves exact per stat ivs`() {
        val loaded = FactoryCatalogLoader.load(StringReader(validSchemaTwoJson())) as FactoryCatalogLoadResult.Loaded

        val garchomp = loaded.catalog
            .rentalPool(FactoryPoolWindow(FactoryPoolGroup.ADVANCED, setOf(1)))
            .single { it.setId == "garchomp_1" }
            .let { it.materialize(0, it.heldItemIds.single(), firstChoiceRandom) }

        assertEquals(FactoryStatSpread(31, 31, 31, 0, 31, 31), garchomp.ivs)
        assertRejected(
            validJson().replace("\"schema_version\": 1", "\"schema_version\": 2"),
            FactoryCatalogIssueCode.MISSING_FIELD,
        )
    }

    @Test
    fun `loads an ace centered concept with explicit enabler and weakness coverage roles`() {
        val loaded = FactoryCatalogLoader.load(StringReader(validJson())) as FactoryCatalogLoadResult.Loaded
        val concept = loaded.catalog.conceptsFor(FactoryBattleFormat.SINGLE).single()

        assertEquals("garchomp_breakthrough", concept.conceptId)
        assertEquals("factory.concept.garchomp_breakthrough.description", concept.descriptionKey)
        assertEquals(1, concept.members.count { BattleTeamRole.ACE in it.roles })
        assertTrue(concept.members.any { BattleTeamRole.SETUP_ENABLER in it.roles })
        assertTrue(concept.members.any { BattleTeamRole.WEAKNESS_COVER in it.roles })
        assertNotNull(FactoryConceptTeamSearch.select(loaded.catalog, concept, FactoryBattleFormat.SINGLE))
        assertEquals(
            "standard",
            loaded.catalog.rentalPool(FactoryPoolWindow(FactoryPoolGroup.ADVANCED, setOf(1)))
                .single { it.setId == "garchomp_1" }
                .let { it.materialize(31, it.heldItemIds.single(), firstChoiceRandom) }
                .formId,
        )
    }

    @Test
    fun `rejects missing ace stall-like unknown roles and unknown fields`() {
        assertRejected(
            validJson().replace("\"ace\"", "\"generalist\""),
            FactoryCatalogIssueCode.INVALID_CONCEPT,
        )
        assertRejected(
            validJson().replace("\"setup_enabler\"", "\"stall\""),
            FactoryCatalogIssueCode.INVALID_VALUE,
        )
        assertRejected(
            validJson().replace("\"catalog_id\": \"mbc_factory\"", "\"catalog_id\": \"mbc_factory\", \"typo\": true"),
            FactoryCatalogIssueCode.UNKNOWN_FIELD,
        )
    }

    @Test
    fun `rejects unknown references duplicate role use and concepts without a legal team`() {
        assertRejected(
            validJson().replace("\"garchomp_1\"]", "\"missing\"]"),
            FactoryCatalogIssueCode.UNKNOWN_REFERENCE,
        )
        assertRejected(
            validJson().replace("\"klefki_1\"]", "\"garchomp_1\"]"),
            FactoryCatalogIssueCode.INVALID_CONCEPT,
        )
        assertRejected(
            validJson().replace("\"scizor_1\"]", "\"garchomp_1\"]"),
            FactoryCatalogIssueCode.INVALID_CONCEPT,
        )
    }

    @Test
    fun `store retains the last valid snapshot after a rejected reload`() {
        val store = FactoryCatalogStore()
        val accepted = store.reload(StringReader(validJson())) as FactoryCatalogLoadResult.Loaded

        assertTrue(store.reload(StringReader("{}")) is FactoryCatalogLoadResult.Rejected)
        assertSame(accepted.catalog, store.snapshot())
    }

    private fun assertRejected(json: String, code: FactoryCatalogIssueCode) {
        val rejected = FactoryCatalogLoader.load(StringReader(json)) as FactoryCatalogLoadResult.Rejected
        assertEquals(code, rejected.issues.single().code)
    }

    private fun validJson() =
        """
        {
          "schema_version": 1,
          "catalog_id": "mbc_factory",
          "concepts": [
            {
              "concept_id": "garchomp_breakthrough",
              "display_name_key": "factory.concept.garchomp_breakthrough.name",
              "description_key": "factory.concept.garchomp_breakthrough.description",
              "formats": ["single"],
              "weight": 10,
              "ai_skill": 3,
              "ai_summary": "Create one safe turn for Garchomp, then preserve its checks and finish with priority.",
              "objectives": ["setup_sweep", "preserve_core"],
              "members": [
                {
                  "plan_id": "ace",
                  "required": true,
                  "roles": ["ace"],
                  "tactical_summary": "Garchomp is the ace and uses Swords Dance only when the enabler created room.",
                  "preferred_move_ids": ["cobblemon:swords_dance"],
                  "lead_priority": 20,
                  "preservation_priority": 100,
                  "set_pool": ["garchomp_1"]
                },
                {
                  "plan_id": "enabler",
                  "required": true,
                  "roles": ["setup_enabler", "disruptor"],
                  "tactical_summary": "Klefki creates the setup turn with screens and paralysis.",
                  "preferred_move_ids": ["cobblemon:reflect"],
                  "lead_priority": 100,
                  "preservation_priority": 30,
                  "set_pool": ["klefki_1"]
                },
                {
                  "plan_id": "ice_cover",
                  "required": false,
                  "roles": ["weakness_cover", "cleaner"],
                  "tactical_summary": "Scizor checks Ice and Fairy pressure and cleans weakened targets with priority.",
                  "preferred_move_ids": ["cobblemon:bullet_punch"],
                  "lead_priority": 10,
                  "preservation_priority": 60,
                  "set_pool": ["scizor_1"]
                }
              ]
            }
          ],
          "sets": [
            {
              "set_id": "garchomp_1", "pool_group": "advanced", "variant": 1,
              "species_id": "cobblemon:garchomp", "form_id": "standard", "ability_id": "cobblemon:rough_skin",
              "held_item_id": "cobblemon:lum_berry", "nature_id": "cobblemon:jolly",
              "moves": ["cobblemon:swords_dance", "cobblemon:earthquake", "cobblemon:dragon_claw"],
              "evs": {"hp": 0, "attack": 252, "defense": 0, "special_attack": 0, "special_defense": 4, "speed": 252}
            },
            {
              "set_id": "klefki_1", "pool_group": "advanced", "variant": 1,
              "species_id": "cobblemon:klefki", "ability_id": "cobblemon:prankster",
              "held_item_id": "cobblemon:light_clay", "nature_id": "cobblemon:careful",
              "moves": ["cobblemon:reflect", "cobblemon:light_screen", "cobblemon:thunder_wave"],
              "evs": {"hp": 252, "attack": 0, "defense": 4, "special_attack": 0, "special_defense": 252, "speed": 0}
            },
            {
              "set_id": "scizor_1", "pool_group": "advanced", "variant": 1,
              "species_id": "cobblemon:scizor", "ability_id": "cobblemon:technician",
              "held_item_id": "cobblemon:metal_coat", "nature_id": "cobblemon:adamant",
              "moves": ["cobblemon:bullet_punch", "cobblemon:u_turn", "cobblemon:close_combat"],
              "evs": {"hp": 248, "attack": 252, "defense": 0, "special_attack": 0, "special_defense": 8, "speed": 0}
            }
          ]
        }
        """.trimIndent()

    private fun validSchemaTwoJson() = validJson()
        .replace("\"schema_version\": 1", "\"schema_version\": 2")
        .replace(
            "\"evs\":",
            "\"ivs\": {\"hp\": 31, \"attack\": 31, \"defense\": 31, \"special_attack\": 0, \"special_defense\": 31, \"speed\": 31}, \"evs\":",
        )

    private fun validSchemaThreeJson(): String {
        val root = JsonParser.parseString(validSchemaTwoJson()).asJsonObject
        root.addProperty("schema_version", 3)
        val candidates = mapOf(
            "garchomp_1" to Pair(
                "[\"cobblemon:lum_berry\",\"cobblemon:yache_berry\"]",
                "[[\"cobblemon:swords_dance\",\"cobblemon:protect\"],[\"cobblemon:earthquake\",\"cobblemon:stomping_tantrum\"],[\"cobblemon:dragon_claw\",\"cobblemon:outrage\"],[\"cobblemon:rock_slide\",\"cobblemon:poison_jab\"]]",
            ),
            "klefki_1" to Pair(
                "[\"cobblemon:light_clay\",\"cobblemon:leftovers\"]",
                "[[\"cobblemon:reflect\",\"cobblemon:spikes\"],[\"cobblemon:light_screen\",\"cobblemon:safeguard\"],[\"cobblemon:thunder_wave\",\"cobblemon:toxic\"],[\"cobblemon:foul_play\",\"cobblemon:play_rough\"]]",
            ),
            "scizor_1" to Pair(
                "[\"cobblemon:metal_coat\",\"cobblemon:life_orb\"]",
                "[[\"cobblemon:bullet_punch\",\"cobblemon:quick_attack\"],[\"cobblemon:u_turn\",\"cobblemon:bug_bite\"],[\"cobblemon:close_combat\",\"cobblemon:superpower\"],[\"cobblemon:swords_dance\",\"cobblemon:roost\"]]",
            ),
        )
        root.getAsJsonArray("sets").forEach { element ->
            val set = element.asJsonObject
            val (items, slots) = candidates.getValue(set["set_id"].asString)
            set.remove("held_item_id")
            set.remove("nature_id")
            set.remove("moves")
            set.add("held_items", JsonParser.parseString(items))
            set.addProperty("nature_pool", "all")
            set.add("move_slots", JsonParser.parseString(slots))
        }
        return root.toString()
    }
}
