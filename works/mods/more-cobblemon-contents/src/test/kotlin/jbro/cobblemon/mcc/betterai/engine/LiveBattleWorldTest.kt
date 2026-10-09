package jbro.cobblemon.mcc.betterai.engine

import com.google.gson.JsonParser
import jbro.cobblemon.mcc.betterai.engine.sim.ShowdownStateReader
import jbro.cobblemon.mcc.betterai.simulation.EngineBranchWorker
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.mcc.betterai.simulation.NativePokemonSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A native world laid over a live battle: the AI's seat becomes p1 whichever seat it holds, its own side and the
 * public board stay as Showdown holds them, and the opponent's hidden information is the world's hypothesis.
 */
class LiveBattleWorldTest {
    private fun uuid(n: Int) = "00000000-0000-4000-8000-%012d".format(n)

    private val player = listOf(
        RefSet("Venusaur", listOf("leechseed", "substitute", "gigadrain", "sleeppowder"), "Chlorophyll", "Leftovers", fixedUuid = uuid(1)),
        RefSet("Rotom-Wash", listOf("hydropump", "voltswitch", "willowisp", "protect"), "Levitate", "Leftovers", fixedUuid = uuid(2)),
    )
    private val trainer = listOf(
        RefSet("Gyarados", listOf("waterfall", "dragondance", "icefang", "taunt"), "Intimidate", "Sitrus Berry", fixedUuid = uuid(3)),
        RefSet("Kingambit", listOf("kowtowcleave", "suckerpunch", "ironhead", "swordsdance"), "Defiant", "Black Glasses", fixedUuid = uuid(4)),
    )

    /** The player sits at p1 as in Cobblemon's trainer battles; the AI's trainer is p2. */
    private val scenario = RefScenario("live-world", player, trainer,
        listOf("move 2" to "move 1", "move 1" to "move 4", "move 3" to "move 1"), snapshots = true)

    private fun native(set: RefSet, uuid: String, item: String = set.item, ability: String = set.ability,
                       moves: List<String> = set.moves, evs: Map<String, Int> = mapOf("hp" to 0, "atk" to 0, "def" to 0, "spa" to 0, "spd" to 0, "spe" to 0)) =
        NativePokemonSet(set.species, set.species, moves, ability, uuid, item = item, nature = set.nature, gender = set.gender,
            level = set.level, evs = evs)

    @Test
    fun `the world's hypothesis replaces the opponent's hidden information over the live battle`() {
        val showdown = EngineReferee.showdown(listOf(scenario)).getValue(scenario.id)
        val live = showdown.snapshots[1]
        val liveVenusaur = JsonParser.parseString(live).asJsonObject.getAsJsonArray("sides")[0].asJsonObject
            .getAsJsonArray("pokemon").map { it.asJsonObject }.first { it.getAsJsonObject("set").get("uuid").asString == uuid(1) }
        val liveShare = liveVenusaur.get("hp").asDouble / liveVenusaur.get("maxhp").asDouble
        val ferrothorn = uuid(9)
        val definition = NativeBattleDefinition(
            formatId = "cobblemonsingles", seed = listOf(7, 7, 7, 7),
            p1Team = trainer.mapIndexed { i, s -> native(s, uuid(3 + i)) },
            p2Team = listOf(
                native(player[0], uuid(1), item = "Black Sludge", moves = listOf("leechseed", "substitute", "sludgebomb", "synthesis"),
                    evs = mapOf("hp" to 252, "atk" to 0, "def" to 4, "spa" to 252, "spd" to 0, "spe" to 0)),
                NativePokemonSet("Ferrothorn", "Ferrothorn", listOf("spikes", "leechseed", "powerwhip", "gyroball"), "Iron Barbs", ferrothorn,
                    item = "Rocky Helmet"),
            ),
        )
        EngineBranchWorker().use { worker ->
            val frame = worker.createLiveBattle(live, definition)
            assertEquals("gyarados", frame.p1Active.single().species, "the AI's trainer sits at p1")
            val venusaur = frame.p2Team.first { it.uuid == uuid(1) }
            assertEquals("blacksludge", venusaur.item)
            assertEquals(listOf("leechseed", "substitute", "sludgebomb", "synthesis"), venusaur.moves.map { it.id })
            assertEquals(listOf("leechseed", "substitute", "sludgebomb", "synthesis"), venusaur.sourceSet!!.moves)
            assertTrue(venusaur.maxHp > liveVenusaur.get("maxhp").asInt, "252 HP EVs raise the max HP")
            assertEquals(liveShare, venusaur.hp.toDouble() / venusaur.maxHp, 0.01)
            assertTrue(frame.p2Team.any { it.uuid == ferrothorn }, "the unrevealed slot is the hypothesis'")
            assertFalse(frame.p2Team.any { it.uuid == uuid(2) }, "the real unrevealed Pokemon is gone")
            // The public board: taunt from the first turn stays on Venusaur.
            assertTrue("taunt" in venusaur.volatiles || "substitute" in venusaur.volatiles)
            // The random stream is the world's.
            val token = JsonParser.parseString(frame.snapshotJson).asJsonObject
            assertTrue(token.has("live"))
            val next = worker.branch(frame.snapshotJson, "move 1", "move 3")
            assertEquals(frame.turn + 1, next.turn)
        }
    }

    /**
     * The swapped seat plays on as Showdown did, with each side's choices moved along: a battle reads the same from
     * either chair. Speed ties are broken by list order and can part, so the lines are compared where none was drawn.
     */
    @Test
    fun `a battle read from the other seat plays on as Showdown did`() {
        val scenarios = listOf(scenario.copy(turns = scenario.turns + listOf("move 1" to "move 2", "switch 2" to "move 3", "move 2" to "move 1")))
        val reference = EngineReferee.showdown(scenarios)
        var compared = 0
        for (s in scenarios) {
            val showdown = reference.getValue(s.id)
            for (step in showdown.snapshots.indices) {
                if (step + 1 >= s.turns.size) continue
                val swapped = ShowdownStateReader.swapSides(JsonParser.parseString(showdown.snapshots[step]).asJsonObject)
                val mirrored = s.copy(turns = s.turns.map { (a, b) -> b to a })
                val mirroredShowdown = RefResult(showdown.id, showdown.log.map(::swapText), showdown.error, showdown.ended, showdown.turn,
                    states = showdown.states.map(::swapStates), snapshots = showdown.snapshots.map { swapped.toString() },
                    logLengths = showdown.logLengths)
                val (expected, actual) = EngineReferee.resume(mirrored, mirroredShowdown, step)
                // Cobblemon's per-side PP lines follow the side order, which is all the swap changes about them.
                fun RefResult.withoutPp() = RefResult(id, log.filterNot { it.startsWith("|pp_update|") }, error, ended, turn,
                    missingHooks, rejections, states)
                assertEquals(null, EngineReferee.difference(expected.withoutPp(), actual.withoutPp()), "step $step")
                compared++
            }
        }
        assertTrue(compared > 0)
    }

    private fun swapText(line: String) = line.replace(Regex("p([12])")) { if (it.groupValues[1] == "1") "p2" else "p1" }

    /** A state line lists p1 first; swapped, p2's half comes first. */
    private fun swapStates(line: String): String {
        val rng = line.substringAfter(" rng=")
        val halves = line.substringBefore(" rng=").split(" p2[")
        return swapText("p2[" + halves[1] + " " + halves[0]) + " rng=" + rng
    }
}
