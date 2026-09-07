package jbro.cobblemon.morebattlecontent.betterai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EmbeddedPublicPpTest {
    private val actor = "p2a: opponent"

    @Test
    fun `ordinary use and public Pressure cost consume PP Max estimate once`() {
        val pp = EmbeddedPublicPp()
        pp.observeMove("|move|$actor|Thunderbolt|p1a: own", pressureLoss = 1)
        assertEquals(22, pp.remaining(actor, "thunderbolt", 24))
        assertEquals(24, pp.remaining("p2a: other", "thunderbolt", 24))
    }

    @Test
    fun `called moves charge their caller and locked continuations spend nothing`() {
        val pp = EmbeddedPublicPp()
        pp.observeMove("|move|$actor|Sleep Talk|$actor")
        pp.observeMove("|move|$actor|Thunderbolt|p1a: own|[from] move: Sleep Talk", pressureLoss = 1)
        assertEquals(14, pp.remaining(actor, "sleeptalk", 16))
        assertEquals(24, pp.remaining(actor, "thunderbolt", 24))
        pp.observeMove("|move|$actor|Fly||[still]", pressureLoss = 1)
        pp.observeMove("|move|$actor|Fly|p1a: own|[from] lockedmove", pressureLoss = 1)
        assertEquals(22, pp.remaining(actor, "fly", 24))
    }

    @Test
    fun `unmatched caller attribution does not fabricate a previous caller`() {
        val pp = EmbeddedPublicPp()
        pp.observeMove("|move|$actor|Thunderbolt|p1a: own|[from] move: Sleep Talk")
        assertEquals(23, pp.remaining(actor, "thunderbolt", 24))
        assertEquals(16, pp.remaining(actor, "sleeptalk", 16))
    }

    @Test
    fun `public loss and restoration apply in order without banking excess healing`() {
        val pp = EmbeddedPublicPp()
        pp.restore(actor, "recover", 10, 8)
        pp.lose(actor, "recover", 20)
        assertEquals(0, pp.remaining(actor, "recover", 8))
        pp.restore(actor, "recover", 3, 8)
        assertEquals(3, pp.remaining(actor, "recover", 8))
        pp.lose(actor, "recover", 1)
        assertEquals(2, pp.remaining(actor, "recover", 8))
        assertThrows(IllegalArgumentException::class.java) { pp.lose(actor, "recover", -1) }
    }
}
