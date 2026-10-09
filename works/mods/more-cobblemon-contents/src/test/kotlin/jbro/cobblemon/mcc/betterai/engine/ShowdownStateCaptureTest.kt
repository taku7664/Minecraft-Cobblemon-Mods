package jbro.cobblemon.mcc.betterai.engine

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import jbro.cobblemon.mcc.betterai.engine.sim.ShowdownStateReader
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownBranchEngine
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173ShowdownStateCapture
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test

/**
 * The live-battle capture against the dev server's own `showdown/index.js`, evaluated as Cobblemon evaluates it:
 * a battle started through its `startBattle` is found in its `battleMap`, serialized, and read by the engine.
 */
class ShowdownStateCaptureTest {
    private val showdownRoot: Path? = System.getProperty("aiengine.showdown")?.let { Path.of(it) }?.takeIf { Files.isDirectory(it) }

    private fun set(species: String, moves: List<String>, ability: String, item: String, uuid: String) = JsonObject().apply {
        addProperty("name", species)
        addProperty("species", species)
        addProperty("ability", ability)
        addProperty("item", item)
        addProperty("nature", "Serious")
        addProperty("gender", "M")
        addProperty("level", 50)
        addProperty("uuid", uuid)
        add("moves", JsonArray().also { a -> moves.forEach { a.add(it) } })
        add("movesInfo", JsonArray().also { a -> moves.forEach { _ -> a.add(JsonObject().apply { addProperty("pp", 16); addProperty("maxPp", 16) }) } })
        add("evs", JsonObject().apply { listOf("hp", "atk", "def", "spa", "spd", "spe").forEach { addProperty(it, 0) } })
        add("ivs", JsonObject().apply { listOf("hp", "atk", "def", "spa", "spd", "spe").forEach { addProperty(it, 31) } })
    }

    @Test
    fun `a battle started through Cobblemon's startBattle is serialized and read by the engine`() {
        Assumptions.assumeTrue(showdownRoot != null, "No dev server Showdown")
        NativeShowdownBranchEngine.open(showdownRoot!!).use { engine ->
            val context = NativeShowdownBranchEngine::class.java.getDeclaredField("context").let {
                it.isAccessible = true
                it.get(engine) as Context
            }
            val p1 = JsonArray().apply {
                add(set("Venusaur", listOf("leechseed", "substitute", "gigadrain", "sleeppowder"), "Chlorophyll", "Leftovers", "00000000-0000-4000-8000-000000000001"))
                add(set("Rotom-Wash", listOf("hydropump", "voltswitch", "willowisp", "protect"), "Levitate", "Leftovers", "00000000-0000-4000-8000-000000000002"))
            }
            val p2 = JsonArray().apply {
                add(set("Gyarados", listOf("waterfall", "dragondance", "icefang", "taunt"), "Intimidate", "Sitrus Berry", "00000000-0000-4000-8000-000000000003"))
                add(set("Kingambit", listOf("kowtowcleave", "suckerpunch", "ironhead", "swordsdance"), "Defiant", "Black Glasses", "00000000-0000-4000-8000-000000000004"))
            }
            val battleId = "11111111-2222-4333-8444-555555555555"
            val messages = listOf(
                """>start {"formatid":"cobblemonsingles","seed":[1,2,3,4]}""",
                """>player p1 {"name":"p1","team":$p1}""",
                """>player p2 {"name":"p2","team":$p2}""",
            )
            context.eval("js", "(function(id, messages) { startBattle({ sendFromShowdown() {}, log(m) { throw new Error(m); } }, id, messages); })")
                .execute(battleId, org.graalvm.polyglot.proxy.ProxyArray.fromList(messages.map { it as Any }))
            context.eval("js", "(function(id) { battleMap.get(id).write('>p1 move 1'); battleMap.get(id).write('>p2 move 4'); })")
                .execute(battleId)

            val json = Cobblemon173ShowdownStateCapture.captureFrom(context, battleId)
            assertTrue(json != null, "the battle was not found in battleMap")
            val battle = ShowdownStateReader.read(EngineReferee.dex, json!!).battle
            assertEquals(2, battle.turn)
            assertEquals("venusaur", battle.sides[0].active[0]!!.species.id)
            assertEquals("gyarados", battle.sides[1].active[0]!!.species.id)
            // Taunt from the first turn is carried over as Showdown keeps it.
            assertTrue("taunt" in battle.sides[0].active[0]!!.volatiles)
            assertNull(Cobblemon173ShowdownStateCapture.captureFrom(context, "no-such-battle"))
        }
    }
}
