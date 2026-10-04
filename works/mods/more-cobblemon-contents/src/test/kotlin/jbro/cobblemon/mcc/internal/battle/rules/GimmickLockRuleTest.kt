package jbro.cobblemon.mcc.internal.battle.rules

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GimmickLockRuleTest {
    private val rookie = UUID.randomUUID()
    private val champion = UUID.randomUUID()
    private val locked = { id: UUID -> id == rookie }

    @Test
    fun `a locked player facing wild Pokemon or trainers battles without gimmicks`() {
        assertEquals(setOf(rookie), GimmickLockRule.lockedPlayers(listOf(setOf(rookie), emptySet()), locked))
        assertEquals(emptySet<UUID>(), GimmickLockRule.lockedPlayers(listOf(setOf(champion), emptySet()), locked))
        // Teammates against wild Pokemon: only the one without the title is held back.
        assertEquals(setOf(rookie), GimmickLockRule.lockedPlayers(listOf(setOf(rookie, champion), emptySet()), locked))
    }

    @Test
    fun `battles between players keep every gimmick`() {
        assertEquals(emptySet<UUID>(), GimmickLockRule.lockedPlayers(listOf(setOf(rookie), setOf(champion)), locked))
        assertEquals(emptySet<UUID>(), GimmickLockRule.lockedPlayers(listOf(setOf(rookie), setOf(UUID.randomUUID())), locked))
    }

    @Test
    fun `every gimmick is refused, recognised or not`() {
        ManagedSubmittedMechanic.entries.forEach { mechanic ->
            assertTrue(GimmickLockRule.rejects(ManagedActionSubmission(hasBagItem = false, mechanics = listOf(mechanic))), mechanic.name)
        }
        assertFalse(GimmickLockRule.rejects(ManagedActionSubmission(hasBagItem = true, mechanics = emptyList())))
    }
}
