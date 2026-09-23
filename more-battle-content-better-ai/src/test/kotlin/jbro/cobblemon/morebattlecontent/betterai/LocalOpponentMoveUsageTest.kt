package jbro.cobblemon.morebattlecontent.betterai

import java.io.StringReader
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicFutureActionFactory
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalHypothesisPriorityReservation
import jbro.cobblemon.morebattlecontent.betterai.state.LocalMoveUsageTable
import jbro.cobblemon.morebattlecontent.betterai.state.LocalOpponentMoveHypotheses
import jbro.cobblemon.morebattlecontent.betterai.state.LocalOpponentMoveUsage
import jbro.cobblemon.morebattlecontent.betterai.state.RecursiveActionHistory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalOpponentMoveUsageTest {
    @Test
    fun `usage table resolves canonical species forms and move ids`() {
        val table = LocalMoveUsageTable.parse(StringReader(snapshotJson(
            "\"urshifurapidstrike\": {\"aquajet\": 0.72, \"surgingstrikes\": 0.93}," +
                "\"dragonite\": {\"extremespeed\": 0.81}",
        )))

        assertEquals(0.72, table.rate("cobblemon:urshifu", "rapid_strike", "Aqua Jet"))
        assertEquals(0.93, table.rate("urshifu", "rapid-strike", "cobblemon:surging_strikes"))
        assertEquals(0.81, table.rate("cobblemon:dragonite", "normal", "Extreme Speed"))
        assertNull(table.rate("cobblemon:dragonite", null, "Ice Spinner"))
    }

    @Test
    fun `usage table rejects malformed probabilities and canonical collisions`() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalMoveUsageTable.parse(StringReader(snapshotJson("\"probe\": {\"move\": 1.01}")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalMoveUsageTable.parse(StringReader(snapshotJson(
                "\"probe-form\": {\"move\": 0.4}, \"probeform\": {\"other\": 0.3}",
            )))
        }
    }

    @Test
    fun `bundled BSS snapshot has pinned provenance and normalized move presence`() {
        val table = LocalOpponentMoveUsage.forFormat(BattleFormat.SINGLE)

        assertTrue(table.loaded)
        assertEquals("gen9bssregj", table.source?.format)
        assertEquals("2025-12", table.source?.month)
        assertEquals(1500, table.source?.cutoff)
        assertEquals(26_139, table.source?.battleCount)
        assertEquals(407, table.speciesCount)
        assertEquals(0.957875, table.rate("cobblemon:fluttermane", null, "moonblast")!!, 0.000001)
        assertEquals(0.216901, table.rate("cobblemon:fluttermane", null, "powergem")!!, 0.000001)
    }

    @Test
    fun `bundled snapshots are selected by singles and doubles format`() {
        val singles = LocalOpponentMoveUsage.forFormat(BattleFormat.SINGLE)
        val doubles = LocalOpponentMoveUsage.forFormat(BattleFormat.DOUBLE)

        assertEquals("gen9bssregj", singles.source?.format)
        assertEquals(26_139, singles.source?.battleCount)
        assertEquals("gen9vgc2025regj", doubles.source?.format)
        assertEquals(200_077, doubles.source?.battleCount)
        assertNotEquals(
            singles.rate("cobblemon:incineroar", null, "fakeout"),
            doubles.rate("cobblemon:incineroar", null, "fakeout"),
            "Singles and doubles must not silently share one move-usage table",
        )
    }

    @Test
    fun `usage ranking filters unobserved learnset moves before search`() {
        val fixture = fixture()
        val table = LocalMoveUsageTable.parse(StringReader(snapshotJson(
            "\"probe\": {\"common\": 0.80, \"niche\": 0.12}",
        )))

        val ranked = LocalOpponentMoveHypotheses.usageRankedOptions(
            fixture.opponent,
            fixture.catalog,
            RecursiveActionHistory(),
            table,
        )

        assertEquals(listOf("common", "niche"), ranked.keys.toList())
        assertFalse("unobserved" in ranked)
    }

    @Test
    fun `cap keeps low usage priority threat then fills remaining slots by usage`() {
        val fixture = fixture()
        val table = LocalMoveUsageTable.parse(StringReader(snapshotJson(
            "\"probe\": {\"common\": 0.80, \"niche\": 0.12, \"quickattack\": 0.01}",
        )))

        val actions = PublicFutureActionFactory.actions(
            fixture.state,
            BattleSide.OPPONENT,
            fixture.catalog,
            unknownMovePokemonIds = setOf(fixture.opponent.battlePokemonId),
            includeMoveHypotheses = true,
            hypotheticalMoveLimitPerSlot = 2,
            hypotheticalPriorityReservation = LocalHypothesisPriorityReservation.SINGLE,
            moveUsage = table,
        )

        val moves = actions.filter { it.kind == BattleActionKind.USE_MOVE }.mapNotNull { it.moveId }.toSet()
        assertEquals(setOf("quickattack", "common"), moves)
        assertTrue(actions.any { "unknown_public_response" in it.tags })
    }

    private fun fixture(): Fixture {
        val opponent = BattlePokemonStateView(UUID.randomUUID(), BattleSide.OPPONENT, 0, "probe", null, 50,
            1.0, null, emptyMap(), emptySet(), null, null, false, setOf("normal"))
        val ally = BattlePokemonStateView(UUID.randomUUID(), BattleSide.ALLY, 0, "target", null, 50,
            1.0, null, emptyMap(), emptySet(), null, null, false, setOf("normal"))
        val details = BattleMoveCandidateView("normal", BattleMoveDamageCategory.PHYSICAL, 40.0, 100.0, 0, 8)
        val moves = linkedMapOf(
            "common" to details.copy(power = 30.0),
            "niche" to details.copy(power = 200.0),
            "quickattack" to details.copy(power = 20.0, priority = 1),
            "unobserved" to details.copy(power = 500.0),
        )
        val catalog = BattlePublicActionCatalogView(emptyList(), candidatePools = listOf(
            BattlePublicMoveCandidatePoolView(opponent.battlePokemonId, "probe", null, moves.keys, "fixture", moves),
        ))
        val state = BattleStateView(UUID.randomUUID(), BattleFormat.SINGLE, 1, listOf(ally, opponent),
            BattleFieldStateView.empty(), mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1),
            emptyList(), emptyList())
        return Fixture(state, opponent, catalog)
    }

    private fun snapshotJson(species: String) = """
        {
          "schemaVersion": 1,
          "source": {
            "provider": "fixture",
            "format": "gen9bssregj",
            "month": "2025-12",
            "cutoff": 1500,
            "battleCount": 26139,
            "url": "https://example.invalid/source.json",
            "rawSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          },
          "species": {$species}
        }
    """.trimIndent()

    private data class Fixture(
        val state: BattleStateView,
        val opponent: BattlePokemonStateView,
        val catalog: BattlePublicActionCatalogView,
    )
}
