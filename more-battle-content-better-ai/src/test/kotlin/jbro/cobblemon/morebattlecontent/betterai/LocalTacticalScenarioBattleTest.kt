package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventKind
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalTacticalScenarioBattleTest {
    @Test
    fun `three complete cycle versus offense teams produce auditable turn logs`() {
        // 15 turns is too short for one of these archetypes to resolve. A status-pivot cycle team
        // against a bulky sweeper is a deliberately grindy matchup, and once recovery moves report
        // their healing correctly - see the probability fix in PublicBattleTacticalCalculator - both
        // the old and the new weights run past 15 turns on it. The self-play harness already uses 30;
        // matching it keeps "did not stall" a statement about the AI rather than about the clock.
        val reports = scenarios.map { LocalTacticalScenarioBattle.run(it, MAXIMUM_TURNS) }
        reports.forEach { report -> println(report.documentationLog()) }

        // Same three scenarios under the pre-fix weights, so a stall can be attributed rather than
        // assumed. Both columns are printed because "this stalls" only means something next to
        // whether it stalled before.
        val legacyReports = scenarios.map {
            LocalTacticalScenarioBattle.run(
                it,
                maximumTurns = MAXIMUM_TURNS,
                cycleTuning = LocalDecisionTuning.LEGACY,
                offenseTuning = LocalDecisionTuning.LEGACY,
            )
        }
        println(
            buildString {
                appendLine("STALL ATTRIBUTION  (legacy weights vs current weights)")
                scenarios.indices.forEach { index ->
                    appendLine(
                        String.format(
                            "  %-46s legacy: winner=%-8s stalled=%-6s | current: winner=%-8s stalled=%s",
                            scenarios[index].name,
                            legacyReports[index].winner ?: "draw",
                            legacyReports[index].stalled,
                            reports[index].winner ?: "draw",
                            reports[index].stalled,
                        ),
                    )
                }
            },
        )

        assertEquals(3, reports.size)
        assertTrue(reports.all { it.turns.isNotEmpty() })
        assertTrue(reports.all { it.definition.cycleSetIds.size == 3 && it.definition.offenseSetIds.size == 3 })
        assertTrue(reports.sumOf { it.cycleStatusMoves } > 0)
        assertTrue(reports.sumOf { it.cycleVoluntarySwitches } > 0)
        assertFalse(reports.any { it.stalled })
        assertTrue(reports.all { it.publicEvidenceCounts.getOrDefault(BattleObservedEventKind.ACTION_ORDER, 0) > 0 })
        assertTrue(reports.all { it.publicEvidenceCounts.getOrDefault(BattleObservedEventKind.MOVE_USED, 0) > 0 })
        assertTrue(reports.all { it.publicEvidenceCounts.getOrDefault(BattleObservedEventKind.HP_CHANGED, 0) > 0 })
        assertTrue(reports.sumOf { it.publicEvidenceCounts.getOrDefault(BattleObservedEventKind.SWITCHED, 0) } > 0)
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
    private companion object {
        const val MAXIMUM_TURNS = 30
    }
}
