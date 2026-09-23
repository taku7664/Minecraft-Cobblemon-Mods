package jbro.cobblemon.morebattlecontent.internal.pvp.ui

import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpBattleFormat
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpBattleMechanic
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpRoomPhase
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpRoomSettings
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpRoomVisibility
import jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpRoomClientView
import jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpRoomIntent
import jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpRoomMemberView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PvpRoomScreenControllerTest {
    private val roomId = UUID.randomUUID()
    private val hostId = UUID.randomUUID()

    @Test
    fun `room mutations serialize until the matching response arrives`() {
        val sent = ArrayList<PvpRoomIntent>()
        val controller = PvpRoomScreenController(view(), sent::add)
        val visibilityRequest = UUID.randomUUID()
        val formatRequest = UUID.randomUUID()

        assertTrue(
            controller.submit(
                PvpRoomIntent.UpdateSettings(
                    visibilityRequest,
                    roomId,
                    settings(PvpRoomVisibility.PRIVATE, PvpBattleFormat.SINGLE),
                ),
            ),
        )
        assertFalse(
            controller.submit(
                PvpRoomIntent.UpdateSettings(
                    formatRequest,
                    roomId,
                    settings(PvpRoomVisibility.PUBLIC, PvpBattleFormat.DOUBLE),
                ),
            ),
        )
        assertEquals(1, sent.size)

        controller.applyState(UUID.randomUUID(), view())
        assertTrue(controller.isPending)
        controller.applyState(visibilityRequest, view(PvpRoomVisibility.PRIVATE))
        assertFalse(controller.isPending)
    }

    @Test
    fun `matching rejection unlocks the room while unrelated rejection does not`() {
        val controller = PvpRoomScreenController(view()) {}
        val requestId = UUID.randomUUID()
        assertTrue(controller.submit(PvpRoomIntent.Observe(requestId, roomId)))

        controller.applyRejected(UUID.randomUUID(), "unrelated")
        assertTrue(controller.isPending)
        assertEquals(null, controller.feedbackKey)

        controller.applyRejected(requestId, "rejected")
        assertFalse(controller.isPending)
        assertEquals("rejected", controller.feedbackKey)
    }

    @Test
    fun `send failure releases the room mutation lock`() {
        val failure = IllegalStateException("send failed")
        val controller = PvpRoomScreenController(view()) { throw failure }

        try {
            controller.submit(PvpRoomIntent.Observe(UUID.randomUUID(), roomId))
        } catch (thrown: IllegalStateException) {
            assertEquals(failure, thrown)
        }

        assertFalse(controller.isPending)
    }

    private fun view(visibility: PvpRoomVisibility = PvpRoomVisibility.PUBLIC) = PvpRoomClientView(
        roomId,
        hostId,
        settings(visibility, PvpBattleFormat.SINGLE),
        PvpRoomPhase.LOBBY,
        PvpRoomMemberView(hostId, "host"),
        null,
        emptyList(),
        emptyList(),
    )

    private fun settings(visibility: PvpRoomVisibility, format: PvpBattleFormat) = PvpRoomSettings(
        visibility,
        format,
        setOf(PvpBattleMechanic.MEGA),
    )
}
