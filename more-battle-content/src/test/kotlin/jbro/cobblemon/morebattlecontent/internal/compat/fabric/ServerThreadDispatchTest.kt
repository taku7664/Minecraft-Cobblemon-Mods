package jbro.cobblemon.morebattlecontent.internal.compat.fabric

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ServerThreadDispatchTest {
    @Test
    fun `disconnect cleanup runs immediately on the server thread`() {
        val calls = mutableListOf<String>()

        dispatchToServerThread(
            isServerThread = true,
            schedule = { calls += "scheduled" },
            action = { calls += "cleanup" },
        )

        assertEquals(listOf("cleanup"), calls)
    }

    @Test
    fun `disconnect cleanup is scheduled when the network callback is off thread`() {
        val calls = mutableListOf<String>()
        var scheduled: (() -> Unit)? = null

        dispatchToServerThread(
            isServerThread = false,
            schedule = { action ->
                calls += "scheduled"
                scheduled = action
            },
            action = { calls += "cleanup" },
        )

        assertEquals(listOf("scheduled"), calls)
        scheduled?.invoke()
        assertEquals(listOf("scheduled", "cleanup"), calls)
    }
}
