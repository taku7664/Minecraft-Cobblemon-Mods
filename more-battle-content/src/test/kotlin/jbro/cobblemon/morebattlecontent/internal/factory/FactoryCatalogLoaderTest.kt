package jbro.cobblemon.morebattlecontent.internal.factory

import com.google.gson.JsonParser
import java.io.StringReader
import jbro.cobblemon.morebattlecontent.api.ai.BattleTeamRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FactoryCatalogLoaderTest {
    @Test
    fun `separated trainer and rental resources assemble one factory snapshot`() {
        val root = JsonParser.parseString(validJson()).asJsonObject
        val trainers = """{"schema_version":1,"trainers":${root.getAsJsonArray("trainers")}}"""
        val rentalSets = """{"schema_version":4,"rental_sets":${root.getAsJsonArray("sets")}}"""

        val loaded = FactoryCatalogLoader.loadSeparated(
            trainerFragments = listOf("example:mbc-battle-factory/trainers/core.json" to StringReader(trainers)),
            rentalSetFragments = listOf("example:mbc-battle-factory/rental-sets/core.json" to StringReader(rentalSets)),
        ) as FactoryCatalogLoadResult.Loaded

        assertEquals(1, loaded.catalog.trainersFor(FactoryBattleFormat.SINGLE).size)
        assertEquals(6, loaded.catalog.rentalPool(FactoryPoolWindow(FactoryPoolGroup.ADVANCED, setOf(1))).size)
    }

    @Test
    fun `schema four merges independent trainer and rental set files`() {
        val root = JsonParser.parseString(validJson()).asJsonObject
        val trainers = root.deepCopy().apply {
            addProperty("catalog_id", "custom_trainers")
            remove("sets")
        }
        val sets = root.deepCopy().apply {
            addProperty("catalog_id", "custom_sets")
            remove("trainers")
        }

        val loaded = FactoryCatalogLoader.loadFragments(
            listOf(
                "example:mbc-battle-factory/legacy/trainers.json" to StringReader(trainers.toString()),
                "example:mbc-battle-factory/legacy/rentals.json" to StringReader(sets.toString()),
            ),
        ) as FactoryCatalogLoadResult.Loaded

        assertEquals("merged_factory_catalog", loaded.catalog.catalogId)
        assertEquals(1, loaded.catalog.trainersFor(FactoryBattleFormat.SINGLE).size)
        assertEquals(6, loaded.catalog.rentalPool(FactoryPoolWindow(FactoryPoolGroup.ADVANCED, setOf(1))).size)
    }

    @Test
    fun `fragment merge rejects duplicate ids instead of depending on file order`() {
        val root = JsonParser.parseString(validJson()).asJsonObject
        val trainers = root.deepCopy().apply { remove("sets") }
        val sets = root.deepCopy().apply { remove("trainers") }

        val rejected = FactoryCatalogLoader.loadFragments(
            listOf(
                "example:mbc-battle-factory/legacy/trainers.json" to StringReader(trainers.toString()),
                "example:mbc-battle-factory/legacy/rentals-a.json" to StringReader(sets.toString()),
                "example:mbc-battle-factory/legacy/rentals-b.json" to StringReader(sets.toString()),
            ),
        ) as FactoryCatalogLoadResult.Rejected

        assertEquals(FactoryCatalogIssueCode.DUPLICATE_ID, rejected.issues.single().code)
    }

    @Test
    fun `schema four loads complete fixed rental sets and independent trainers`() {
        val loaded = FactoryCatalogLoader.load(StringReader(validJson())) as FactoryCatalogLoadResult.Loaded
        val template = loaded.catalog
            .rentalPool(FactoryPoolWindow(FactoryPoolGroup.ADVANCED, setOf(1)))
            .single { it.setId == "garchomp_1" }
        val trainer = loaded.catalog.trainersFor(FactoryBattleFormat.SINGLE).single()

        assertEquals(listOf("cobblemon:swordsdance", "cobblemon:earthquake", "cobblemon:dragonclaw", "cobblemon:rockslide"), template.moveIds)
        assertEquals("cobblemon:lum_berry", template.heldItemId)
        assertEquals("cobblemon:jolly", template.natureId)
        assertEquals(setOf(BattleTeamRole.ACE, BattleTeamRole.WALLBREAKER), template.roles)
        assertEquals("cynthia", trainer.trainerId)
        assertEquals("factory.trainer.cynthia.name", trainer.displayNameKey)
    }

    @Test
    fun `catalog accepts a pool whose complete presets repeat held items`() {
        val root = JsonParser.parseString(validJson()).asJsonObject
        root.getAsJsonArray("sets").forEach { element ->
            element.asJsonObject.addProperty("held_item_id", "cobblemon:leftovers")
        }

        val loaded = FactoryCatalogLoader.load(StringReader(root.toString()))

        assertTrue(loaded is FactoryCatalogLoadResult.Loaded)
    }

    @Test
    fun `schema four rejects every randomized candidate field and incomplete move sets`() {
        assertRejected(validJson().replace("\"moves\":", "\"move_slots\":"), FactoryCatalogIssueCode.UNKNOWN_FIELD)
        assertRejected(validJson().replace("\"held_item_id\":", "\"held_items\":"), FactoryCatalogIssueCode.UNKNOWN_FIELD)
        assertRejected(validJson().replace("\"nature_id\":", "\"nature_pool\":"), FactoryCatalogIssueCode.UNKNOWN_FIELD)
        assertRejected(
            validJson().replace(
                ", \"cobblemon:rockslide\"]",
                "]",
            ),
            FactoryCatalogIssueCode.INVALID_VALUE,
        )
    }

    @Test
    fun `legacy randomized schemas are retired instead of silently reinterpreted`() {
        listOf(1, 2, 3).forEach { schema ->
            assertRejected(validJson().replace("\"schema_version\": 4", "\"schema_version\": $schema"), FactoryCatalogIssueCode.UNSUPPORTED_SCHEMA)
        }
    }

    @Test
    fun `fixed iv overrides remain optional because round iv is a separate original rule`() {
        val withoutOverride = FactoryCatalogLoader.load(StringReader(validJson())) as FactoryCatalogLoadResult.Loaded
        val ordinary = withoutOverride.catalog.rentalPool(FactoryPoolWindow(FactoryPoolGroup.ADVANCED, setOf(1))).first()
        assertEquals(FactoryStatSpread(21, 21, 21, 21, 21, 21), ordinary.materialize(21).ivs)

        val withOverrideJson = validJson().replace(
            "\"evs\":",
            "\"ivs\": {\"hp\": 31, \"attack\": 31, \"defense\": 31, \"special_attack\": 0, \"special_defense\": 31, \"speed\": 31}, \"evs\":",
        )
        val withOverride = FactoryCatalogLoader.load(StringReader(withOverrideJson)) as FactoryCatalogLoadResult.Loaded
        val overridden = withOverride.catalog.rentalPool(FactoryPoolWindow(FactoryPoolGroup.ADVANCED, setOf(1))).first()
        assertEquals(FactoryStatSpread(31, 31, 31, 0, 31, 31), overridden.materialize(0).ivs)

        assertRejected(
            validJson().replaceFirst("\"evs\":", "\"ivs\": true, \"evs\":"),
            FactoryCatalogIssueCode.INVALID_VALUE,
        )
    }

    @Test
    fun `rejects duplicate ids invalid role metadata and unknown fields`() {
        assertRejected(validJson().replace("\"cynthia\"", "\"cynthia\", \"typo\": true"), FactoryCatalogIssueCode.UNKNOWN_FIELD)
        assertRejected(validJson().replace("\"wallbreaker\"", "\"not_a_role\""), FactoryCatalogIssueCode.INVALID_VALUE)
        assertRejected(validJson().replace("\"garchomp_2\"", "\"garchomp_1\""), FactoryCatalogIssueCode.DUPLICATE_ID)
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
          "schema_version": 4,
          "catalog_id": "mbc_factory",
          "trainers": [
            {
              "trainer_id": "cynthia",
              "display_name_key": "factory.trainer.cynthia.name",
              "description_key": "factory.trainer.shared.description",
              "formats": ["single", "double"],
              "weight": 10,
              "ai_skill": 3,
              "ai_summary": "Choose a legal team from the current Factory rental pool.",
              "objectives": ["preserve_core"]
            }
          ],
          "sets": [
            {
              "set_id": "garchomp_1", "pool_group": "advanced", "variant": 1,
              "species_id": "cobblemon:garchomp", "ability_id": "cobblemon:rough_skin",
              "held_item_id": "cobblemon:lum_berry", "nature_id": "cobblemon:jolly",
              "moves": ["cobblemon:swordsdance", "cobblemon:earthquake", "cobblemon:dragonclaw", "cobblemon:rockslide"],
              "roles": ["ace", "wallbreaker"], "preferred_move_ids": ["cobblemon:swordsdance"],
              "lead_priority": 30, "preservation_priority": 100,
              "evs": {"hp": 0, "attack": 252, "defense": 0, "special_attack": 0, "special_defense": 4, "speed": 252}
            },
            {
              "set_id": "garchomp_2", "pool_group": "advanced", "variant": 1,
              "species_id": "cobblemon:scizor", "ability_id": "cobblemon:technician",
              "held_item_id": "cobblemon:metal_coat", "nature_id": "cobblemon:adamant",
              "moves": ["cobblemon:bulletpunch", "cobblemon:uturn", "cobblemon:closecombat", "cobblemon:swordsdance"],
              "roles": ["cleaner"], "preferred_move_ids": ["cobblemon:bulletpunch"],
              "lead_priority": 20, "preservation_priority": 70,
              "evs": {"hp": 4, "attack": 252, "defense": 0, "special_attack": 0, "special_defense": 0, "speed": 252}
            },
            {
              "set_id": "klefki_1", "pool_group": "advanced", "variant": 1,
              "species_id": "cobblemon:klefki", "ability_id": "cobblemon:prankster",
              "held_item_id": "cobblemon:light_clay", "nature_id": "cobblemon:careful",
              "moves": ["cobblemon:reflect", "cobblemon:lightscreen", "cobblemon:thunderwave", "cobblemon:foulplay"],
              "roles": ["setup_enabler"], "preferred_move_ids": ["cobblemon:reflect"],
              "lead_priority": 100, "preservation_priority": 30,
              "evs": {"hp": 252, "attack": 0, "defense": 4, "special_attack": 0, "special_defense": 252, "speed": 0}
            },
            {
              "set_id": "rotom_1", "pool_group": "advanced", "variant": 1,
              "species_id": "cobblemon:rotom", "form_id": "wash", "ability_id": "cobblemon:levitate",
              "held_item_id": "cobblemon:leftovers", "nature_id": "cobblemon:bold",
              "moves": ["cobblemon:hydropump", "cobblemon:voltswitch", "cobblemon:willowisp", "cobblemon:protect"],
              "roles": ["weakness_cover"], "preferred_move_ids": ["cobblemon:voltswitch"],
              "lead_priority": 50, "preservation_priority": 60,
              "evs": {"hp": 252, "attack": 0, "defense": 252, "special_attack": 4, "special_defense": 0, "speed": 0}
            },
            {
              "set_id": "arcanine_1", "pool_group": "advanced", "variant": 1,
              "species_id": "cobblemon:arcanine", "ability_id": "cobblemon:intimidate",
              "held_item_id": "cobblemon:sitrus_berry", "nature_id": "cobblemon:jolly",
              "moves": ["cobblemon:flareblitz", "cobblemon:extremespeed", "cobblemon:closecombat", "cobblemon:wildcharge"],
              "roles": ["cleaner"], "preferred_move_ids": ["cobblemon:extremespeed"],
              "lead_priority": 60, "preservation_priority": 70,
              "evs": {"hp": 4, "attack": 252, "defense": 0, "special_attack": 0, "special_defense": 0, "speed": 252}
            },
            {
              "set_id": "milotic_1", "pool_group": "advanced", "variant": 1,
              "species_id": "cobblemon:milotic", "ability_id": "cobblemon:competitive",
              "held_item_id": "cobblemon:rocky_helmet", "nature_id": "cobblemon:bold",
              "moves": ["cobblemon:scald", "cobblemon:icebeam", "cobblemon:recover", "cobblemon:protect"],
              "roles": ["weakness_cover"], "preferred_move_ids": ["cobblemon:recover"],
              "lead_priority": 40, "preservation_priority": 80,
              "evs": {"hp": 252, "attack": 0, "defense": 252, "special_attack": 0, "special_defense": 4, "speed": 0}
            }
          ]
        }
        """.trimIndent()
}
