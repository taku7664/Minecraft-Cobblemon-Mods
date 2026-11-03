package jbro.cobblemon.mcc.api.access

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContentSwitchPolicyTest {
    private val tower = "more_cobblemon_contents:battle_tower"
    private val pwt = "more_cobblemon_contents:pwt"
    private val player = UUID(0, 1)
    private val paused = ContentAccessDecision.Denied(MccContentSwitches.PAUSED_REASON_KEY, MccContentSwitches.PAUSED_CODE)

    @Test
    fun `an enabled content is allowed without asking any probe`() {
        var asked = false
        val policy = ContentSwitchPolicy<String>({ true })
        policy.registerRunProbe(tower) { _, _ -> asked = true; false }
        assertEquals(ContentAccessDecision.Allowed, policy.check("server", player, tower))
        assertFalse(asked)
    }

    @Test
    fun `a switched-off content turns away a player with no run and lets a run go on`() {
        val runs = mutableSetOf<UUID>()
        val policy = ContentSwitchPolicy<String>({ it != tower })
        policy.registerRunProbe(tower) { server, id -> assertEquals("server", server); id in runs }
        assertEquals(paused, policy.check("server", player, tower))
        runs += player
        assertEquals(ContentAccessDecision.Allowed, policy.check("server", player, tower))
        assertEquals(ContentAccessDecision.Allowed, policy.check("server", player, pwt))
    }

    @Test
    fun `a probe only speaks for its own content`() {
        val policy = ContentSwitchPolicy<String>({ false })
        policy.registerRunProbe(pwt) { _, _ -> true }
        assertEquals(paused, policy.check("server", player, tower))
        assertEquals(ContentAccessDecision.Allowed, policy.check("server", player, pwt))
    }

    @Test
    fun `a failing probe counts as no run and is reported, and a closed probe stops counting`() {
        val reported = mutableListOf<Throwable>()
        val policy = ContentSwitchPolicy<String>({ false }) { reported += it }
        policy.registerRunProbe(tower) { _, _ -> error("broken") }
        assertEquals(paused, policy.check("server", player, tower))
        assertEquals(1, reported.size)

        val handle = policy.registerRunProbe(tower) { _, _ -> true }
        assertEquals(ContentAccessDecision.Allowed, policy.check("server", player, tower))
        handle.close()
        assertEquals(paused, policy.check("server", player, tower))
        assertTrue(reported.size >= 2)
    }
}
