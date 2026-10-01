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
        assertEquals(setOf("player"), root.getChild("session").children.map { it.name }.toSet())
        assertEquals(setOf("force"), root.getChild("abandon").getChild("player").children.map { it.name }.toSet())
    }

    @Test
    fun `ai test command lists every difficulty for operators`() {
        val aiTest = AiTestCommands.build().build()

        assertEquals("test", aiTest.name)
        assertEquals(
            setOf("ai-입문", "ai-표준", "ai-상급", "ai-보스", "stop"),
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

    @Test
    fun `players cannot reach the tower operator commands`() {
        fun source(level: Int) = net.minecraft.commands.CommandSourceStack(net.minecraft.commands.CommandSource.NULL,
            net.minecraft.world.phys.Vec3.ZERO, net.minecraft.world.phys.Vec2.ZERO, null, level, "test",
            net.minecraft.network.chat.Component.literal("test"), null, null)
        listOf(TowerProgressCommands.build().build(), AiTestCommands.build().build()).forEach { root ->
            org.junit.jupiter.api.Assertions.assertFalse(root.requirement.test(source(0)), root.name)
            org.junit.jupiter.api.Assertions.assertTrue(root.requirement.test(source(2)), root.name)
        }
    }

    @Test
    fun `the Battle Tower terminal cannot be crafted`() {
        // Terminals are placed by operators; a crafting recipe would let anyone set one up.
        org.junit.jupiter.api.Assertions.assertFalse(java.nio.file.Files.exists(java.nio.file.Path.of("src/main/resources/data/more_cobblemon_contents_battle_tower/recipe/battle_tower_terminal.json")))
    }
}
