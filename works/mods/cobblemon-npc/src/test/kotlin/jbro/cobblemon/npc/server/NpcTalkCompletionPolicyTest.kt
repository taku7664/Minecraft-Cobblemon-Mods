package jbro.cobblemon.npc.server

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NpcTalkCompletionPolicyTest {
    @Test fun `talk remains without a timer and closes when its NPC context is lost`() {
        assertFalse(NpcTalkCompletionPolicy.shouldClose(npcPresent = true, sameLevel = true, distanceSquared = 16.0))
        assertTrue(NpcTalkCompletionPolicy.shouldClose(npcPresent = false, sameLevel = true, distanceSquared = 0.0))
        assertTrue(NpcTalkCompletionPolicy.shouldClose(npcPresent = true, sameLevel = false, distanceSquared = 0.0))
        assertTrue(NpcTalkCompletionPolicy.shouldClose(npcPresent = true, sameLevel = true, distanceSquared = 32.0 * 32.0 + 1.0))
    }
}
