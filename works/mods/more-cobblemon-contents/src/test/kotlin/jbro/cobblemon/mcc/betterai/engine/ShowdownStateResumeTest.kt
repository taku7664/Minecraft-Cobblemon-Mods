package jbro.cobblemon.mcc.betterai.engine

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Showdown serializes a battle after every step and the engine plays on from it ([EngineReferee.resume]): the
 * rest of the battle must log and stand exactly as Showdown's own continuation does.
 *
 * The sweep battles cover every workbook entry the engine already replays like Showdown from the start, so a
 * difference here is the reader's, not a port's. The scripted battles add positions the sweep never reaches:
 * substitutes, seeds, locks, a pivot's pending switch, a faint replacement, doubles, Mega Evolution and Tera.
 */
class ShowdownStateResumeTest {
    private val dex = EngineReferee.dex
    private val seed = intArrayOf(3, 1, 4, 1)

    private fun priority(kind: String): List<String> {
        val text = requireNotNull(javaClass.getResourceAsStream("/ai-engine/priority.json")).reader().use { it.readText() }
        return JsonParser.parseString(text).asJsonObject.getAsJsonArray(kind).map { it.asJsonObject.get("id").asString }
    }

    private fun sweepScenarios(): List<RefScenario> {
        val baseline = javaClass.getResourceAsStream("/ai-engine/sweep-baseline.txt")?.reader()
            ?.use { r -> r.readLines().filter { it.isNotBlank() }.toSet() } ?: emptySet()
        val out = ArrayList<RefScenario>()
        for (id in priority("moves")) if ("기술:$id" in baseline) {
            val attacker = RefSet("Mew", listOf(id, "tackle"))
            val turns = listOf("move 1" to "move 1", "move 1" to "move 1", "move 1" to "move 2", "move 2" to "move 1")
            out += RefScenario("move-$id", listOf(attacker, RefSet("Chansey", listOf("tackle"))),
                listOf(RefSet("Snorlax", listOf("tackle", "bodyslam")), RefSet("Blissey", listOf("tackle", "icebeam"))), turns,
                seed = seed, snapshots = true)
        }
        val moves = listOf("tackle", "ember", "thunderwave", "earthquake")
        val foeMoves = listOf("tackle", "flamethrower", "icebeam", "willowisp")
        val turns = listOf("move 1" to "move 1", "move 2" to "move 2", "move 3" to "move 3", "move 4" to "move 1", "move 1" to "move 2")
        for (id in priority("abilities")) if ("특성:$id" in baseline) {
            out += RefScenario("ability-$id", listOf(RefSet("Mew", moves, ability = id), RefSet("Chansey", listOf("tackle"))),
                listOf(RefSet("Snorlax", foeMoves), RefSet("Blissey", listOf("tackle"))), turns, seed = seed, snapshots = true)
        }
        for (id in priority("items")) if ("도구:$id" in baseline) {
            val item = dex.item(id)
            @Suppress("UNCHECKED_CAST")
            val mega = (item.data("megaStone") as? Map<String, Any?>)?.entries?.firstOrNull { (base, forme) ->
                dex.species(base) != null && dex.species(forme as String) != null
            }
            val first = if (mega != null) "move 1 mega" else "move 1"
            out += RefScenario("item-$id", listOf(RefSet(mega?.key ?: "Mew", moves, item = id), RefSet("Chansey", listOf("tackle"))),
                listOf(RefSet("Snorlax", foeMoves), RefSet("Blissey", listOf("tackle"))),
                listOf(first to "move 1") + turns.drop(1), seed = seed, snapshots = true)
        }
        return out
    }

