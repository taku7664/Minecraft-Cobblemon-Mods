package jbro.cobblemon.mcc.internal.battle.rules

import jbro.cobblemon.mcc.api.rules.MajorBattleMechanic
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class ManagedBattleRuleRegistrationWindowTest {
    private val actorIds = setOf(UUID.randomUUID(), UUID.randomUUID())

    @Test
    fun `matching constructed battle is registered before start returns`() {
        val registry = ManagedBattleRuleRegistry()
        val window = ManagedBattleRuleRegistrationWindow(registry)
        val battleId = UUID.randomUUID()

        window.begin(ManagedBattleContentIds.BATTLE_TOWER, MajorBattleMechanic.MEGA, actorIds)
        assertTrue(window.attachIfPending(battleId, actorIds))
        assertTrue(window.finish(battleId))
        assertEquals(ManagedBattleContentIds.BATTLE_TOWER, registry.contentId(battleId))

        assertTrue(
            registry.recordAccepted(
                battleId,
                actorIds.first(),
                ManagedActionSubmission(mechanics = listOf(ManagedSubmittedMechanic.MEGA)),
            ),
        )
    }

    @Test
    fun `unrelated battle cannot claim pending rules and failed start cleans attached rules`() {
        val registry = ManagedBattleRuleRegistry()
        val window = ManagedBattleRuleRegistrationWindow(registry)
        val battleId = UUID.randomUUID()

        window.begin(ManagedBattleContentIds.BATTLE_FACTORY, MajorBattleMechanic.TERA, actorIds)
        assertFalse(window.attachIfPending(UUID.randomUUID(), setOf(UUID.randomUUID())))
        assertTrue(window.attachIfPending(battleId, actorIds))
        assertFalse(window.finish(null))

        assertNull(
            registry.rejectionReason(
                battleId,
                actorIds.first(),
                ManagedActionSubmission(hasBagItem = true),
            ),
        )
    }

    @Test
    fun `nested starts on one server thread are rejected and finish always clears the window`() {
        val window = ManagedBattleRuleRegistrationWindow(ManagedBattleRuleRegistry())

        window.begin(ManagedBattleContentIds.BATTLE_TOWER, MajorBattleMechanic.DYNAMAX, actorIds)
        assertThrows(IllegalStateException::class.java) {
            window.begin(ManagedBattleContentIds.BATTLE_TOWER, MajorBattleMechanic.MEGA, actorIds)
        }
        assertFalse(window.finish(null))

        window.begin(ManagedBattleContentIds.BATTLE_TOWER, MajorBattleMechanic.MEGA, actorIds)
        assertFalse(window.finish(null))
    }
}
