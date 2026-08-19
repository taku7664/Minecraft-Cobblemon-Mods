package jbro.cobblemon.morebattlecontent.internal.tower.opponent

import com.google.gson.JsonParser
import java.io.StringReader
import jbro.cobblemon.morebattlecontent.api.rules.MajorBattleMechanic
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleFormat
import jbro.cobblemon.morebattlecontent.internal.tower.TowerOpponentKind
import jbro.cobblemon.morebattlecontent.internal.tower.TowerRank
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TowerOpponentCatalogLoaderTest {
    @Test
    fun `loads schema two and indexes profiles by mechanic and existing tower contracts`() {
        val result = TowerOpponentCatalogLoader.load(StringReader(validCatalogJson()))

        result as TowerOpponentCatalogLoadResult.Loaded
        val profiles = result.catalog.profilesFor(
            rank = TowerRank.RANK_1,
            format = TowerBattleFormat.SINGLE,
            opponentKind = TowerOpponentKind.REGULAR,
            mechanic = MajorBattleMechanic.MEGA,
        )

        assertEquals("mbc_core", result.catalog.catalogId)
        assertEquals(listOf("r1_single_balanced"), profiles.map(TowerOpponentProfile::profileId))
        assertEquals(MajorBattleMechanic.MEGA, profiles.single().mechanic)
        assertEquals((1..6).map { "set_$it" }, result.catalog.setsFor(profiles.single()).map(TowerPokemonSet::setId))
        assertEquals(15, result.catalog.setsFor(profiles.single()).first().ivs.hp)
    }

    @Test
    fun `rejects malformed json unsupported schemas and unknown fields`() {
        assertIssue(TowerOpponentCatalogIssueCode.MALFORMED_JSON, "{")
        assertIssue(
            TowerOpponentCatalogIssueCode.UNSUPPORTED_SCHEMA,
            validCatalogJson().replace("\"schema_version\": 2", "\"schema_version\": 4"),
        )
        assertIssue(
            TowerOpponentCatalogIssueCode.UNKNOWN_FIELD,
            validCatalogJson().replace("\"catalog_id\": \"mbc_core\"", "\"catalog_id\": \"mbc_core\", \"typo\": true"),
        )
    }

    @Test
    fun `schema three loads tera and dynamax properties without changing schema two`() {
        val tera = TowerOpponentCatalogLoader.load(
            StringReader(schemaThreeCatalogJson("tera", "\"tera_type\": \"fire\",")),
        ) as TowerOpponentCatalogLoadResult.Loaded
        val teraProfile = tera.catalog.profilesFor(
            TowerRank.RANK_1,
            TowerBattleFormat.SINGLE,
            TowerOpponentKind.REGULAR,
            MajorBattleMechanic.TERA,
        ).single()
        val teraSet = tera.catalog.setsFor(teraProfile).first()
        assertEquals("fire", teraSet.teraType)
        assertNull(teraSet.dmaxLevel)
        assertNull(teraSet.gmaxFactor)

        val dynamax = TowerOpponentCatalogLoader.load(
            StringReader(schemaThreeCatalogJson("dynamax", "\"dmax_level\": 10, \"gmax_factor\": true,")),
        ) as TowerOpponentCatalogLoadResult.Loaded
        val dynamaxProfile = dynamax.catalog.profilesFor(
            TowerRank.RANK_1,
            TowerBattleFormat.SINGLE,
            TowerOpponentKind.REGULAR,
            MajorBattleMechanic.DYNAMAX,
        ).single()
        val dynamaxSet = dynamax.catalog.setsFor(dynamaxProfile).first()
        assertNull(dynamaxSet.teraType)
        assertEquals(10, dynamaxSet.dmaxLevel)
        assertEquals(true, dynamaxSet.gmaxFactor)

        val schemaTwo = TowerOpponentCatalogLoader.load(StringReader(validCatalogJson()))
        assertTrue(schemaTwo is TowerOpponentCatalogLoadResult.Loaded)
        assertIssue(
            TowerOpponentCatalogIssueCode.UNKNOWN_FIELD,
            validCatalogJson().replaceFirst("\"moves\":", "\"tera_type\": \"fire\", \"moves\":"),
        )
    }

    @Test
    fun `schema three requires only the selected mechanic properties`() {
        assertIssue(
            TowerOpponentCatalogIssueCode.MISSING_FIELD,
            schemaThreeCatalogJson("tera", "\"tera_type\": \"fire\",")
                .replaceFirst("\"tera_type\": \"fire\",", ""),
        )
        assertIssue(
            TowerOpponentCatalogIssueCode.MISSING_FIELD,
            schemaThreeCatalogJson("dynamax", "\"dmax_level\": 10, \"gmax_factor\": true,")
                .replaceFirst("\"dmax_level\": 10,", ""),
        )
        assertIssue(
            TowerOpponentCatalogIssueCode.MISSING_FIELD,
            schemaThreeCatalogJson("dynamax", "\"dmax_level\": 10, \"gmax_factor\": true,")
                .replaceFirst("\"gmax_factor\": true,", ""),
        )
        assertIssue(
            TowerOpponentCatalogIssueCode.INVALID_VALUE,
            schemaThreeCatalogJson("mega", "\"tera_type\": \"fire\","),
        )
        assertIssue(
            TowerOpponentCatalogIssueCode.INVALID_VALUE,
            schemaThreeCatalogJson("tera", "\"tera_type\": \"fire\", \"dmax_level\": 10, \"gmax_factor\": false,"),
        )
        assertIssue(
            TowerOpponentCatalogIssueCode.INVALID_VALUE,
            schemaThreeCatalogJson("dynamax", "\"dmax_level\": 10, \"gmax_factor\": false, \"tera_type\": \"fire\","),
        )
    }

    @Test
    fun `schema three rejects nonstandard tera types and invalid dynamax values`() {
        assertIssue(
            TowerOpponentCatalogIssueCode.INVALID_VALUE,
            schemaThreeCatalogJson("tera", "\"tera_type\": \"stellar\","),
        )
        assertIssue(
            TowerOpponentCatalogIssueCode.INVALID_VALUE,
            schemaThreeCatalogJson("dynamax", "\"dmax_level\": 11, \"gmax_factor\": true,"),
        )
        assertIssue(
            TowerOpponentCatalogIssueCode.INVALID_VALUE,
            schemaThreeCatalogJson("dynamax", "\"dmax_level\": 10, \"gmax_factor\": \"yes\","),
        )
    }

    @Test
    fun `schema three rejects invalid mechanic properties even on an unreferenced set`() {
        val root = JsonParser.parseString(
            schemaThreeCatalogJson("tera", "\"tera_type\": \"fire\","),
        ).asJsonObject
        val unreferenced = root.getAsJsonArray("sets").first().asJsonObject.deepCopy().apply {
            addProperty("set_id", "unused_set")
            addProperty("species_id", "cobblemon:unused_species")
            addProperty("held_item_id", "minecraft:unused_item")
            addProperty("dmax_level", 10)
        }
        root.getAsJsonArray("sets").add(unreferenced)

        assertIssue(TowerOpponentCatalogIssueCode.INVALID_VALUE, root.toString())
    }

    @Test
    fun `schema two requires one of the three approved mechanics`() {
        assertIssue(
            TowerOpponentCatalogIssueCode.MISSING_FIELD,
            validCatalogJson().replace("                  \"mechanic_id\": \"mega\",\n", ""),
        )
        assertIssue(
            TowerOpponentCatalogIssueCode.INVALID_VALUE,
            validCatalogJson().replace("\"mechanic_id\": \"mega\"", "\"mechanic_id\": \"none\""),
        )
    }

    @Test
    fun `schema one remains readable only as a legacy mechanic-free catalog`() {
        val legacyJson = validCatalogJson()
            .replace("\"schema_version\": 2", "\"schema_version\": 1")
            .replace("                  \"mechanic_id\": \"mega\",\n", "")

        val loaded = TowerOpponentCatalogLoader.load(StringReader(legacyJson)) as TowerOpponentCatalogLoadResult.Loaded

        assertEquals(null, loaded.catalog.profilesFor(TowerRank.RANK_1, TowerBattleFormat.SINGLE, TowerOpponentKind.REGULAR)
            .single().mechanic)
        assertTrue(
            loaded.catalog.profilesFor(
                TowerRank.RANK_1,
                TowerBattleFormat.SINGLE,
                TowerOpponentKind.REGULAR,
                MajorBattleMechanic.MEGA,
            ).isEmpty(),
        )
    }

    @Test
    fun `rejects duplicate ids invalid scalar values and unknown rank ids`() {
        assertIssue(
            TowerOpponentCatalogIssueCode.DUPLICATE_ID,
            validCatalogJson().replace("\"set_id\": \"set_6\"", "\"set_id\": \"set_1\""),
        )
        assertIssue(
            TowerOpponentCatalogIssueCode.INVALID_VALUE,
            validCatalogJson().replace("\"ai_skill\": 1", "\"ai_skill\": 6"),
        )
        assertIssue(
            TowerOpponentCatalogIssueCode.INVALID_VALUE,
            validCatalogJson().replace("\"rank_ids\": [\"1\"]", "\"rank_ids\": [\"missing\"]"),
        )
        assertIssue(
            TowerOpponentCatalogIssueCode.INVALID_VALUE,
            validCatalogJson().replaceFirst("\"hp\": 15", "\"hp\": 32"),
        )
        assertIssue(
            TowerOpponentCatalogIssueCode.INVALID_VALUE,
            validCatalogJson().replaceFirst(
                "\"hp\": 0, \"attack\": 0, \"defense\": 0",
                "\"hp\": 252, \"attack\": 252, \"defense\": 7",
            ),
        )
    }

    @Test
    fun `rejects missing set references and pools smaller than six`() {
        assertIssue(
            TowerOpponentCatalogIssueCode.UNKNOWN_REFERENCE,
            validCatalogJson().replaceFirst("\"set_6\"", "\"missing_set\""),
        )
        assertIssue(
            TowerOpponentCatalogIssueCode.INSUFFICIENT_POOL,
            validCatalogJson().replace(", \"set_6\"", ""),
        )
    }

    @Test
    fun `allows alternate sets for one species only when a legal selected team still exists`() {
        val legalAlternates = validCatalogJson()
            .replace("\"format\": \"single\"", "\"format\": \"double\"")
            .replace("\"species_id\": \"cobblemon:species_6\"", "\"species_id\": \"cobblemon:species_1\"")
        val loaded = TowerOpponentCatalogLoader.load(StringReader(legalAlternates))
        assertTrue(loaded is TowerOpponentCatalogLoadResult.Loaded)

        val impossible = legalAlternates
            .replace(Regex("cobblemon:species_[2-5]"), "cobblemon:species_1")
        assertIssue(TowerOpponentCatalogIssueCode.NO_LEGAL_TEAM, impossible)
    }

    @Test
    fun `failed reload preserves the previously published immutable snapshot`() {
        val store = TowerOpponentCatalogStore()
        val loaded = store.reload(StringReader(validCatalogJson())) as TowerOpponentCatalogLoadResult.Loaded
        val before = store.snapshot()

        val rejected = store.reload(StringReader("{}"))

        assertTrue(rejected is TowerOpponentCatalogLoadResult.Rejected)
        assertSame(loaded.catalog, before)
        assertSame(before, store.snapshot())
        assertNull(TowerOpponentCatalogStore().snapshot())
    }

    private fun assertIssue(code: TowerOpponentCatalogIssueCode, json: String) {
        val result = TowerOpponentCatalogLoader.load(StringReader(json))
        result as TowerOpponentCatalogLoadResult.Rejected
        assertTrue(result.issues.any { it.code == code }, "Expected $code but got ${result.issues}")
    }

    private fun validCatalogJson(): String {
        val setIds = (1..6).joinToString(", ") { "\"set_$it\"" }
        val sets = (1..6).joinToString(",\n") { index ->
            """
            {
              "set_id": "set_$index",
              "set_tier": 1,
              "species_id": "cobblemon:species_$index",
              "ability_id": "cobblemon:ability_$index",
              "nature_id": "cobblemon:hardy",
              "held_item_id": "minecraft:item_$index",
              "moves": ["cobblemon:move_$index"],
              "ivs": {"hp": 15, "attack": 15, "defense": 15, "special_attack": 15, "special_defense": 15, "speed": 15},
              "evs": {"hp": 0, "attack": 0, "defense": 0, "special_attack": 0, "special_defense": 0, "speed": 0}
            }
            """.trimIndent()
        }
        return """
            {
              "schema_version": 2,
              "catalog_id": "mbc_core",
              "profiles": [
                {
                  "profile_id": "r1_single_balanced",
                  "display_name_key": "trainer.cobblemon_more_battle_content.r1_single_balanced",
                  "rank_ids": ["1"],
                  "format": "single",
                  "opponent_kind": "regular",
                  "mechanic_id": "mega",
                  "weight": 100,
                  "ai_skill": 1,
                  "theme": "balanced",
                  "set_pool": [$setIds]
                }
              ],
              "sets": [
                $sets
              ]
            }
        """.trimIndent()
    }

    private fun schemaThreeCatalogJson(mechanicId: String, mechanicFields: String): String =
        validCatalogJson()
            .replace("\"schema_version\": 2", "\"schema_version\": 3")
            .replace("\"mechanic_id\": \"mega\"", "\"mechanic_id\": \"$mechanicId\"")
            .replace("\"moves\":", "$mechanicFields\n              \"moves\":")
}
