package jbro.cobblemon.mcc.betterai.engine

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import jbro.cobblemon.mcc.betterai.simulation.EngineBranchWorker
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleOpeningState
import jbro.cobblemon.mcc.betterai.simulation.NativePokemonOpeningState
import jbro.cobblemon.mcc.betterai.simulation.NativePokemonSet
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownBranchEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test

/**
 * The engine-backed branch worker against the Showdown one the native search used before: the same battle
 * definition and choices must give the same frames (everything except the snapshot token and the log).
 */
class EngineBranchWorkerTest {
    private val showdownRoot: Path? = System.getProperty("aiengine.showdown")?.let { Path.of(it) }?.takeIf { Files.isDirectory(it) }

    private fun uuid(n: Int) = "00000000-0000-4000-8000-%012d".format(n)

    private val definition = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(11, 22, 33, 44),
        p1Team = listOf(
            NativePokemonSet("Garchomp", "Garchomp", listOf("earthquake", "dragonclaw", "stoneedge", "swordsdance"), "Rough Skin", uuid(1), item = "Life Orb", nature = "Jolly"),
            NativePokemonSet("Rotom-Wash", "Rotom-Wash", listOf("hydropump", "voltswitch", "willowisp", "protect"), "Levitate", uuid(2), item = "Leftovers"),
        ),
        p2Team = listOf(
            NativePokemonSet("Gyarados", "Gyarados", listOf("waterfall", "dragondance", "icefang", "taunt"), "Intimidate", uuid(3), item = "Sitrus Berry"),
            NativePokemonSet("Kingambit", "Kingambit", listOf("kowtowcleave", "suckerpunch", "ironhead", "swordsdance"), "Defiant", uuid(4), item = "Black Glasses"),
        ),
    )

    private val lines = listOf(
        listOf("move 1" to "move 2", "move 2" to "move 1", "switch 2" to "move 3"),
        listOf("move 4" to "switch 2", "move 1" to "move 2", "move 3" to "move 1"),
        listOf("switch 2" to "move 4", "move 2" to "move 1", "move 1" to "move 1"),
    )

    private fun comparable(frame: NativeBattleFrame): String {
        val json = com.google.gson.Gson().toJsonTree(frame.copy(snapshotJson = "", log = emptyList())).asJsonObject
        // Requests are compared on the fields the search reads.
        for (key in listOf("p1RequestJson", "p2RequestJson")) {
            val request = JsonParser.parseString(frame.javaClass.getMethod("get${key.replaceFirstChar { it.uppercase() }}").invoke(frame) as String)
            json.remove(key)
            json.add(key, trimRequest(request))
        }
        return json.toString()
    }

    private fun trimRequest(request: com.google.gson.JsonElement): com.google.gson.JsonElement {
        if (!request.isJsonObject) return request
        val out = com.google.gson.JsonObject()
        val o = request.asJsonObject
        for (key in listOf("wait", "teamPreview", "forceSwitch")) o.get(key)?.let { out.add(key, it) }
        o.getAsJsonArray("active")?.let { active ->
            out.add("active", com.google.gson.JsonArray().also { a ->
                active.forEach { slot ->
                    if (!slot.isJsonObject) { a.add(slot); return@forEach }
                    val s = slot.asJsonObject
                    a.add(com.google.gson.JsonObject().apply {
                        add("moves", com.google.gson.JsonArray().also { m ->
                            s.getAsJsonArray("moves").forEach { mv ->
                                val move = mv.asJsonObject
                                m.add(com.google.gson.JsonObject().apply {
                                    add("id", move.get("id"))
                                    add("target", move.get("target"))
                                    addProperty("disabled", move.get("disabled")?.let { it.isJsonPrimitive && (it.asJsonPrimitive.isBoolean && it.asBoolean || it.asJsonPrimitive.isString) } ?: false)
                                })
                            }
                        })
                        for (flag in listOf("trapped", "canMegaEvo", "canDynamax", "canTerastallize")) s.get(flag)?.let { add(flag, it) }
                    })
                }
            })
        }
        return out
    }

    /** The scripted choice when Showdown's request allows it; otherwise a forced switch, a pass, or the first open move. */
    private fun legal(requestJson: String, scripted: String, team: List<jbro.cobblemon.mcc.betterai.simulation.NativePokemonFrame>): String {
        val request = JsonParser.parseString(requestJson)
        if (!request.isJsonObject) return "pass"
        val o = request.asJsonObject
        if (o.has("wait")) return "pass"
        val bench = team.indexOfFirst { it.activeSlot == null && it.hp > 0 }
        if (o.has("forceSwitch")) return if (bench >= 0) "switch ${bench + 1}" else "pass"
        val active = o.getAsJsonArray("active")[0].asJsonObject
        val moves = active.getAsJsonArray("moves")
        if (scripted.startsWith("switch")) {
            val index = scripted.substringAfter(' ').toInt() - 1
            if (!active.has("trapped") && team.getOrNull(index)?.let { it.activeSlot == null && it.hp > 0 } == true) return scripted
        } else {
            val index = scripted.substringAfter(' ').toInt() - 1
            val move = moves.getOrNull(index)?.asJsonObject
            if (move != null && !(move.get("disabled")?.let { it.isJsonPrimitive && (!it.asJsonPrimitive.isBoolean || it.asBoolean) } ?: false)) return scripted
        }
        val open = moves.indexOfFirst { m -> !(m.asJsonObject.get("disabled")?.let { it.isJsonPrimitive && (!it.asJsonPrimitive.isBoolean || it.asBoolean) } ?: false) }
        return "move ${open + 1}"
    }

    private fun com.google.gson.JsonArray.getOrNull(index: Int) = if (index in 0 until size()) get(index) else null

    private fun assertSameLines(definition: NativeBattleDefinition) {
        Assumptions.assumeTrue(showdownRoot != null, "No dev server Showdown")
        NativeShowdownBranchEngine.open(showdownRoot!!).use { showdown ->
            EngineBranchWorker().use { engine ->
                val sRoot = showdown.createBattle(definition)
                val eRoot = engine.createBattle(definition)
                assertEquals(comparable(sRoot), comparable(eRoot), "root frames differ")
                for ((index, line) in lines.withIndex()) {
                    var s = sRoot
                    var e = eRoot
                    for ((turn, choices) in line.withIndex()) {
                        if (s.ended) break
                        val (p1, p2) = choices
                        val c1 = legal(s.p1RequestJson, p1, s.p1Team)
                        val c2 = legal(s.p2RequestJson, p2, s.p2Team)
                        s = showdown.branch(s.snapshotJson, c1, c2)
                        val before = e
                        e = try {
                            engine.branch(e.snapshotJson, c1, c2)
                        } catch (failure: IllegalArgumentException) {
                            throw AssertionError("line $index turn $turn ($c1 / $c2) failed on the engine; showdown now ${comparable(s)}; " +
                                "engine before ${comparable(before)}", failure)
                        }
                        assertEquals(comparable(s), comparable(e), "line $index turn $turn ($c1 / $c2) differs")
                    }
                }
            }
        }
    }

    @Test
    fun `engine frames match Showdown frames`() = assertSameLines(definition)

    @Test
    fun `engine frames match Showdown frames from a public opening state`() = assertSameLines(definition.copy(
        openingState = NativeBattleOpeningState(listOf(
            NativePokemonOpeningState(uuid(1), 120, 183, "Rough Skin", "Life Orb", "brn"),
            NativePokemonOpeningState(uuid(2), 125, 125, "Levitate", "Leftovers"),
            NativePokemonOpeningState(uuid(3), 90, 170, "Intimidate", "Sitrus Berry", "par"),
            NativePokemonOpeningState(uuid(4), 175, 175, "Defiant", "Black Glasses"),
        )),
    ))

    @Test
    fun `an evicted snapshot is rebuilt by replay`() {
        val engine = EngineBranchWorker(cacheLimit = 1)
        val root = engine.createBattle(definition)
        val first = engine.branch(root.snapshotJson, "move 1", "move 2")
        engine.branch(root.snapshotJson, "move 2", "move 1")
        val again = engine.branch(first.snapshotJson, "move 2", "move 1")
        val fresh = EngineBranchWorker().let { w ->
            val r = w.createBattle(definition)
            w.branch(w.branch(r.snapshotJson, "move 1", "move 2").snapshotJson, "move 2", "move 1")
        }
        assertEquals(comparable(fresh), comparable(again))
    }
}
