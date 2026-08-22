package jbro.cobblemon.morebattlecontent.betterai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalTacticalScenarioBattleTest {
    @Test
    fun `three complete cycle versus offense teams produce auditable turn logs`() {
        val reports = scenarios.map(LocalTacticalScenarioBattle::run)
        reports.forEach { report -> println(report.documentationLog()) }

        assertEquals(3, reports.size)
        assertTrue(reports.all { it.turns.isNotEmpty() })
        assertTrue(reports.all { it.definition.cycleSetIds.size == 3 && it.definition.offenseSetIds.size == 3 })
        assertTrue(reports.sumOf { it.cycleStatusMoves } > 0)
        assertTrue(reports.sumOf { it.cycleVoluntarySwitches } > 0)
        assertFalse(reports.any { it.stalled })
        assertTrue(reports.all { report -> report.turns.all { it.result.isNotBlank() } })
        assertFalse(reports.any { "cycle:cycle:" in it.documentationLog() || "offense:offense:" in it.documentationLog() })
        assertFalse(reports.any { report ->
            report.turns.drop(1).any { turn ->
                "firstimpression" in turn.cycleActual || "firstimpression" in turn.offenseActual
            }
        })
    }

    private val scenarios = listOf(
        LocalTacticalScenarioDefinition(
            name = "regenerator-cycle-vs-physical-setup",
            cycleSetIds = listOf("slowbro_preset_3", "amoonguss_preset_2", "corviknight_preset_1"),
            offenseSetIds = listOf("garchomp_preset_1", "scizor_preset_2", "blaziken_preset_3"),
            seed = 8_230_101,
        ),
        LocalTacticalScenarioDefinition(
            name = "status-pivot-cycle-vs-priority-sweep",
            cycleSetIds = listOf("clefable_preset_4", "whimsicott_preset_3", "gliscor_preset_4"),
            offenseSetIds = listOf("dragonite_preset_1", "volcarona_preset_1", "mimikyu_preset_1"),
            seed = 8_230_202,
        ),
        LocalTacticalScenarioDefinition(
            name = "recovery-cycle-vs-fast-physical-pressure",
            cycleSetIds = listOf("arcanine_preset_2", "volcarona_preset_3", "slowbro_preset_1"),
            offenseSetIds = listOf("arcanine_preset_1", "garchomp_preset_2", "scizor_preset_4"),
            seed = 8_230_303,
        ),
    )
}
