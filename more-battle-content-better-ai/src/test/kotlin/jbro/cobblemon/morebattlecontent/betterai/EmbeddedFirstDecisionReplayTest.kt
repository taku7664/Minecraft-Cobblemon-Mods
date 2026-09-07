package jbro.cobblemon.morebattlecontent.betterai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EmbeddedFirstDecisionReplayTest {
    @Test
    fun `first request selection preserves side and rejects history dependent frames`() {
        val ordinary = """{"side":"p2","turn":1,"input":{"request":{}},"actionId":"move 1"}"""
        assertEquals("p2", EmbeddedFirstDecisionReplay.firstRequest(sequenceOf(ordinary), "p2")["side"].asString)
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddedFirstDecisionReplay.firstRequest(sequenceOf(ordinary.replace("\"turn\":1", "\"turn\":2")), "p2")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddedFirstDecisionReplay.firstRequest(sequenceOf(ordinary.replace("\"request\":{}", "\"request\":{\"forceSwitch\":[true]}")), "p2")
        }
        assertThrows(IllegalArgumentException::class.java) { EmbeddedFirstDecisionReplay.firstRequest(sequenceOf(ordinary), "other") }
    }
}
