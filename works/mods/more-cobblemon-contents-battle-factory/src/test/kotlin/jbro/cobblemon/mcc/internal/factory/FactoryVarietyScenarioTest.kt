package jbro.cobblemon.mcc.internal.factory

import java.io.Reader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Plays many Factory runs through the real draft and opponent selectors and reports how varied the Pokemon a player
 * meets are: per round window, how evenly the pool's species come up across all runs, and within one run how often
 * a species the player already met comes back. The report lands in build/reports/factory-variety.md.
 */
class FactoryVarietyScenarioTest {
    private class SeededRandom(seed: Long) : FactoryCatalogRandom {
        private val random = Random(seed)
        override fun nextLong(bound: Long): Long = random.nextLong(bound)
        override fun nextInt(bound: Int): Int = random.nextInt(bound)
    }

    private class Tally {
        val counts = HashMap<String, Int>()
        var total = 0
        fun add(species: String) { counts.merge(species, 1, Int::plus); total++ }
        /** exp(entropy): how many equally likely species the spread is worth. */
        fun effectiveSpecies(): Double = exp(-counts.values.sumOf { val p = it.toDouble() / total; p * ln(p) })
        fun topShare(fraction: Double): Double {
            val top = counts.values.sortedDescending().take(maxOf(1, (counts.size * fraction).toInt()))
            return top.sum().toDouble() / total
        }
        fun maxOverMean(): Double = counts.values.max() / (total.toDouble() / counts.size)
    }

    @Test
    fun `a Factory run keeps meeting new Pokemon and no species crowds out the rest`() {
        val catalog = bundledCatalog()
        val report = StringBuilder("# Battle Factory variety scenario\n\n")
        val runs = 2_000
        val battles = 49
        var worstMaxOverMean = 0.0
        FactoryLevelMode.entries.forEach { levelMode ->
            val format = FactoryBattleFormat.SINGLE
            val opponentsByRound = sortedMapOf<Int, Tally>()
            val draftsByRound = sortedMapOf<Int, Tally>()
            var repeatedOpponents = 0
            var opponentsSeen = 0
            var repeatedOffers = 0
            var offersSeen = 0
            var distinctPerRun = 0L
            var mostMetPerRun = 0L
            repeat(runs) { run ->
                val random = SeededRandom(run.toLong() * 7_919 + levelMode.ordinal)
                val drafts = FactoryDraftSelector(catalog, random)
                val opponents = FactoryOpponentSelector(catalog, random)
                val recentOffers = ArrayDeque<String>()
                val recentOpponents = ArrayDeque<String>()
                val metThisRun = HashMap<String, Int>()
                val offeredThisRun = HashSet<String>()
                for (battle in 1..battles) {
                    val round = FactoryProgression.roundForBattle(battle)
                    if ((battle - 1) % FactoryProgression.BATTLES_PER_ROUND == 0) {
                        // A new round opens with a fresh draft, as after each round's rent-and-trade.
                        val draft = requireNotNull(drafts.select(levelMode, round, battle - 1, recentOffers.toSet()))
                        draft.sets.forEach { set ->
                            draftsByRound.getOrPut(round, ::Tally).add(set.speciesId)
                            offersSeen++
                            if (!offeredThisRun.add(set.speciesId)) repeatedOffers++
                            recentOffers.addLast(set.speciesId)
                            while (recentOffers.size > 18) recentOffers.removeFirst()
                        }
                    }
                    val selected = opponents.select(format, levelMode, round, recentSpeciesIds = recentOpponents.toSet())
                        as FactoryOpponentSelectionResult.Selected
                    selected.team.forEach { set ->
                        recentOpponents.addLast(set.speciesId)
                        while (recentOpponents.size > 24) recentOpponents.removeFirst()
                        opponentsByRound.getOrPut(round, ::Tally).add(set.speciesId)
                        opponentsSeen++
                        if (metThisRun.merge(set.speciesId, 1, Int::plus)!! > 1) repeatedOpponents++
                    }
                }
                distinctPerRun += metThisRun.size
                mostMetPerRun += metThisRun.values.max()
            }
            report.append("## ${levelMode.id}, singles, $runs runs of $battles battles\n\n")
            report.append("| round | pool species | opponents: effective species | top 10% share | most / average |" +
                " offers: effective species |\n|---|---|---|---|---|---|\n")
            opponentsByRound.forEach { (round, tally) ->
                val pool = catalog.rentalPool(FactoryProgression.opponentPoolWindow(levelMode, round)).map { it.speciesId }.toSet().size
                worstMaxOverMean = maxOf(worstMaxOverMean, tally.maxOverMean())
                report.append("| $round | $pool | ${"%.0f".format(tally.effectiveSpecies())} | ${"%.1f%%".format(tally.topShare(0.1) * 100)} |" +
                    " ${"%.2f".format(tally.maxOverMean())} | ${"%.0f".format(draftsByRound.getValue(round).effectiveSpecies())} |\n")
            }
            report.append("\n- Opponent Pokemon that repeat a species already met in the same run: " +
                "${"%.1f%%".format(repeatedOpponents * 100.0 / opponentsSeen)}\n")
            report.append("- Distinct opponent species met per run: ${"%.1f".format(distinctPerRun.toDouble() / runs)} of ${battles * format.selectionSize}\n")
            report.append("- The species met most in a run, on average: ${"%.2f".format(mostMetPerRun.toDouble() / runs)} times\n")
            report.append("- Rental offers repeating a species already offered in the run: ${"%.1f%%".format(repeatedOffers * 100.0 / offersSeen)}\n\n")
        }
        val out = Paths.get("build/reports/factory-variety.md")
        Files.createDirectories(out.parent)
        Files.writeString(out, report)
        println(report)
        // Uniform draws keep every species near its fair share; three times the average would mean a crowding species.
        assertTrue(worstMaxOverMean < 3.0, "A species comes up $worstMaxOverMean times its fair share")
    }

    private fun bundledCatalog(): FactoryCatalog {
        val result = FactoryCatalogLoader.loadSeparated(readers("/data/more_cobblemon_contents/mcc-battle-factory/trainers"),
            readers("/data/more_cobblemon_contents/mcc-battle-factory/rental-sets"))
        return (result as FactoryCatalogLoadResult.Loaded).catalog
    }

    private fun readers(directory: String): List<Pair<String, Reader>> {
        val root = Paths.get(javaClass.getResource(directory)!!.toURI())
        return Files.list(root).use { paths -> paths.filter { it.toString().endsWith(".json") }.sorted().toList() }
            .map { path: Path -> path.fileName.toString() to Files.newBufferedReader(path) }
    }
}
