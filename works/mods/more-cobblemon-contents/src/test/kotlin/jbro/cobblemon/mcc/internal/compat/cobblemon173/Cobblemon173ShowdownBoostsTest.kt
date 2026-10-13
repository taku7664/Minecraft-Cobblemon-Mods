package jbro.cobblemon.mcc.internal.compat.cobblemon173

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownBranchEngine
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test

/** Stat stages read out of the dev server's own Showdown, including a reset the public log reports silently. */
class Cobblemon173ShowdownBoostsTest {
    private val showdownRoot: Path? = System.getProperty("aiengine.showdown")?.let { Path.of(it) }?.takeIf { Files.isDirectory(it) }

    private val sneasler = UUID.fromString("00000000-0000-4000-8000-000000000011")
    private val gyarados = UUID.fromString("00000000-0000-4000-8000-000000000021")

    private fun set(species: String, moves: List<String>, ability: String, item: String, uuid: UUID) = JsonObject().apply {
        addProperty("name", species)
        addProperty("species", species)
        addProperty("ability", ability)
        addProperty("item", item)
        addProperty("nature", "Serious")
        addProperty("gender", "M")
        addProperty("level", 50)
        addProperty("uuid", uuid.toString())
        add("moves", JsonArray().also { a -> moves.forEach { a.add(it) } })
        add("movesInfo", JsonArray().also { a -> moves.forEach { _ -> a.add(JsonObject().apply { addProperty("pp", 16); addProperty("maxPp", 16) }) } })
        add("evs", JsonObject().apply { listOf("hp", "atk", "def", "spa", "spd", "spe").forEach { addProperty(it, 0) } })
        add("ivs", JsonObject().apply { listOf("hp", "atk", "def", "spa", "spd", "spe").forEach { addProperty(it, 31) } })
    }

    @Test
    fun `stages follow Showdown through a silent White Herb reset and later boosts`() {
        Assumptions.assumeTrue(showdownRoot != null, "No dev server Showdown")
        NativeShowdownBranchEngine.open(showdownRoot!!).use { engine ->
            val context = NativeShowdownBranchEngine::class.java.getDeclaredField("context").let {
                it.isAccessible = true
                it.get(engine) as Context
            }
            val p1 = JsonArray().apply { add(set("Sneasler", listOf("swordsdance", "closecombat"), "Unburden", "White Herb", sneasler)) }
            val p2 = JsonArray().apply { add(set("Gyarados", listOf("dragondance", "waterfall"), "Intimidate", "Sitrus Berry", gyarados)) }
            val battleId = "11111111-2222-4333-8444-666666666666"
            val messages = listOf(
                """>start {"formatid":"cobblemonsingles","seed":[1,2,3,4]}""",
                """>player p1 {"name":"p1","team":$p1}""",
                """>player p2 {"name":"p2","team":$p2}""",
            )
            context.eval("js", "(function(id, messages) { startBattle({ sendFromShowdown() {}, log(m) { throw new Error(m); } }, id, messages); })")
                .execute(battleId, org.graalvm.polyglot.proxy.ProxyArray.fromList(messages.map { it as Any }))
            val log = context.eval("js", "(function(id) { return battleMap.get(id).battle.log.join('\\n'); })").execute(battleId).asString()
            assertTrue(log.lines().any { it.startsWith("|-clearnegativeboost|") && it.endsWith("[silent]") }, "White Herb did not reset Intimidate silently:\n$log")

            val afterLead = Cobblemon173ShowdownBoosts.readFrom(context, battleId)?.let(Cobblemon173ShowdownBoosts::parse)
            assertEquals(mapOf(sneasler to emptyMap<String, Int>(), gyarados to emptyMap()), afterLead)

            context.eval("js", "(function(id) { battleMap.get(id).write('>p1 move 1'); battleMap.get(id).write('>p2 move 1'); })")
                .execute(battleId)
            val afterTurn = Cobblemon173ShowdownBoosts.readFrom(context, battleId)?.let(Cobblemon173ShowdownBoosts::parse)
            assertEquals(mapOf(sneasler to mapOf("atk" to 2), gyarados to mapOf("atk" to 1, "spe" to 1)), afterTurn)

            assertNull(Cobblemon173ShowdownBoosts.readFrom(context, "no-such-battle"))
        }
    }
}
