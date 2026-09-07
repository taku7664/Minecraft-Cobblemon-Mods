package jbro.cobblemon.morebattlecontent.betterai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EmbeddedPublicPpTest {
    private val actor = "p2a: opponent"

    @Test
    fun `public event replay restores original PP on departure but not disguise replacement`() {
        val lines = listOf("|switch|$actor|Ditto, L50|100/100",
            "|move|$actor|Transform|p1a: own", "|-transform|$actor|p1a: own",
            "|move|$actor|Recover|$actor", "|replace|$actor|Ditto, L50|100/100")
        fun replay() = EmbeddedPublicPp().apply { lines.forEach { observe(it) } }
        val pp = replay()
        assertEquals(4, pp.remaining(actor, "recover", 8))
        assertEquals(4, replay().remaining(actor, "recover", 8))
        pp.observe("|switch|p2a: bench|Pikachu, L50|100/100")
        assertFalse(pp.isTransformed(actor))
        pp.observe("|switch|$actor|Ditto, L50|100/100")
        assertEquals(15, pp.remaining(actor, "transform", 16))
        assertEquals(8, pp.remaining(actor, "recover", 8))
        pp.observe("|-transform|$actor|p1a: own")
        pp.observe("|faint|$actor")
        assertFalse(pp.isTransformed(actor))
        assertEquals(15, pp.remaining(actor, "transform", 16))
    }

    @Test
    fun `Spite and Leppa public messages change only the named pool in event order`() {
        val pp = EmbeddedPublicPp()
        fun observe(line: String) = pp.observe(line, maximumPp = { if (it == "recover") 8 else null })
        observe("|move|$actor|Recover|$actor")
        observe("|-activate|$actor|move: Spite|Recover|4")
        assertEquals(3, pp.remaining(actor, "recover", 8))
        observe("|-activate|$actor|item: Leppa Berry|Recover")
        assertEquals(8, pp.remaining(actor, "recover", 8))
        observe("|move|$actor|Recover|$actor")
        assertEquals(7, pp.remaining(actor, "recover", 8))
        observe("|-activate|$actor|move: Spite|Recover|99")
        assertEquals(7, pp.remaining(actor, "recover", 8))
        assertEquals(8, pp.remaining("p2a: other", "recover", 8))
    }

    @Test
    fun `copied PP is temporary and departure restores original spending exactly once`() {
        val pp = EmbeddedPublicPp()
        pp.lose(actor, "recover", 3)
        pp.beginTransform(actor)
        assertTrue(pp.isTransformed(actor))
        assertEquals(5, pp.remaining(actor, "recover", 8))
        assertEquals(1, pp.remaining(actor, "revivalblessing", 1))
        pp.lose(actor, "recover", 2)
        assertEquals(3, pp.remaining(actor, "recover", 8))
        pp.restore(actor, "recover", 10, 8)
        assertEquals(5, pp.remaining(actor, "recover", 8))
        pp.lose(actor, "recover", 1)
        pp.endTransform(actor)
        assertFalse(pp.isTransformed(actor))
        assertEquals(5, pp.remaining(actor, "recover", 8))
        pp.lose(actor, "recover", 1)
        pp.endTransform(actor)
        assertEquals(4, pp.remaining(actor, "recover", 8))
        pp.beginTransform(actor)
        assertEquals(5, pp.remaining(actor, "recover", 8))
        assertEquals(8, pp.remaining("p2a: other", "recover", 8))
    }

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
