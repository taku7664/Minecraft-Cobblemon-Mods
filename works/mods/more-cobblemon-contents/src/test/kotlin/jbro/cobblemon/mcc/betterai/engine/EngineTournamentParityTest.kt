package jbro.cobblemon.mcc.betterai.engine

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import jbro.cobblemon.mcc.betterai.engine.sim.Battle
import jbro.cobblemon.mcc.betterai.engine.sim.BattleOptions
import jbro.cobblemon.mcc.betterai.engine.sim.Side
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Whole tournament-style battles: singles 3 vs 3 from the Battle Stadium Singles usage data and doubles
 * 4 vs 4 from the VGC usage data, level 50, with sets drawn from the real share of abilities, items,
 * spreads, Tera Types and moves. The engine plays each battle to the end with random legal choices
 * (moves, targets, Terastallization, switches), then Showdown replays those choices and the protocol
 * logs must match line for line.
 */
class EngineTournamentParityTest {
    private val dex = EngineReferee.dex
    private val fallbacks = HashMap<String, MutableList<String>>()

    private class Usage(val builds: JsonObject, val moves: JsonObject)

    private fun usage(format: String): Usage {
        fun read(dir: String) = JsonParser.parseString(requireNotNull(javaClass.getResourceAsStream(
            "/data/more_cobblemon_contents/$dir/$format-2025-12-1500.json")).reader().use { it.readText() }).asJsonObject.getAsJsonObject("species")
        return Usage(read("opponent_build_usage"), read("opponent_move_usage"))
    }

    private fun weighted(random: Random, table: JsonObject?, exclude: Set<String> = emptySet()): String? {
        val entries = table?.entrySet()?.filter { it.key !in exclude && it.value.asDouble > 0 } ?: return null
        if (entries.isEmpty()) return null
        var roll = random.nextDouble() * entries.sumOf { it.value.asDouble }
        for (e in entries) {
            roll -= e.value.asDouble
            if (roll <= 0) return e.key
        }
        return entries.last().key
    }

    private fun set(random: Random, usage: Usage, speciesId: String): RefSet? {
        val species = dex.species(speciesId) ?: return null
        val build = usage.builds.getAsJsonObject(speciesId) ?: return null
        val moveTable = usage.moves.getAsJsonObject(speciesId) ?: return null
        val moves = ArrayList<String>()
        while (moves.size < 4) {
            val move = weighted(random, moveTable, moves.toSet()) ?: break
            val data = dex.move(move) ?: return null
            if (jbro.cobblemon.mcc.betterai.engine.Js.truthy(data.isZ)) return null
            moves += move
        }
        if (moves.isEmpty()) return null
        val ability = weighted(random, build.getAsJsonObject("abilities"))?.takeIf { dex.abilityOrNull(it) != null } ?: return null
        val item = weighted(random, build.getAsJsonObject("items"))?.takeIf { it != "nothing" }?.takeIf { dex.itemOrNull(it) != null } ?: ""
        val spread = weighted(random, build.getAsJsonObject("spreads"))
        val (nature, evs) = spread?.split(":")?.let { (n, e) ->
            n to RefSet.STATS.zip(e.split("/").map { it.toInt() }).toMap()
        } ?: ("serious" to emptyMap())
        val tera = weighted(random, build.getAsJsonObject("teraTypes"))?.replaceFirstChar { it.uppercase() }
        return RefSet(species.name, moves, ability = ability, item = item, level = 50, nature = nature, evs = evs,
            teraType = tera, gender = species.gender.ifEmpty { "M" })
    }

    private fun team(random: Random, usage: Usage, size: Int): List<RefSet> {
        val pool = usage.builds.keySet().toList()
        val chosen = LinkedHashMap<String, RefSet>()
        var guard = 0
        while (chosen.size < size && guard++ < 500) {
            val id = pool[random.nextInt(pool.size)]
            val baseSpecies = dex.species(id)?.baseSpecies ?: continue
            if (chosen.values.any { dex.species(it.species)?.baseSpecies == baseSpecies }) continue
            set(random, usage, id)?.let { chosen[id] = it }
        }
        return chosen.values.toList()
    }

