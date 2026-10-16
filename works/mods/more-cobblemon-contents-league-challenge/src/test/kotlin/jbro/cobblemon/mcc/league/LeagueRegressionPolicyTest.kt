package jbro.cobblemon.mcc.league

import java.nio.file.Files
import java.nio.file.Path
import jbro.cobblemon.mcc.league.client.LeagueTooltipTarget
import jbro.cobblemon.mcc.league.network.LeagueAction
import jbro.cobblemon.mcc.league.server.LeagueErrorChannel
import jbro.cobblemon.mcc.league.server.LeagueErrorDelivery
import jbro.cobblemon.mcc.league.trainer.WildNpcDeparturePolicy
import jbro.cobblemon.mcc.league.ui.LeagueLiveHomeState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LeagueRegressionPolicyTest {
    @Test fun `server error clears pending through controller before showing one popup`() {
        val state = LeagueLiveHomeState()
        val view = jbro.cobblemon.mcc.league.network.LeagueView(
            java.util.UUID.randomUUID(), 1, 1, "league.name", 1, "POKE_BALL", 20, false, 10,
            listOf(jbro.cobblemon.mcc.league.network.LeagueChallengeView(
                "test:gym", "gym", "AVAILABLE", 20)), null, false, false,
        )
        state.accept(view)
        assertTrue(state.begin(LeagueAction.START))
        state.reject()
        assertFalse(state.pending)
        assertTrue(state.begin(LeagueAction.START))

        val session = Files.readString(Path.of(
            "src/main/kotlin/jbro/cobblemon/mcc/league/client/LeagueClientSession.kt"))
        assertTrue(session.contains("LeagueHomeController.receiveError(payload.translationKey)"))
        assertFalse(session.contains("execute { LeagueErrorPopup.show(payload.translationKey) }"))

        val controller = Files.readString(Path.of(
            "src/main/kotlin/jbro/cobblemon/mcc/league/client/LeagueHomeController.kt"))
        val failurePath = controller.substringAfter("private fun fail(key: String)")
        assertTrue(failurePath.indexOf("state.reject()") < failurePath.indexOf("LeagueErrorPopup.show(key)"))
    }

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
