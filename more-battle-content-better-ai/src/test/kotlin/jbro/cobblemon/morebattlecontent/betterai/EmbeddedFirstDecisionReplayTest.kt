package jbro.cobblemon.morebattlecontent.betterai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EmbeddedFirstDecisionReplayTest {
    @Test
    fun `no KO replay arm changes only the KO weight and identity`() {
        val current = EmbeddedFirstDecisionReplay.tuningFor("CURRENT")
        val probe = EmbeddedFirstDecisionReplay.tuningFor("CURRENT_NO_KO_CREDIT")
        assertEquals(current.copy(id = "current_no_ko_credit", knockoutMaterialScore = 0.0), probe)
        assertEquals(200.0, current.knockoutMaterialScore)
        assertThrows(IllegalArgumentException::class.java) { EmbeddedFirstDecisionReplay.tuningFor("UNKNOWN") }
    }

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
