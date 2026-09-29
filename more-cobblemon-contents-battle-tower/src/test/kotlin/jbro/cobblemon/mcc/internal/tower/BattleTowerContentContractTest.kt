package jbro.cobblemon.mcc.internal.tower

import java.nio.file.Files
import java.nio.file.Path
import jbro.cobblemon.mcc.internal.command.AiTestCommands
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BattleTowerContentContractTest {
    @Test
    fun `tower streak admin command keeps its argument tree`() {
        val root = TowerProgressCommands.build().build()

        assertEquals("tower", root.name)
        val towerStreak = root.getChild("streak")
        assertEquals(setOf("get", "set", "reset"), towerStreak.children.map { it.name }.toSet())
        val towerGet = towerStreak.getChild("get")
        assertEquals(setOf("player"), towerGet.children.map { it.name }.toSet())
        assertEquals(setOf("format"), towerGet.getChild("player").children.map { it.name }.toSet())
        val towerSet = towerStreak.getChild("set")
        assertEquals(setOf("player"), towerSet.children.map { it.name }.toSet())
        assertEquals(
            setOf("format"),
            towerSet.getChild("player").children.map { it.name }.toSet(),
        )
        assertEquals(
            setOf("value"),
            towerSet.getChild("player").getChild("format").children.map { it.name }.toSet(),
        )
        val towerReset = towerStreak.getChild("reset")
        assertEquals(setOf("player"), towerReset.children.map { it.name }.toSet())
        assertEquals(
            setOf("scope"),
            towerReset.getChild("player").getChild("format").children.map { it.name }.toSet(),
        )

        assertNotSame(root.requirement, towerSet.requirement)
    }

    @Test
    fun `ai test command lists every difficulty for operators`() {
        val aiTest = AiTestCommands.build().build()

        assertEquals("test", aiTest.name)
        assertEquals(
            setOf("ai-입문", "ai-표준", "ai-상급", "ai-보스"),
            aiTest.children.map { it.name }.toSet(),
        )
        assertEquals(AiTestCommands.ADMIN_PERMISSION_LEVEL, 2)

    }

    @Test
    fun `tower and ai test battles and catalog cleanup go through the core`() {
        listOf(
            "internal/compat/cobblemon173/Cobblemon173TowerPveBattleRuntime.kt",
            "internal/compat/cobblemon173/Cobblemon173AiTestBattleRuntime.kt",
        ).forEach { path ->
            val source = source(path)
            assertTrue(source.contains("Cobblemon173ManagedAiBattleEngine("), path)
            assertTrue(source.contains("engine.start("), path)
        }
        assertTrue(
            source("internal/compat/fabric/TowerOpponentCatalogResources.kt")
                .contains("ManagedServerEphemeralStateCleanup.register(store::clear)"),
        )
    }

    private fun source(path: String): String = Files.readString(Path.of("src/main/kotlin/jbro/cobblemon/mcc", path))
}