    private fun scripted(): List<RefScenario> {
        val seeds = listOf(intArrayOf(3, 1, 4, 1), intArrayOf(27, 18, 28, 18), intArrayOf(5, 9, 2, 6))
        val base = listOf(
            RefScenario("substitute-seed-taunt",
                listOf(RefSet("Venusaur", listOf("leechseed", "substitute", "gigadrain", "sleeppowder"), "Chlorophyll", "Leftovers"),
                    RefSet("Rotom-Wash", listOf("hydropump", "voltswitch", "willowisp", "protect"), "Levitate", "Leftovers")),
                listOf(RefSet("Gyarados", listOf("waterfall", "dragondance", "icefang", "taunt"), "Intimidate", "Sitrus Berry"),
                    RefSet("Kingambit", listOf("kowtowcleave", "suckerpunch", "ironhead", "swordsdance"), "Defiant", "Black Glasses")),
                listOf("move 2" to "move 2", "move 1" to "move 4", "move 3" to "move 1", "switch 2" to "move 3",
                    "move 2" to "switch 2", "move 1" to "move 1", "move 3" to "move 2", "move 1" to "move 1")),
            RefScenario("encore-toxic-spikes",
                listOf(RefSet("Clefable", listOf("moonblast", "encore", "softboiled", "thunderwave"), "Magic Guard", "Leftovers"),
                    RefSet("Ferrothorn", listOf("spikes", "leechseed", "powerwhip", "stealthrock"), "Iron Barbs", "Rocky Helmet")),
                listOf(RefSet("Toxapex", listOf("toxic", "recover", "scald", "toxicspikes"), "Regenerator", "Black Sludge"),
                    RefSet("Dragonite", listOf("dragondance", "extremespeed", "earthquake", "roost"), "Multiscale", "Heavy-Duty Boots")),
                listOf("move 1" to "move 4", "move 2" to "move 1", "switch 2" to "move 3", "move 1" to "switch 2",
                    "move 4" to "move 1", "move 2" to "move 2", "switch 2" to "move 3", "move 3" to "move 4")),
            RefScenario("pivot-and-faints",
                listOf(RefSet("Scizor", listOf("uturn", "bulletpunch", "swordsdance", "knockoff"), "Technician", "Choice Band"),
                    RefSet("Garchomp", listOf("earthquake", "dragonclaw", "stoneedge", "swordsdance"), "Rough Skin", "Life Orb"),
                    RefSet("Blissey", listOf("seismictoss", "softboiled", "toxic", "teleport"), "Natural Cure", "Heavy-Duty Boots")),
                listOf(RefSet("Weavile", listOf("tripleaxel", "knockoff", "iceshard", "swordsdance"), "Pressure", "Focus Sash"),
                    RefSet("Corviknight", listOf("bravebird", "roost", "uturn", "defog"), "Pressure", "Leftovers"),
                    RefSet("Heatran", listOf("magmastorm", "earthpower", "flashcannon", "taunt"), "Flash Fire", "Air Balloon")),
                listOf("move 1" to "move 1", "switch 3" to "move 2", "move 1" to "move 3", "move 2" to "move 1",
                    "move 1" to "move 2", "move 3" to "move 1", "move 1" to "move 1", "move 2" to "move 3", "move 1" to "move 1")),
            RefScenario("mega-tera-weather",
                listOf(RefSet("Charizard", listOf("flamethrower", "solarbeam", "airslash", "roost"), "Blaze", "Charizardite Y", teraType = "Fire"),
                    RefSet("Tyranitar", listOf("stoneedge", "crunch", "earthquake", "dragondance"), "Sand Stream", "Leftovers", teraType = "Rock")),
                listOf(RefSet("Pelipper", listOf("hurricane", "scald", "uturn", "roost"), "Drizzle", "Damp Rock"),
                    RefSet("Barraskewda", listOf("liquidation", "closecombat", "aquajet", "flipturn"), "Swift Swim", "Choice Band", teraType = "Water")),
                listOf("move 1 mega" to "move 1", "move 2" to "move 3", "move 3" to "move 1", "switch 2" to "move 2",
                    "move 1 terastallize" to "move 1", "move 3" to "move 4", "move 2" to "move 1", "move 1" to "move 1")),
            RefScenario("doubles-spread",
                listOf(RefSet("Incineroar", listOf("fakeout", "flareblitz", "partingshot", "knockoff"), "Intimidate", "Sitrus Berry"),
                    RefSet("Rillaboom", listOf("grassyglide", "fakeout", "woodhammer", "uturn"), "Grassy Surge", "Miracle Seed"),
                    RefSet("Amoonguss", listOf("spore", "ragepowder", "pollenpuff", "protect"), "Regenerator", "Rocky Helmet")),
                listOf(RefSet("Flutter Mane", listOf("moonblast", "shadowball", "protect", "icywind"), "Protosynthesis", "Booster Energy"),
                    RefSet("Landorus-Therian", listOf("earthquake", "rockslide", "uturn", "protect"), "Intimidate", "Choice Scarf"),
                    RefSet("Gholdengo", listOf("makeitrain", "shadowball", "nastyplot", "protect"), "Good as Gold", "Leftovers")),
                listOf("move 1 1, move 1 2" to "move 1 1, move 1", "move 2 1, move 3 1" to "move 2 2, move 2",
                    "move 4 2, move 1" to "move 4, move 3 1", "move 2 2, move 4 1" to "move 1 1, move 1",
                    "move 1, move 3 1" to "move 2 1, move 1", "move 2 1, move 1 2" to "move 1 2, move 2"),
                gameType = "doubles"),
        )
        return base.flatMap { s -> seeds.map { s.withSeed(it).copy(snapshots = true) } }
    }

    @Test
    fun `the engine plays on from Showdown's serialized battle exactly as Showdown does`() {
        EngineReferee.assumeAvailable()
        val scenarios = scripted() + sweepScenarios()
        val failures = ArrayList<String>()
        val unknown = sortedSetOf<String>()
        var resumed = 0
        for (chunk in scenarios.chunked(150)) {
            val reference = EngineReferee.showdown(chunk)
            for (scenario in chunk) {
                val showdown = reference.getValue(scenario.id)
                for (step in showdown.snapshots.indices) {
                    val (expected, actual) = EngineReferee.resume(scenario, showdown, step)
                    resumed++
                    unknown += actual.rejections
                    EngineReferee.difference(expected, actual.copyWithoutUnknown())?.let { failures += "step $step: $it" }
                }
            }
        }
        val reports = Path.of(System.getProperty("aiengine.coverage") ?: "build/reports/x").parent
        Files.createDirectories(reports)
        Files.writeString(reports.resolve("showdown-state-resume.md"), buildString {
            appendLine("# Resumed ${resumed} positions from ${scenarios.size} battles: ${failures.size} differ")
            appendLine()
            appendLine("## Serialized properties the engine does not have")
            unknown.forEach { appendLine("- $it") }
            appendLine()
            failures.forEach { appendLine(it.lines().take(30).joinToString("\n")); appendLine() }
        })
        assertTrue(failures.isEmpty()) { "${failures.size} of $resumed resumed positions differ; first:\n${failures.first()}" }
    }

    private fun RefResult.copyWithoutUnknown() = RefResult(id, log, error, ended, turn, missingHooks, emptyList(), states)
}
