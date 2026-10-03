package jbro.cobblemon.mcc.internal.battle

import jbro.cobblemon.ui.extended.transition.BattleEntryKind
import jbro.cobblemon.ui.extended.transition.BattleEntryTimeline
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WildBattleEntryDelayTest {
    /** The server must not start the battle before the client's screen is covered, nor keep it waiting long after. */
    @Test
    fun `the server waits until the client's cover ends`() {
        for ((legendary, kind) in listOf(true to BattleEntryKind.LEGENDARY, false to BattleEntryKind.WILD)) {
            val coverEnd = BattleEntryTimeline.flashEnd(kind) + kind.coverMillis
            val wait = WildBattleEntryDelay.waitTicks(legendary) * 50L
            assertTrue(wait >= coverEnd, "$kind waits $wait ms for a cover of $coverEnd ms")
            assertTrue(wait - coverEnd <= 100, "$kind waits $wait ms, well past its cover of $coverEnd ms")
        }
    }
}
