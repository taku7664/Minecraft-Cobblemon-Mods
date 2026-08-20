package jbro.cobblemon.morebattlecontent.internal.pvp

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PvpLoungeRescuePolicyTest {
    @Test
    fun `a player left in the lounge with no way back is pulled out`() {
        assertTrue(
            PvpLoungeRescuePolicy.rescues(
                inLoungeDimension = true,
                hasPendingReturn = false,
            ),
        )
    }

    @Test
    fun `a recorded return point is left to the coordinator`() {
        assertFalse(
            PvpLoungeRescuePolicy.rescues(
                inLoungeDimension = true,
                hasPendingReturn = true,
            ),
        )
    }

    @Test
    fun `players outside the lounge are never touched`() {
        listOf(true, false).forEach { pending ->
            assertFalse(
                PvpLoungeRescuePolicy.rescues(
                    inLoungeDimension = false,
                    hasPendingReturn = pending,
                ),
            )
        }
    }
}
