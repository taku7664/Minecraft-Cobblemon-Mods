package jbro.cobblemon.morebattlecontent.betterai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EmbeddedPublicPressureTest {
    private val actor = "p2a: attacker"
    private val target = "p1a: defender"
    private val pressure = EmbeddedPublicPressure.Participant(target, "pressure")

    @Test
    fun `only public opposing affected Pressure users add cost`() {
        assertEquals(0, EmbeddedPublicPressure.loss(actor, target, "normal", emptyList()))
        assertEquals(1, EmbeddedPublicPressure.loss(actor, target, "normal", listOf(pressure)))
        assertEquals(0, EmbeddedPublicPressure.loss(actor, actor, "self", listOf(pressure)))
        assertEquals(0, EmbeddedPublicPressure.loss(actor, "p2b: partner", "adjacentAlly",
            listOf(EmbeddedPublicPressure.Participant("p2b: partner", "pressure"))))
        val second = EmbeddedPublicPressure.Participant("p1b: second", "pressure")
        assertEquals(2, EmbeddedPublicPressure.loss(actor, null, "allAdjacentFoes", listOf(pressure, second)))
        assertEquals(1, EmbeddedPublicPressure.loss(actor, target, "normal", listOf(pressure, second)))
    }

    @Test
    fun `public suppression and Ability Shield modify Pressure without assuming hidden abilities`() {
        val gas = EmbeddedPublicPressure.Participant("p2b: gas", "neutralizinggas")
        assertEquals(0, EmbeddedPublicPressure.loss(actor, target, "normal", listOf(pressure, gas)))
        assertEquals(1, EmbeddedPublicPressure.loss(actor, target, "normal",
            listOf(pressure.copy(item = "abilityshield"), gas)))
        assertEquals(0, EmbeddedPublicPressure.loss(actor, target, "normal",
            listOf(pressure.copy(suppressed = true))))
        assertEquals(1, EmbeddedPublicPressure.loss(actor, target, "normal",
            listOf(pressure, gas.copy(suppressed = true))))
        assertEquals(0, EmbeddedPublicPressure.loss(actor, target, "normal",
            listOf(pressure.copy(ability = null))))
    }

    @Test
    fun `unannounced preparation target is charged only when every possible target agrees`() {
        assertEquals(1, EmbeddedPublicPressure.loss(actor, null, "normal", listOf(pressure), preparing = true))
        assertEquals(0, EmbeddedPublicPressure.loss(actor, null, "normal",
            listOf(pressure, EmbeddedPublicPressure.Participant("p1b: unknown", null)), preparing = true))
        assertEquals(0, EmbeddedPublicPressure.loss(actor, null, "normal", listOf(pressure)))
    }
}
