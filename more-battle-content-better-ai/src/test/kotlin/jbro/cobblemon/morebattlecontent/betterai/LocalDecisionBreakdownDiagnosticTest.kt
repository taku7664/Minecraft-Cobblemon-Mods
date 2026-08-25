package jbro.cobblemon.morebattlecontent.betterai

import org.junit.jupiter.api.Test

/**
 * Prints the full score decomposition for the decisions that changed when the scoring units were
 * unified, so the change can be read as numbers instead of argued from a single winning action id.
 *
 * This asserts nothing. It exists to make a disagreement between two tunings inspectable.
 */
class LocalDecisionBreakdownDiagnosticTest {
    private val simulation = LocalTacticalBrainSimulationTest()

    @Test
    fun `contested decisions report their score decomposition under both tunings`() {
        val report = buildString {
            appendLine("=".repeat(110))
            appendLine("DECISION BREAKDOWN  (score units: 100 = one full HP bar, 200 = one Pokemon removed)")
            appendLine("=".repeat(110))
            LocalContestedDecisionCatalog.all(simulation).forEach { scenario ->
                appendLine()
                appendLine("### ${scenario.name}")
                appendLine("    expected=${scenario.expected}")
                val (legacy, current) = LocalDecisionInstrumentation.compare(scenario.context)
                append(legacy.format("legacy "))
                append(current.format("current"))
                appendLine(
                    "    verdict: legacy=${legacy.chosenByRanking?.actionId} " +
                        "current=${current.chosenByRanking?.actionId}",
                )
            }
        }
        println(report)
    }
}
