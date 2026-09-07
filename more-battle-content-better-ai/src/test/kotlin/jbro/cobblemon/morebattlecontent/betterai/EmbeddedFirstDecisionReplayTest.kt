package jbro.cobblemon.morebattlecontent.betterai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EmbeddedFirstDecisionReplayTest {
    @Test
    fun `depth probe changes only requested plies not boss tier or other profile fields`() {
        val boss = EmbeddedPolicyComparison.profileForSkill(5)
        assertEquals(boss, EmbeddedFirstDecisionReplay.replayProfile(5, null))
        assertEquals(boss.copy(difficulty = boss.difficulty.copy(lookaheadPlies = 2)),
            EmbeddedFirstDecisionReplay.replayProfile(5, 2))
        for (depth in listOf(0, 5)) assertThrows(IllegalArgumentException::class.java) {
            EmbeddedFirstDecisionReplay.replayProfile(5, depth)
        }
    }

    @Test
    fun `repetitions are explicit bounded and default to one`() {
        assertEquals(1, EmbeddedFirstDecisionReplay.repetitionCount(null))
        assertEquals(5, EmbeddedFirstDecisionReplay.repetitionCount("5"))
        assertEquals(20, EmbeddedFirstDecisionReplay.repetitionCount("20"))
        for (invalid in listOf("0", "-1", "21", "bad")) {
            assertThrows(IllegalArgumentException::class.java) {
                EmbeddedFirstDecisionReplay.repetitionCount(invalid)
            }
        }
    }

    @Test
    fun `explicit snapshot selection retains forced request and validates index`() {
        val first = """{"side":"p2","turn":1,"input":{"request":{}},"actionId":"move 1"}"""
        val forced = """{"side":"p2","turn":1,"input":{"request":{"forceSwitch":[true]}},"actionId":"switch 3"}"""
        assertEquals("switch 3", EmbeddedFirstDecisionReplay.snapshotRequest(sequenceOf(first, forced), "p2", 1)["actionId"].asString)
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddedFirstDecisionReplay.snapshotRequest(sequenceOf(first), "p2", -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddedFirstDecisionReplay.snapshotRequest(sequenceOf(first), "other", 0)
        }
        assertThrows(NoSuchElementException::class.java) {
            EmbeddedFirstDecisionReplay.snapshotRequest(sequenceOf(first), "p2", 1)
        }
    }

    @Test
    fun `hypothesis replay arms use exactly the paired battle tunings`() {
        for (name in listOf("CURRENT_PUBLIC_MOVE_HYPOTHESES", "CURRENT_PUBLIC_MOVE_HYPOTHESES_CAP3",
            "CURRENT_PUBLIC_MOVE_HYPOTHESES_CAP3_PRIORITY")) {
            assertEquals(EmbeddedPolicyComparison.tuning(name), EmbeddedFirstDecisionReplay.tuningFor(name))
        }
    }

    @Test
    fun `no coverage floor replay arm preserves all other tuning fields`() {
        val current = EmbeddedFirstDecisionReplay.tuningFor("CURRENT")
        assertEquals(current.copy(id = "current_no_coverage_floor", lookaheadCoverageFloor = 0.0),
            EmbeddedFirstDecisionReplay.tuningFor("CURRENT_NO_COVERAGE_FLOOR"))
        assertEquals(0.35, current.lookaheadCoverageFloor)
    }

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
