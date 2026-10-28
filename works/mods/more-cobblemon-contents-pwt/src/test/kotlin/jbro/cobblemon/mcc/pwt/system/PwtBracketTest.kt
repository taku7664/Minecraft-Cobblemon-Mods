package jbro.cobblemon.mcc.pwt.system

import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PwtBracketTest {
    private val roster = (1..17).map { "pwt:trainer_$it" }

    @Test
    fun `draw seats seven distinct trainers and the player`() {
        repeat(50) { seed ->
            val bracket = PwtBracket.draw(roster, Random(seed))
            assertEquals(8, bracket.entrants.distinct().size)
            assertEquals(1, bracket.entrants.count { it == PwtBracket.PLAYER })
            assertTrue(bracket.entrants.filter { it != PwtBracket.PLAYER }.all { it in roster })
        }
    }

    @Test
    fun `draw needs seven trainers`() {
        assertThrows<IllegalArgumentException> { PwtBracket.draw(roster.take(6), Random(1)) }
    }

    @Test
    fun `three wins take the title and settle every other match`() {
        var bracket = PwtBracket.draw(roster, Random(7))
        repeat(3) { round ->
            assertEquals(round, bracket.round)
            val opponent = bracket.opponentSlot()!!
            val alive = bracket.alive(round)
            assertEquals(alive.indexOf(bracket.playerSlot) xor 1, alive.indexOf(opponent))
            bracket = bracket.settle(true, Random(round))
            assertEquals(8 shr (round + 1), bracket.winners.last().size)
            assertTrue(bracket.playerSlot in bracket.winners.last())
        }
        assertTrue(bracket.finished)
        assertTrue(bracket.playerWon)
        assertNull(bracket.opponentSlot())
    }

    @Test
    fun `a loss ends the run but still settles its round`() {
        var bracket = PwtBracket.draw(roster, Random(3)).settle(true, Random(1))
        val opponent = bracket.opponentSlot()!!
        bracket = bracket.settle(false, Random(2))
        assertTrue(bracket.finished)
        assertFalse(bracket.playerWon)
        assertEquals(2, bracket.winners.size)
        assertTrue(opponent in bracket.winners.last())
        assertThrows<IllegalStateException> { bracket.settle(true, Random(4)) }
    }

    @Test
    fun `a winner who never played the match is rejected`() {
        val entrants = listOf(PwtBracket.PLAYER) + (1..7).map { "pwt:t$it" }
        PwtBracket(entrants, listOf(listOf(0, 3, 5, 7)))
        // Slot 4 plays in match 2, not match 1.
        assertThrows<IllegalArgumentException> { PwtBracket(entrants, listOf(listOf(0, 4, 4, 6))) }
        assertThrows<IllegalArgumentException> { PwtBracket(entrants, listOf(listOf(0, 2, 4))) }
    }
}
