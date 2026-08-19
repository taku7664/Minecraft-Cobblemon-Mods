package jbro.cobblemon.morebattlecontent.internal.compat.fabric

import com.google.gson.JsonParser
import java.io.InputStreamReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HoloBattleTerminalResourcesTest {
    @Test
    fun `terminal uses stable registry identifiers`() {
        assertEquals("cobblemon_more_battle_content:holo_battle_terminal", HoloBattleTerminalIds.id.toString())
    }

    @Test
    fun `terminal has procedural renderer support resources without custom textures`() {
        val blockState = json("/assets/cobblemon_more_battle_content/blockstates/holo_battle_terminal.json")
        val blockModel = json("/assets/cobblemon_more_battle_content/models/block/holo_battle_terminal.json")
        val itemModel = json("/assets/cobblemon_more_battle_content/models/item/holo_battle_terminal.json")
        val loot = json("/data/cobblemon_more_battle_content/loot_table/blocks/holo_battle_terminal.json")

        assertTrue(blockState.has("variants"))
        assertEquals("minecraft:block/cyan_concrete", blockModel.getAsJsonObject("textures")["particle"].asString)
        assertEquals("builtin/entity", itemModel["parent"].asString)
        assertEquals("minecraft:block", loot["type"].asString)
        assertNotNull(language("en_us")["block.cobblemon_more_battle_content.holo_battle_terminal"])
        assertNotNull(language("ko_kr")["block.cobblemon_more_battle_content.holo_battle_terminal"])
    }

    private fun json(path: String) = javaClass.getResourceAsStream(path).let { stream ->
        assertNotNull(stream, "Missing resource: $path")
        stream!!.use { JsonParser.parseReader(InputStreamReader(it)).asJsonObject }
    }

    private fun language(code: String) = json("/assets/cobblemon_more_battle_content/lang/$code.json")
}
