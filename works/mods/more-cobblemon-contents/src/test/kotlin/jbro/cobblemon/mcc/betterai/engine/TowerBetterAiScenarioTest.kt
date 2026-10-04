package jbro.cobblemon.mcc.betterai.engine

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import jbro.cobblemon.mcc.betterai.EmbeddedPresetAudit
import jbro.cobblemon.mcc.betterai.EmbeddedTeamBattle
import jbro.cobblemon.mcc.internal.ai.BattleDifficultyProfile
import jbro.cobblemon.mcc.internal.ai.BattleDifficultyProfiles
import jbro.cobblemon.mcc.internal.ai.BattleTrainerProfile
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * The Battle Tower (Endless) with the real Better AI on both sides, on Cobblemon's Showdown: the challenger brings
 * Dragonite, Kingambit and Gholdengo and plays at the Advanced tier (a strong player); each win's opponent is the
 * Tower's own: regular trainers on their stage's sets and AI tier (Introductory to the 5th win, Standard to the
 * 10th, Advanced after), and at every 5th win a fully trained Champion on the Boss tier with the Champion
 * personality, all one level higher every 5 wins. Single 3 vs 3, no Tera (the test adapter offers no gimmicks).
 * Report: build/reports/tower-betterai.md.
 *
 *     ./gradlew :more-cobblemon-contents:unitTest -Pscope=engine -Ptests=TowerBetterAiScenario -Poracle
 */
@EnabledIfSystemProperty(named = "betterai.oracle", matches = "true")
class TowerBetterAiScenarioTest : TowerScenarioBase() {
    private class Cell(val label: String, val opponent: (Random) -> List<jbro.cobblemon.mcc.betterai.engine.RefSet>, val profile: BattleTrainerProfile)


    /** Opponents read the challenger's preview as the Tower's do, unless TOWER_PREVIEW=0 keeps the random pick. */
    private val readsPreview = System.getenv("TOWER_PREVIEW") != "0"

    private fun scorer(tier: jbro.cobblemon.mcc.internal.ai.BattleTrainerTier) = if (readsPreview) previewScorer(tier, previewEntry) else null

    private fun regular(tier: Int, level: Int, difficulty: BattleDifficultyProfile, skill: Int): Cell {
        val reading = scorer(difficulty.tier)
        return Cell("tier $tier Lv$level ${difficulty.tier.name.lowercase()}",
            { r -> previewTeam(r, pool(false, tier), reading).map { it.copy(level = level) } },
            BattleTrainerProfile.balanced(skill, difficulty))
    }

    private fun champion(level: Int): Cell {
        val reading = scorer(jbro.cobblemon.mcc.internal.ai.BattleTrainerTier.BOSS)
        return Cell("Champion Lv$level boss", { r -> championTeam(r, reading).map { it.copy(level = level) } }, BattleTrainerProfile.champion(4))
    }

    private fun team(sets: List<jbro.cobblemon.mcc.betterai.engine.RefSet>, side: String) = JsonObject().apply {
        add("setIds", JsonArray().apply { sets.forEach { add(it.species) } })
        add("sets", JsonArray().apply { sets.forEachIndexed { index, set -> add(set.toJson(side, index)) } })
    }

