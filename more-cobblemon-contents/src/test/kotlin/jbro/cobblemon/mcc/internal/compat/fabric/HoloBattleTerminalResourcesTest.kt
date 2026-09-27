package jbro.cobblemon.mcc.internal.compat.fabric

import com.google.gson.JsonParser
import java.io.InputStreamReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HoloBattleTerminalResourcesTest {
    @Test
    fun `terminal uses stable registry identifiers`() {
        assertEquals("more_cobblemon_contents:holo_battle_terminal", HoloBattleTerminalIds.id.toString())
    }

    @Test
    fun `terminal has procedural renderer support resources without custom textures`() {
        val blockState = json("/assets/more_cobblemon_contents/blockstates/holo_battle_terminal.json")
        val blockModel = json("/assets/more_cobblemon_contents/models/block/holo_battle_terminal.json")
        val itemModel = json("/assets/more_cobblemon_contents/models/item/holo_battle_terminal.json")
        val loot = json("/data/more_cobblemon_contents/loot_table/blocks/holo_battle_terminal.json")

        assertTrue(blockState.has("variants"))
        assertEquals("minecraft:block/cyan_concrete", blockModel.getAsJsonObject("textures")["particle"].asString)
        assertEquals("builtin/entity", itemModel["parent"].asString)
        assertEquals("minecraft:block", loot["type"].asString)
        assertNotNull(language("en_us")["block.more_cobblemon_contents.holo_battle_terminal"])
        assertNotNull(language("ko_kr")["block.more_cobblemon_contents.holo_battle_terminal"])
    }

    private fun json(path: String) = javaClass.getResourceAsStream(path).let { stream ->
        assertNotNull(stream, "Missing resource: $path")
        stream!!.use { JsonParser.parseReader(InputStreamReader(it)).asJsonObject }
    }

    private fun language(code: String) = json("/assets/more_cobblemon_contents/lang/$code.json")
}
