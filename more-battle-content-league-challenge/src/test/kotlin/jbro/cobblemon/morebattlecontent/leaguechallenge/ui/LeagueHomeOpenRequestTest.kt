package jbro.cobblemon.morebattlecontent.leaguechallenge.ui

import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LeagueHomeOpenRequestTest {
    @Test fun `explicit request waits for battle and other screen then opens once`() {
        val queue = LeagueHomeOpenRequest()
        val session = UUID.randomUUID()
        assertFalse(queue.consume(session, 0, true, false, true))
        queue.request(session, 0)
        assertFalse(queue.consume(session, 1, true, true, true))
        assertFalse(queue.consume(session, 2, true, false, false))
        assertTrue(queue.consume(session, 3, true, false, true))
        assertFalse(queue.consume(session, 4, true, false, true))
    }

    @Test fun `dismiss expiry disconnect and replaced session discard pending open`() {
        val session = UUID.randomUUID()
        val queue = LeagueHomeOpenRequest()
        queue.request(session, 0)
        queue.dismiss()
        assertFalse(queue.consume(session, 1, true, false, true))
        queue.request(session, 0)
        assertFalse(queue.consume(session, 201, true, false, true))
        queue.request(session, 0)
        assertFalse(queue.consume(session, 1, false, false, true))
        assertFalse(queue.consume(session, 2, true, false, true))
        queue.request(session, 0)
        assertFalse(queue.consume(UUID.randomUUID(), 1, true, false, true))
        assertFalse(queue.consume(session, 2, true, false, true))
    }
}
