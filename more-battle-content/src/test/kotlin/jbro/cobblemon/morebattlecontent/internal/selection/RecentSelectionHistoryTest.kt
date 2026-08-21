package jbro.cobblemon.morebattlecontent.internal.selection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecentSelectionHistoryTest {
    @Test
    fun `keeps a bounded distinct history per owner and forgets disconnected owners`() {
        val history = RecentSelectionHistory<String, String>(capacity = 3)

        history.record("player-a", "one")
        history.record("player-a", "two")
        history.record("player-a", "three")
        history.record("player-a", "four")
        history.record("player-b", "other")

        assertEquals(linkedSetOf("two", "three", "four"), history.recent("player-a"))
        assertEquals(setOf("other"), history.recent("player-b"))

        history.forget("player-a")
        assertTrue(history.recent("player-a").isEmpty())
        assertEquals(setOf("other"), history.recent("player-b"))
    }

    @Test
    fun `recording the same selection again moves it to the newest position`() {
        val history = RecentSelectionHistory<String, String>(capacity = 3)

        history.record("player", "one")
        history.record("player", "two")
        history.record("player", "one")
        history.record("player", "three")
        history.record("player", "four")

        assertEquals(linkedSetOf("one", "three", "four"), history.recent("player"))
    }
}
