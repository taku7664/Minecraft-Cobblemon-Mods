package jbro.cobblemon.policy.support

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class InquiriesTest {
    @Test
    fun `reasons are one line of at most 100 characters`() {
        assertEquals("버그 신고 합니다", Inquiries.clean("  버그\n신고   합니다 "))
        assertEquals(Inquiries.Outcome.Empty, Inquiries.check("", 0, null))
        assertNull(Inquiries.check("가".repeat(100), 0, null))
        assertEquals(Inquiries.Outcome.TooLong, Inquiries.check("가".repeat(101), 0, null))
    }

    @Test
    fun `one inquiry every five minutes`() {
        val wait = Inquiries.check("질문", 60_000, 0) as Inquiries.Outcome.Cooldown
        assertEquals(4, wait.minutes)
        assertEquals(1, (Inquiries.check("질문", Inquiries.COOLDOWN_MILLIS - 1, 0) as Inquiries.Outcome.Cooldown).minutes)
        assertNull(Inquiries.check("질문", Inquiries.COOLDOWN_MILLIS, 0))
    }

    @Test
    fun `the card's title names the player`() {
        assertEquals("[빡켓몬 문의] 김빡주 (Park_JH)", Inquiries.subject("김빡주", "Park_JH"))
    }
}
