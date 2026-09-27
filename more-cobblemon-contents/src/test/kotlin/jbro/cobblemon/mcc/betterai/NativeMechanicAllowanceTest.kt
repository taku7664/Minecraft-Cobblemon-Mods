package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.api.ai.BattleActionCandidate
import jbro.cobblemon.mcc.api.ai.BattleActionKind
import jbro.cobblemon.mcc.api.ai.BattleMechanicCandidate
import jbro.cobblemon.mcc.betterai.simulation.NativeMechanicAllowance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NativeMechanicAllowanceTest {
    @Test
    fun `offered mechanics are canonical and include composite components`() {
        val tera = move("tera", "cobblemon:terastallize")
        val mega = move("mega", "Mega Evolution")
        val composite = BattleActionCandidate(
            actionId = "joint",
            kind = BattleActionKind.COMPOSITE,
            componentActionIds = listOf("mega", "plain"),
            componentActions = listOf(mega, move("plain", null)),
        )

        assertEquals(setOf("mega", "tera"), NativeMechanicAllowance.offered(listOf(tera, composite)))
    }

    @Test
    fun `allowance only grows once a mechanic has been offered`() {
        val afterTeraSpent = listOf(move("plain", null))

        assertEquals(setOf("tera"), NativeMechanicAllowance.merge(setOf("tera"), afterTeraSpent))
        assertEquals(emptySet<String>(), NativeMechanicAllowance.merge(null, afterTeraSpent))
    }

    private fun move(id: String, mechanic: String?) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = 0,
        moveSlot = 0,
        moveId = "tackle",
        mechanic = mechanic?.let { BattleMechanicCandidate(it, null, null) },
    )
}
