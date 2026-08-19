package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.battles.MoveActionResponse
import jbro.cobblemon.morebattlecontent.internal.tower.rules.TowerActionSubmission
import jbro.cobblemon.morebattlecontent.internal.tower.rules.TowerSubmittedMechanic
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Cobblemon173BattleRuleHooksTest {
    @Test
    fun `cobblemon move responses expose only non-null gimmick ids`() {
        val responses = listOf(
            MoveActionResponse("tackle", null, "mega"),
            MoveActionResponse("protect", null, null),
        )

        assertEquals(
            TowerActionSubmission(hasBagItem = false, mechanics = listOf(TowerSubmittedMechanic.MEGA)),
            Cobblemon173BattleRuleHooks.inspect(responses),
        )
    }

    @Test
    fun `experience is suppressed only while a battle is managed by MBC`() {
        val battleId = UUID.randomUUID()
        val actorIds = setOf(UUID.randomUUID(), UUID.randomUUID())

        assertFalse(Cobblemon173BattleRuleHooks.shouldSuppressExperience(battleId))
        try {
            assertTrue(Cobblemon173BattleRuleHooks.register(battleId, null, actorIds))
            assertTrue(Cobblemon173BattleRuleHooks.shouldSuppressExperience(battleId))
        } finally {
            Cobblemon173BattleRuleHooks.unregister(battleId)
        }
        assertFalse(Cobblemon173BattleRuleHooks.shouldSuppressExperience(battleId))
    }
}