    /** One random legal choice for a side, from the engine's own request. */
    private fun decide(random: Random, battle: Battle, side: Side): String {
        val request = side.activeRequest ?: return ""
        if (request.wait) return ""
        val used = HashSet<Int>()
        fun bench(): Int? = (side.active.size until side.pokemon.size)
            .filter { !side.pokemon[it].fainted && it !in used }.shuffled(random).firstOrNull()?.also { used += it }
        request.forceSwitch?.let { table ->
            return table.withIndex().joinToString(", ") { (slot, needs) ->
                when {
                    !needs -> "pass"
                    // Revival Blessing asks for a fainted Pokemon to bring back.
                    side.slotConditions[slot]["revivalblessing"] != null ->
                        side.pokemon.indexOfFirst { it.fainted }.takeIf { it >= 0 }?.let { "switch ${it + 1}" } ?: "pass"
                    else -> bench()?.let { "switch ${it + 1}" } ?: "pass"
                }
            }
        }
        var teraUsed = false
        return side.active.mapIndexed { slot, pokemon ->
            val data = request.active?.getOrNull(slot)
            if (pokemon == null || pokemon.fainted || data == null || pokemon.volatiles["commanding"] != null) return@mapIndexed "pass"
            if (!data.trapped && !jbro.cobblemon.mcc.betterai.engine.Js.truthy(pokemon.trapped) && random.nextDouble() < 0.12) {
                bench()?.let { return@mapIndexed "switch ${it + 1}" }
            }
            val options = data.moves.withIndex().filter { !jbro.cobblemon.mcc.betterai.engine.Js.truthy(it.value.disabled) }
            if (options.isEmpty()) return@mapIndexed "move 1"
            val (index, move) = options[random.nextInt(options.size)]
            var choice = "move ${index + 1}"
            if (side.active.size > 1 && battle.actions.targetTypeChoices(move.target)) {
                val locs = listOf(1, 2, -1, -2).filter { loc ->
                    battle.validTargetLoc(loc, pokemon, move.target) && pokemon.getAtLoc(loc)?.let { !it.fainted } == true
                }
                if (locs.isNotEmpty()) choice += " ${locs[random.nextInt(locs.size)]}"
            }
            val locked = pokemon.getLockedMove() != null
            if (!teraUsed && !locked && pokemon.canTerastallize is String && random.nextDouble() < 0.3) {
                choice += " terastallize"
                teraUsed = true
            }
            choice
        }.joinToString(", ")
    }

    /** Plays one battle on the engine with random legal choices and returns it as a replayable scenario. */
    private fun play(id: String, gameType: String, p1: List<RefSet>, p2: List<RefSet>, seed: IntArray, random: Random): RefScenario {
        val battle = Battle(dex, BattleOptions(gameType = gameType, seed = seed, log = false))
        battle.setPlayer("p1", "p1", p1.mapIndexed { i, s -> s.toSet("p1", i) })
        battle.setPlayer("p2", "p2", p2.mapIndexed { i, s -> s.toSet("p2", i) })
        val turns = ArrayList<Pair<String, String>>()
        var steps = 0
        while (!battle.ended && steps++ < 120) {
            val made = battle.sides.map { side ->
                val choice = decide(random, battle, side)
                if (choice.isEmpty()) return@map ""
                val accepted = runCatching { side.choose(choice) }.getOrDefault(false)
                if (accepted) choice else "default".also {
                    fallbacks.getOrPut(id) { ArrayList() } += "turn ${battle.turn} ${side.id}: '$choice' rejected (${side.choice.error}), request=${side.requestState}"
                    side.choose("default")
                }
            }
            turns += made[0] to made[1]
            battle.commitDecisions()
        }
        return RefScenario(id, p1, p2, turns, seed = seed, gameType = gameType)
    }

    private fun tournament(format: String, gameType: String, size: Int, battles: Int, salt: Long): List<String> {
        val usage = usage(format)
        val scenarios = (0 until battles).map { n ->
            val random = Random(salt * 1000 + n)
            val seed = IntArray(4) { random.nextInt(65536) }
            play("$gameType-$n", gameType, team(random, usage, size), team(random, usage, size), seed, random)
        }
        val failing = EngineReferee.compare(scenarios).map { it.substringAfter("scenario ").substringBefore(":").substringBefore("@") }.toSet()
        // Replay the failing battles with the PRNG traced, so each report shows where the rolls part.
        return EngineReferee.compare(scenarios.filter { it.id in failing }.map { it.copy(traceRng = true) }).map { diff ->
            val id = diff.substringAfter("scenario ").substringBefore(":").substringBefore("@")
            (fallbacks[id]?.joinToString("\n", postfix = "\n") ?: "") + diff
        }
    }

    private fun report(name: String, differences: List<String>) {
        val dir = Path.of(System.getProperty("aiengine.coverage") ?: "build/reports/x").parent
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("ai-engine-tournament-$name.md"), differences.joinToString("\n\n"))
    }

    @Test
    fun `singles 3v3 battles from BSS usage replay like Showdown`() {
        EngineReferee.assumeAvailable()
        val differences = tournament("gen9bssregj", "singles", 3, 60, 33)
        report("singles", differences)
        assertTrue(differences.isEmpty()) { "${differences.size} of 60 battles differ:\n" + differences.take(3).joinToString("\n") }
    }

    @Test
    fun `doubles 4v4 battles from VGC usage replay like Showdown`() {
        EngineReferee.assumeAvailable()
        val differences = tournament("gen9vgc2025regj", "doubles", 4, 60, 44)
        report("doubles", differences)
        assertTrue(differences.isEmpty()) { "${differences.size} of 60 battles differ:\n" + differences.take(3).joinToString("\n") }
    }
}
