package jbro.cobblemon.morebattlecontent.internal.battle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManagedBattleLifecycleRegistryTest {
    @Test
    fun `completed battle remains registered until every target is released`() {
        val registry = ManagedBattleLifecycleRegistry<String, String, String>()
        registry.register("player", "battle", listOf("player-copy", "opponent"))

        assertTrue(registry.markEnded("battle"))
        val forced = registry.advanceEnded(
            graceTicks = 20,
            isReleased = { true },
        )

        assertTrue(forced.isEmpty())
        assertEquals(0, registry.size())
    }

    @Test
    fun `completed battle is returned for forced release after grace expires`() {
        val registry = ManagedBattleLifecycleRegistry<String, String, String>()
        registry.register("player", "battle", listOf("opponent"))
        registry.markEnded("battle")

        assertTrue(registry.advanceEnded(2) { false }.isEmpty())
        val forced = registry.advanceEnded(2) { false }

        assertEquals(listOf("battle"), forced.map { it.battleId })
        assertEquals(listOf("opponent"), forced.single().targets)
        assertEquals(0, registry.size())
    }

    @Test
    fun `disconnect claims active and already ended battles for the player`() {
        val registry = ManagedBattleLifecycleRegistry<String, String, String>()
        registry.register("player", "battle", listOf("opponent"))
        registry.markEnded("battle")

        val claimed = registry.takePlayer("player")

        assertEquals(listOf("battle"), claimed.map { it.battleId })
        assertEquals(0, registry.size())
        assertTrue(registry.takePlayer("player").isEmpty())
    }

    @Test
    fun `shutdown atomically claims every managed battle`() {
        val registry = ManagedBattleLifecycleRegistry<String, String, String>()
        registry.register("first", "battle-1", listOf("one"))
        registry.register("second", "battle-2", listOf("two"))

        val claimed = registry.takeAll()

        assertEquals(setOf("battle-1", "battle-2"), claimed.map { it.battleId }.toSet())
        assertEquals(0, registry.size())
    }

    @Test
    fun `duplicate battle is rejected while a new battle may overlap completed cleanup`() {
        val registry = ManagedBattleLifecycleRegistry<String, String, String>()
        registry.register("player", "battle", listOf("opponent"))
        registry.markEnded("battle")

        assertThrows(IllegalArgumentException::class.java) {
            registry.register("other-player", "battle", listOf("other"))
        }
        registry.register("player", "other-battle", listOf("other"))
        assertEquals(2, registry.size())
    }

    @Test
    fun `aborted startup claims only its own registration`() {
        val registry = ManagedBattleLifecycleRegistry<String, String, String>()
        registry.register("player", "battle", listOf("opponent"))

        assertEquals(listOf("opponent"), registry.takeBattle("battle")?.targets)
        assertEquals(null, registry.takeBattle("battle"))
        assertEquals(0, registry.size())
    }
}
