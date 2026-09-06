package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonActionConstraintView
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EmbeddedPublicActionConstraintsTest {
    private val actor = "p2a: target"
    private fun state(vararg lines: String) = EmbeddedPublicActionConstraints().apply { lines.forEach(::observe) }

    @Test
    fun `encore requires observed move and all explicit restrictions end independently`() {
        val tracker = state("|switch|$actor|Pikachu, L50|100/100", "|-start|$actor|Encore")
        assertNull(tracker.forPokemon(actor).encoreMoveId)
        tracker.observe("|move|$actor|Thunderbolt|p1a: other")
        tracker.observe("|-start|$actor|Encore")
        tracker.observe("|-start|$actor|Taunt")
        tracker.observe("|-mustrecharge|$actor")
        tracker.observe("|-activate|$actor|move: Bind|[of] p1a: other")
        assertEquals(BattlePokemonActionConstraintView(true, "thunderbolt", true, true), tracker.forPokemon(actor))
        tracker.observe("|-end|$actor|Taunt")
        assertFalse(tracker.forPokemon(actor).taunted)
        assertTrue(tracker.forPokemon(actor).trapped)
        tracker.observe("|-end|$actor|Encore")
        tracker.observe("|-end|$actor|move: Bind|[partiallytrapped]")
        tracker.observe("|cant|$actor|recharge")
        assertEquals(BattlePokemonActionConstraintView.empty(), tracker.forPokemon(actor))
    }

    @Test
    fun `switch clears outgoing restrictions and incoming last move without crossing sides`() {
        val tracker = state("|switch|$actor|Pikachu|100/100", "|switch|p1a: other|Pikachu|100/100",
            "|move|$actor|Thunderbolt|p1a: other", "|-start|$actor|Encore", "|-start|p1a: other|Taunt")
        tracker.observe("|drag|p2a: reserve|Pikachu|100/100")
        assertEquals(BattlePokemonActionConstraintView.empty(), tracker.forPokemon(actor))
        assertTrue(tracker.forPokemon("p1: other").taunted)
        tracker.observe("|switch|$actor|Pikachu|100/100")
        tracker.observe("|-start|$actor|Encore")
        assertNull(tracker.forPokemon(actor).encoreMoveId)
    }

    @Test
    fun `unattributed trapping and generic activation do not invent a restriction`() {
        val tracker = state("|switch|$actor|Pikachu|100/100", "|-activate|$actor|move: Bind",
            "|-activate|$actor|ability: Shadow Tag")
        assertFalse(tracker.forPokemon(actor).trapped)
        tracker.observe("|cant|$actor|trapped")
        assertTrue(tracker.forPokemon(actor).trapped)
    }
}
