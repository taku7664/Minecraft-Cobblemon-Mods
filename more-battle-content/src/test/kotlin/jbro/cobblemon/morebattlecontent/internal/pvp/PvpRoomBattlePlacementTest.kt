package jbro.cobblemon.morebattlecontent.internal.pvp

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PvpRoomBattlePlacementTest {
    private val roomId = UUID(0, 100)
    private val left = UUID(0, 1)
    private val right = UUID(0, 2)

    @Test
    fun `room becomes active only after its prepared lounge binds to the started battle`() {
        val rooms = previewRoom()
        val gateway = RecordingGateway()
        val lounge = PvpLoungeCoordinator(PvpArenaPool(), gateway)
        val placement = PvpRoomBattlePlacement(rooms, lounge)

        val prepared = placement.prepare(request())

        assertNotNull(prepared)
        assertEquals(PvpRoomPhase.TEAM_PREVIEW, rooms.get(roomId)?.phase)
        assertEquals(listOf(left, right), gateway.movedCompetitors)
        assertTrue(requireNotNull(prepared).activate(UUID(0, 900)))
        assertEquals(PvpRoomPhase.ACTIVE, rooms.get(roomId)?.phase)
        assertEquals(setOf(roomId), lounge.activeRoomIds())
    }

    @Test
    fun `room placement rejects a launch whose participants do not own both seats`() {
        val rooms = previewRoom()
        val lounge = PvpLoungeCoordinator(PvpArenaPool(), RecordingGateway())
        val wrongPlayer = UUID(0, 77)

        val prepared = PvpRoomBattlePlacement(rooms, lounge).prepare(
            request().copy(
                secondPlayerId = wrongPlayer,
                secondSelection = selection(wrongPlayer, 4),
            ),
        )

        assertNull(prepared)
        assertNull(lounge.leaseFor(roomId))
        assertEquals(PvpRoomPhase.TEAM_PREVIEW, rooms.get(roomId)?.phase)
    }

    private fun previewRoom(): PvpRoomService = PvpRoomService { roomId }.also { rooms ->
        val settings = PvpRoomSettings(
            PvpRoomVisibility.PUBLIC,
            PvpBattleFormat.SINGLE,
            PvpRoomDefaults.ENABLED_MECHANICS,
        )
        rooms.create(left, settings)
        rooms.join(roomId, right)
        rooms.claimSeat(roomId, left, PvpRoomSide.LEFT)
        rooms.claimSeat(roomId, right, PvpRoomSide.RIGHT)
        rooms.startPreview(roomId, left)
    }

    private fun request() = PvpBattleLaunchRequest(
        matchId = roomId,
        firstPlayerId = left,
        secondPlayerId = right,
        format = PvpBattleFormat.SINGLE,
        firstSelection = selection(left, 1),
        secondSelection = selection(right, 4),
    )

    private fun selection(playerId: UUID, firstIndex: Int) = PvpSelectedTeam(
        PvpBattleFormat.SINGLE,
        (firstIndex until firstIndex + 3).map { index ->
            PvpPokemonRegistration(UUID(playerId.mostSignificantBits, index.toLong()), "cobblemon:species$index", null, 50)
        },
    )

    private class RecordingGateway : PvpLoungeGateway {
        val movedCompetitors = ArrayList<UUID>()

        override fun ensureArena(lease: PvpArenaLease): Boolean = true

        override fun capture(playerId: UUID) = PvpReturnPoint(
            "minecraft:overworld",
            playerId.leastSignificantBits.toDouble(),
            64.0,
            0.0,
            0f,
            0f,
            "survival",
        )

        override fun moveCompetitor(playerId: UUID, lease: PvpArenaLease, side: PvpRoomSide): Boolean =
            true.also { movedCompetitors += playerId }

        override fun moveSpectator(playerId: UUID, lease: PvpArenaLease): Boolean = true

        override fun spectate(viewerId: UUID, targetId: UUID): Boolean = true

        override fun restore(playerId: UUID, point: PvpReturnPoint): Boolean = true
    }
}
