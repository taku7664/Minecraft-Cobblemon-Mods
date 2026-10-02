package jbro.cobblemon.mcc.betterai.engine

import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * Which members each Champion brings beside the ace against one challenger's six, at every AI tier and at random:
 * the Tower's preview reading should pick differently by tier, sharper at higher tiers.
 * Report: build/reports/tower-preview-picks.md.
 *
 *     ./gradlew :more-cobblemon-contents:unitTest -Pscope=engine -Ptests=TowerPreviewPickScenario -Psweeps
 */
@EnabledIfSystemProperty(named = "betterai.sweeps", matches = "true")
class TowerPreviewPickScenarioTest : TowerScenarioBase() {
    private val entry = firepower + listOf(
        fullyTrained("garchomp", listOf("earthquake", "outrage", "stoneedge", "firefang"), "roughskin", "choicescarf", "Jolly", "atk"),
        fullyTrained("rotomwash", listOf("hydropump", "voltswitch", "willowisp", "painsplit"), "levitate", "leftovers", "Modest", "spa"),
        fullyTrained("clefable", listOf("moonblast", "flamethrower", "softboiled", "calmmind"), "magicguard", "sitrusberry", "Modest", "spa"),
    )

    @Test
    fun `Champions' picks against one challenger by AI tier`() {
        val draws = 300
        val report = StringBuilder("# Champion picks by AI tier\n\nChallenger's six: Dragonite, Kingambit, Gholdengo, " +
            "Garchomp, Rotom-Wash, Clefable. $draws draws per cell; how often each member comes beside the ace.\n\n")
        val readings = listOf("random" to null) + listOf(BattleTrainerTier.STANDARD, BattleTrainerTier.ADVANCED, BattleTrainerTier.BOSS)
            .map { it.name.lowercase() to previewScorer(it, entry) }
        championAces.keys.forEach { name ->
            report.append("## $name\n\n| tier | picks beside the ace |\n|---|---|\n")
            readings.forEach { (label, scorer) ->
                val counts = HashMap<String, Int>()
                val random = Random(20261002L + name.hashCode())
                repeat(draws) {
                    championTeam(random, scorer, champion = name).drop(1).forEach { counts.merge(it.species, 1, Int::plus) }
                }
                report.append("| $label | " + counts.entries.sortedByDescending { it.value }
                    .joinToString(", ") { "${it.key} ${"%.0f%%".format(it.value * 100.0 / draws)}" } + " |\n")
            }
            report.append("\n")
        }
        val out = Path.of("build/reports/tower-preview-picks.md")
        Files.createDirectories(out.parent)
        Files.writeString(out, report)
        println(report)
    }
}
