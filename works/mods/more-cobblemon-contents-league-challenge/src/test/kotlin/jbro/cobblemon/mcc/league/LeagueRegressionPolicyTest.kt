package jbro.cobblemon.mcc.league

import jbro.cobblemon.mcc.league.client.LeagueTooltipTarget
import jbro.cobblemon.mcc.league.server.LeagueErrorChannel
import jbro.cobblemon.mcc.league.server.LeagueErrorDelivery
import jbro.cobblemon.mcc.league.trainer.WildNpcDeparturePolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LeagueRegressionPolicyTest {
    @Test fun `popup capability selects exactly one error channel`() {
        assertEquals(LeagueErrorChannel.POPUP, LeagueErrorDelivery.channel(true))
        assertEquals(LeagueErrorChannel.CHAT, LeagueErrorDelivery.channel(false))
    }

    @Test fun `challenger and reward tooltip hover uses current mouse coordinates`() {
        assertTrue(LeagueTooltipTarget.hovered(10, 20, 30, 40, 10, 20))
        assertTrue(LeagueTooltipTarget.hovered(10, 20, 30, 40, 39, 59))
        assertFalse(LeagueTooltipTarget.hovered(10, 20, 30, 40, 40, 59))
        assertFalse(LeagueTooltipTarget.hovered(10, 20, 30, 40, 39, 60))
    }

    @Test fun `departing wild NPC waits for dialogue and battle but not elapsed time`() {
        assertFalse(WildNpcDeparturePolicy.shouldDiscard(marked = true, waitingForDialogue = true, inBattle = false))
        assertFalse(WildNpcDeparturePolicy.shouldDiscard(marked = true, waitingForDialogue = false, inBattle = true))
        assertTrue(WildNpcDeparturePolicy.shouldDiscard(marked = true, waitingForDialogue = false, inBattle = false))
        assertFalse(WildNpcDeparturePolicy.shouldDiscard(marked = false, waitingForDialogue = false, inBattle = false))
    }
}
