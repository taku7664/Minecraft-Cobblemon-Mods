package jbro.cobblemon.mcc.betterai.engine

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The Protects that punish contact, played on Showdown and on the engine. King's Shield and Obstruct are not in the
 * Gen 9 usage workbook the sweep walks ([EngineSweepTest]), and went unported: in the engine they blocked nothing, so
 * Aegislash took a U-turn through King's Shield and an Obstruct turn read as a knockout of its user (2026-10-16).
 */
class EngineContactProtectTest {
    private val seeds = listOf(intArrayOf(3, 1, 4, 1), intArrayOf(27, 18, 28, 18))

    private fun scenarios(id: String): List<RefScenario> {
        val user = RefSet("Mew", listOf(id, "tackle"))
        val bench = RefSet("Blissey", listOf("tackle", "icebeam"))
        // Twice in a row (the second may fail), then the user attacks; a contact move, a non-contact one, a status move.
        val turns = listOf("move 1" to "move 1", "move 1" to "move 2", "move 2" to "move 1")
        return listOf(
            RefScenario("protect-$id-contact", listOf(user, RefSet("Chansey", listOf("tackle"))),
                listOf(RefSet("Snorlax", listOf("bodyslam", "earthquake")), bench), turns),
            RefScenario("protect-$id-status", listOf(user),
                listOf(RefSet("Gengar", listOf("willowisp", "shadowball")), bench), turns),
        ).flatMap { base -> seeds.map { base.withSeed(it) } }
    }

    @Test
    fun `King's Shield, Obstruct and the other contact Protects replay like Showdown`() {
        EngineReferee.assumeAvailable()
        val all = listOf("kingsshield", "obstruct", "silktrap", "spikyshield", "banefulbunker", "burningbulwark").flatMap(::scenarios)
        val reference = EngineReferee.showdown(all)
        val diffs = all.mapNotNull { scenario ->
            EngineReferee.difference(reference.getValue(scenario.id), EngineReferee.engine(scenario))?.let { "${scenario.id}: ${it.lines().take(8).joinToString("\n  ")}" }
        }
        assertTrue(diffs.isEmpty(), diffs.joinToString("\n"))
    }
}
