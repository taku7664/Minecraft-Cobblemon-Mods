package jbro.cobblemon.mcc.internal.command

import jbro.cobblemon.mcc.api.reload.MccReloadResult
import net.minecraft.network.chat.contents.TranslatableContents
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MccAdminCommandsTest {
    @Test
    fun `status, record reset and battle commands keep their argument trees`() {
        assertEquals("status", MccAdminCommands.status().build().name)

        val records = MccAdminCommands.records().build()
        val reset = records.getChild("reset").getChild("player")
        assertEquals(setOf("content"), reset.children.map { it.name }.toSet())
        assertEquals(setOf("format"), reset.getChild("content").children.map { it.name }.toSet())

        val battle = MccAdminCommands.battle().build()
        assertEquals(setOf("list", "end", "pending"), battle.children.map { it.name }.toSet())
        assertEquals(setOf("forfeit", "void"), battle.getChild("end").getChild("player").children.map { it.name }.toSet())
        assertEquals(setOf("list", "retry", "drop"), battle.getChild("pending").children.map { it.name }.toSet())
        listOf("list", "retry", "drop").forEach { action ->
            assertEquals(setOf("player"), battle.getChild("pending").getChild(action).children.map { it.name }.toSet(), action)
        }
        assertEquals("reload", MccAdminCommands.reload().build().name)
    }

    @Test
    fun `the reload reply counts the applied files and gives each file its line`() {
        val lines = MccAdminCommands.reloadLines(listOf(
            MccReloadResult.Applied("hub_tabs.json"),
            MccReloadResult.Applied("content_switches.json", listOf("missing entries were added with their defaults")),
            MccReloadResult.Kept("bp-shop.json", "entries[0]: bad price"),
        ))
        val prefix = "command.more_cobblemon_contents.admin.reload."
        assertEquals(listOf("header", "applied", "applied", "note", "kept", "datapacks").map { prefix + it },
            lines.map { (it.contents as TranslatableContents).key })
        assertEquals(listOf(2, 3), (lines.first().contents as TranslatableContents).args.toList())
        assertEquals(listOf("bp-shop.json", "entries[0]: bad price"), (lines[4].contents as TranslatableContents).args.toList())
    }
}