    @Test
    fun `how far an Advanced player with a strong team runs through the Tower (Endless)`() {
        val battles = System.getenv("TOWER_BATTLES")?.toInt() ?: 40
        val root = Path.of("build/reports/tower-betterai-runs/${UUID.randomUUID()}")
        Files.createDirectories(root.parent)
        EmbeddedPresetAudit.run(root.resolve("audit"), teamPairs = 0)
        val engine = root.resolve("audit/engine")
        val player = BattleTrainerProfile.balanced(4, BattleDifficultyProfiles.ADVANCED)
        // Each win's opponent up to the 30th, by the Tower (Endless) rules.
        val cells = listOf(
            regular(1, 50, BattleDifficultyProfiles.INTRODUCTORY, 1), champion(50),
            regular(2, 51, BattleDifficultyProfiles.STANDARD, 2), champion(51),
            regular(3, 52, BattleDifficultyProfiles.ADVANCED, 4), champion(52),
            regular(3, 53, BattleDifficultyProfiles.ADVANCED, 4), champion(53),
            regular(4, 54, BattleDifficultyProfiles.ADVANCED, 4), champion(54),
            regular(4, 55, BattleDifficultyProfiles.ADVANCED, 4), champion(55),
        )
        fun cellOf(win: Int): Int = 2 * ((win - 1) / 5) + if (win % 5 == 0) 1 else 0
        // The Better AI's search recurses deeply; give each battle the stack the server's battle threads have.
        val executor = Executors.newFixedThreadPool(System.getenv("TOWER_THREADS")?.toInt() ?: 6) { task ->
            Thread(null, task, "tower-betterai", 256L shl 20).apply { isDaemon = true }
        }
        val report = StringBuilder("# Battle Tower (Endless) with Better AI\n\n$battles battles per cell. Challenger: " +
            "Dragonite, Kingambit, Gholdengo on the Advanced tier. Opponents as the Tower draws them, one level higher " +
            "every 5 wins; Champions on the Boss tier with the Champion personality. Single 3 vs 3, no Tera. Opponents " +
            (if (readsPreview) "pick their team from the challenger's six (with Garchomp, Rotom-Wash and Clefable) as their " +
                "AI tier reads them" else "pick their team at random") + ".\n\n" +
            "| opponent | wins | losses | unfinished | errors | win rate |\n|---|---|---|---|---|---|\n")
        val out = Path.of("build/reports/tower-betterai.md")
        val rates = cells.mapIndexed { cellIndex, cell ->
            val futures = (0 until battles).map { index ->
                executor.submit<String> {
                    val random = Random(20261002L * 31 + cellIndex * 1000L + index)
                    val pair = JsonObject().apply {
                        addProperty("battleFormat", "SINGLE")
                        add("battleSeed", JsonArray().apply { repeat(4) { add(random.nextInt(65536)) } })
                        add("p1", team(firepower, "p1"))
                        add("p2", team(cell.opponent(random), "p2"))
                    }
                    try {
                        val result = EmbeddedTeamBattle.run(engine, pair, root.resolve("cell-$cellIndex/battle-$index"), maxTurns = 100,
                            p1TrainerProfile = player, p2TrainerProfile = cell.profile)
                        val winner = result["winner"]?.takeUnless { it.isJsonNull }?.asString
                        if (result["status"].asString != "COMPLETE") "unfinished" else if (winner == "p1") "win" else "loss"
                    } catch (failure: Throwable) {
                        System.err.println("TOWER_BETTERAI_ERROR cell=$cellIndex battle=$index ${failure.stackTraceToString().take(3000)}")
                        "error"
                    }
                }
            }
            val outcomes = futures.map { it.get(30, TimeUnit.MINUTES) }
            val wins = outcomes.count { it == "win" }
            // An unfinished battle is no win at the Tower; errors are left out of the rate.
            val decided = outcomes.count { it != "error" }
            val rate = if (decided == 0) 0.0 else wins.toDouble() / decided
            report.append("| ${cell.label} | $wins | ${outcomes.count { it == "loss" }} | ${outcomes.count { it == "unfinished" }} | " +
                "${outcomes.count { it == "error" }} | ${"%.1f%%".format(rate * 100)} |\n")
            Files.writeString(out, report)
            println("TOWER_BETTERAI cell=${cell.label} win=$wins decided=$decided")
            rate
        }
        executor.shutdown()
        fun reach(wins: Int) = (1..wins).fold(1.0) { p, win -> p * rates[cellOf(win)] }
        report.append("\n| reach 5 | reach 10 | reach 15 | reach 20 | reach 25 | reach 30 |\n|---|---|---|---|---|---|\n| " +
            listOf(5, 10, 15, 20, 25, 30).joinToString(" | ") { "%.1f%%".format(reach(it) * 100) } + " |\n")
        Files.writeString(out, report)
        println(report)
    }
}
