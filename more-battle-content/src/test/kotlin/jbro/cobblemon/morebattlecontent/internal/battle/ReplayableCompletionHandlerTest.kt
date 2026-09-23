package jbro.cobblemon.morebattlecontent.internal.battle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReplayableCompletionHandlerTest {
    @Test
    fun `completion that happened before registration is replayed once`() {
        var registered: ((String) -> Unit)? = null
        val delivered = ArrayList<String>()

        attachReplayableCompletionHandler(
            completion = "ended",
            register = { registered = it },
            isComplete = { true },
            handler = delivered::add,
        )
        registered?.invoke("duplicate")

        assertEquals(listOf("ended"), delivered)
    }

    @Test
    fun `completion racing with registration is delivered once`() {
        val delivered = ArrayList<String>()

        attachReplayableCompletionHandler(
            completion = "ended",
            register = { callback -> callback("callback") },
            isComplete = { true },
            handler = delivered::add,
        )

        assertEquals(listOf("callback"), delivered)
    }

    @Test
    fun `live completion remains attached and duplicate callbacks are ignored`() {
        var registered: ((String) -> Unit)? = null
        val delivered = ArrayList<String>()

        attachReplayableCompletionHandler(
            completion = "ended",
            register = { registered = it },
            isComplete = { false },
            handler = delivered::add,
        )
        registered?.invoke("first")
        registered?.invoke("duplicate")

        assertEquals(listOf("first"), delivered)
    }
}
