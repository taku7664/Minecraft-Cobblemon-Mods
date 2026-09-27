package jbro.cobblemon.mcc.betterai.engine

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import jbro.cobblemon.mcc.betterai.engine.dex.Effect
import jbro.cobblemon.mcc.betterai.engine.hooks.EngineHooks
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Every workbook entry, in usage order, played on Showdown and on the engine in a few generic battles.
 *
 * An entry passes when all its battles give identical logs and every handler its data declares is ported.
 * Passing entries are written to the coverage file (their workbook rows turn yellow). The test fails only
 * when an entry listed in `sweep-baseline.txt` stops passing, so the baseline is a ratchet: after porting,
 * copy the new passes from `build/reports/ai-engine-sweep-pass.txt` into it.
 */
class EngineSweepTest {
    private val dex = EngineReferee.dex
    private val seeds = listOf(intArrayOf(3, 1, 4, 1), intArrayOf(27, 18, 28, 18))

    private class Entry(val category: String, val id: String, val scenarios: List<RefScenario>)

    private fun priority(kind: String): List<String> {
        val text = requireNotNull(javaClass.getResourceAsStream("/ai-engine/priority.json")).reader().use { it.readText() }
        return JsonParser.parseString(text).asJsonObject.getAsJsonArray(kind).map { it.asJsonObject.get("id").asString }
    }

    private fun seeded(base: RefScenario) = seeds.map { base.withSeed(it) }

    private fun moveEntry(id: String): Entry {
        val attacker = RefSet("Mew", listOf(id, "tackle"))
        val turns = listOf("move 1" to "move 1", "move 1" to "move 1", "move 1" to "move 2")
        val bench = RefSet("Blissey", listOf("tackle", "icebeam"))
        return Entry("기술", id, seeded(RefScenario("move-$id-a", listOf(attacker, RefSet("Chansey", listOf("tackle"))),
            listOf(RefSet("Snorlax", listOf("tackle", "bodyslam")), bench), turns)) +
            seeded(RefScenario("move-$id-b", listOf(attacker), listOf(RefSet("Gyarados", listOf("waterfall", "icefang")), bench), turns)))
    }

    private fun abilityEntry(id: String): Entry {
        val moves = listOf("tackle", "ember", "thunderwave", "earthquake")
        val turns = listOf("move 1" to "move 1", "move 2" to "move 2", "move 3" to "move 3", "move 4" to "move 1", "move 1" to "move 2")
        val foeMoves = listOf("tackle", "flamethrower", "icebeam", "willowisp")
        return Entry("특성", id,
            seeded(RefScenario("ability-$id-holder", listOf(RefSet("Mew", moves, ability = id), RefSet("Chansey", listOf("tackle"))),
                listOf(RefSet("Snorlax", foeMoves), RefSet("Blissey", listOf("tackle"))), turns)) +
                seeded(RefScenario("ability-$id-foe", listOf(RefSet("Snorlax", foeMoves), RefSet("Chansey", listOf("tackle"))),
                    listOf(RefSet("Mew", moves, ability = id), RefSet("Blissey", listOf("tackle"))), turns)))
    }

    private fun itemEntry(id: String): Entry {
        val item = dex.item(id)
        @Suppress("UNCHECKED_CAST")
        val mega = (item.data("megaStone") as? Map<String, Any?>)?.entries?.firstOrNull { (base, forme) ->
            dex.species(base) != null && dex.species(forme as String) != null
        }
        val holderSpecies = mega?.key ?: "Mew"
        val moves = listOf("tackle", "ember", "thunderwave", "earthquake")
        val first = if (mega != null) "move 1 mega" else "move 1"
        val turns = listOf(first to "move 1", "move 2" to "move 2", "move 3" to "move 3", "move 4" to "move 1", "move 1" to "move 2")
        val foeMoves = listOf("tackle", "flamethrower", "icebeam", "willowisp")
        return Entry("도구", id,
            seeded(RefScenario("item-$id-holder", listOf(RefSet(holderSpecies, moves, item = id), RefSet("Chansey", listOf("tackle"))),
                listOf(RefSet("Snorlax", foeMoves), RefSet("Blissey", listOf("tackle"))), turns)) +
                seeded(RefScenario("item-$id-foe", listOf(RefSet("Snorlax", foeMoves), RefSet("Chansey", listOf("tackle"))),
                    listOf(RefSet("Mew", moves, item = id), RefSet("Blissey", listOf("tackle"))), turns)))
    }

    /** Declared handlers of an entry that have no Kotlin port, as `key.handler`. */
    private fun unported(effect: Effect): List<String> = effect.declaredHooks.mapNotNull { path ->
        val dot = path.lastIndexOf('.')
        val key = if (dot < 0) effect.hookKey else "${effect.hookKey}/${path.substring(0, dot)}"
        val name = path.substring(dot + 1)
        if (EngineHooks.get(key, name) == null) "$key.$name" else null
    }

    @Test
    fun `workbook entries replay like Showdown`() {
        EngineReferee.assumeAvailable()
        val entries = priority("moves").map(::moveEntry) + priority("abilities").map(::abilityEntry) + priority("items").map(::itemEntry)
        val reference = entries.flatMap { it.scenarios }.chunked(400).fold(HashMap<String, RefResult>()) { acc, chunk ->
            acc.putAll(EngineReferee.showdown(chunk)); acc
        }
        EngineCoverage.clear("EngineSweepTest")
        val passed = ArrayList<String>()
        val report = StringBuilder("# AI engine referee sweep\n\n")
        val failures = LinkedHashMap<String, MutableList<String>>()
        for (entry in entries) {
            val effect: Effect = when (entry.category) {
                "기술" -> dex.move(entry.id)!!
                "특성" -> dex.ability(entry.id)
                else -> dex.item(entry.id)
            }
            val missing = unported(effect)
            val diffs = entry.scenarios.mapNotNull { EngineReferee.difference(reference.getValue(it.id), EngineReferee.engine(it)) }
            val key = "${entry.category}:${entry.id}"
            if (missing.isEmpty() && diffs.isEmpty()) {
                passed += key
                EngineCoverage.record(entry.category, entry.id, "*", "EngineSweepTest")
            } else {
                val reason = if (missing.isNotEmpty()) "unported ${missing.joinToString(", ")}" else diffs.first().lines().take(10).joinToString("\n    ")
                failures.getOrPut(entry.category) { ArrayList() } += "- ${entry.id}: $reason"
            }
        }
        for (category in listOf("기술", "특성", "도구")) {
            val total = entries.count { it.category == category }
            val ok = passed.count { it.startsWith("$category:") }
            report.append("## $category: $ok / $total\n\n")
            failures[category]?.forEach { report.append(it).append('\n') }
            report.append('\n')
        }
        val reports = Path.of(System.getProperty("aiengine.coverage") ?: "build/reports/x").parent
        Files.createDirectories(reports)
        Files.writeString(reports.resolve("ai-engine-sweep.md"), report)
        Files.writeString(reports.resolve("ai-engine-sweep-pass.txt"), passed.joinToString("\n", postfix = "\n"))
        val baseline = javaClass.getResourceAsStream("/ai-engine/sweep-baseline.txt")?.reader()?.use { r -> r.readLines().filter { it.isNotBlank() } }
            ?: emptyList()
        val regressed = baseline.filter { it !in passed }
        assertTrue(regressed.isEmpty()) { "Entries that used to replay like Showdown no longer do: $regressed" }
    }
}
