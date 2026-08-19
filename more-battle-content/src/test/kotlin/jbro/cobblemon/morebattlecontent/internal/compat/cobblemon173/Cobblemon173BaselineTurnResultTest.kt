package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Cobblemon173BaselineTurnResultTest {
    @Test
    fun `zero slot wait is a successful no action result`() {
        val result = Cobblemon173BaselineTurnResult.noActionRequired()

        assertEquals(Cobblemon173BaselineTurnStatus.NO_ACTION_REQUIRED, result.status)
        assertTrue(result.responses.isEmpty())
    }
}
