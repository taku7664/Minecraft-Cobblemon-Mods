package jbro.cobblemon.morebattlecontent.internal.pvp

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PvpRoomServiceTest {
    private val host = id(1)
    private val guest = id(2)
    private val spectator = id(3)
    private val roomId = id(100)

    @Test
    fun `public rooms accept direct entry while private rooms require an invitation`() {
        val rooms = PvpRoomService { roomId }
        val publicRoom = rooms.create(host, settings(PvpRoomVisibility.PUBLIC)).room

        assertTrue(rooms.join(publicRoom.roomId, guest) is PvpRoomMutation.Applied)

        val privateRoomId = id(101)
        val privateRooms = PvpRoomService { privateRoomId }
        val privateRoom = privateRooms.create(host, settings(PvpRoomVisibility.PRIVATE)).room
        assertEquals(PvpRoomError.INVITE_REQUIRED, privateRooms.join(privateRoom.roomId, guest).errorOrNull())
        assertTrue(privateRooms.invite(privateRoom.roomId, host, guest) is PvpRoomMutation.Applied)
        assertTrue(privateRooms.join(privateRoom.roomId, guest) is PvpRoomMutation.Applied)
    }

    @Test
    fun `declining an invitation removes private room visibility and entry permission`() {
        val rooms = PvpRoomService { roomId }
        val room = rooms.create(host, settings(PvpRoomVisibility.PRIVATE)).room
        rooms.invite(room.roomId, host, guest)

        assertTrue(rooms.visibleRoomsFor(guest).any { it.roomId == room.roomId })
        assertTrue(rooms.declineInvite(room.roomId, guest) is PvpRoomMutation.Applied)

        assertFalse(rooms.visibleRoomsFor(guest).any { it.roomId == room.roomId })
        assertEquals(PvpRoomError.INVITE_REQUIRED, rooms.join(room.roomId, guest).errorOrNull())
        assertEquals(PvpRoomError.INVITE_REQUIRED, rooms.declineInvite(room.roomId, guest).errorOrNull())
    }

    @Test
    fun `members claim either seat and only the host can start with both seats occupied`() {
        val rooms = PvpRoomService { roomId }
        rooms.create(host, settings(PvpRoomVisibility.PUBLIC))
        rooms.join(roomId, guest)
        rooms.join(roomId, spectator)

        assertTrue(rooms.claimSeat(roomId, host, PvpRoomSide.LEFT) is PvpRoomMutation.Applied)
        assertEquals(PvpRoomError.SEATS_INCOMPLETE, rooms.startPreview(roomId, host).errorOrNull())
        assertTrue(rooms.claimSeat(roomId, guest, PvpRoomSide.RIGHT) is PvpRoomMutation.Applied)
        assertEquals(PvpRoomError.HOST_ONLY, rooms.startPreview(roomId, guest).errorOrNull())

        val started = rooms.startPreview(roomId, host) as PvpRoomMutation.Applied
        assertEquals(PvpRoomPhase.TEAM_PREVIEW, started.room.phase)
        assertEquals(listOf(spectator), started.room.spectatorIds)
    }

    @Test
    fun `active public room remains joinable by new spectators`() {
        val rooms = PvpRoomService { roomId }
        rooms.create(host, settings(PvpRoomVisibility.PUBLIC))
        rooms.join(roomId, guest)
        rooms.claimSeat(roomId, host, PvpRoomSide.LEFT)
        rooms.claimSeat(roomId, guest, PvpRoomSide.RIGHT)
        rooms.startPreview(roomId, host)
        rooms.markActive(roomId)

        val joined = rooms.join(roomId, spectator) as PvpRoomMutation.Applied

        assertEquals(PvpRoomPhase.ACTIVE, joined.room.phase)
        assertEquals(listOf(spectator), joined.room.spectatorIds)
        assertTrue(rooms.publicRooms().any { it.roomId == roomId && it.phase == PvpRoomPhase.ACTIVE })
    }

    @Test
    fun `disconnect releases an active spectator room index so they can rejoin`() {
        val rooms = PvpRoomService { roomId }
        rooms.create(host, settings(PvpRoomVisibility.PUBLIC))
        rooms.join(roomId, guest)
        rooms.join(roomId, spectator)
        rooms.claimSeat(roomId, host, PvpRoomSide.LEFT)
        rooms.claimSeat(roomId, guest, PvpRoomSide.RIGHT)
        rooms.startPreview(roomId, host)
        rooms.markActive(roomId)

        assertEquals(roomId, rooms.disconnect(spectator)?.roomId)
        assertNull(rooms.roomFor(spectator))
        assertTrue(rooms.join(roomId, spectator) is PvpRoomMutation.Applied)
    }

    @Test
    fun `host can transfer ownership and unexpected departure elects the oldest remaining member`() {
        val rooms = PvpRoomService { roomId }
        rooms.create(host, settings(PvpRoomVisibility.PUBLIC))
        rooms.join(roomId, guest)
        rooms.join(roomId, spectator)

        assertTrue(rooms.transferHost(roomId, host, spectator) is PvpRoomMutation.Applied)
        assertEquals(spectator, rooms.get(roomId)?.hostId)

        rooms.leave(roomId, spectator)
        assertEquals(host, rooms.get(roomId)?.hostId)
        rooms.leave(roomId, host)
        assertEquals(guest, rooms.get(roomId)?.hostId)
        rooms.leave(roomId, guest)
        assertNull(rooms.get(roomId))
    }

    @Test
    fun `room settings allow multiple mechanics and remain host owned before preview`() {
        val rooms = PvpRoomService { roomId }
        rooms.create(host, settings(PvpRoomVisibility.PUBLIC))
        rooms.join(roomId, guest)

        val all = PvpRoomSettings(
            visibility = PvpRoomVisibility.PRIVATE,
            format = PvpBattleFormat.DOUBLE,
            enabledMechanics = PvpBattleMechanic.entries.toSet(),
        )
        assertEquals(PvpRoomError.HOST_ONLY, rooms.updateSettings(roomId, guest, all).errorOrNull())
        val updated = rooms.updateSettings(roomId, host, all) as PvpRoomMutation.Applied
        assertEquals(PvpBattleMechanic.entries.toSet(), updated.room.settings.enabledMechanics)

        rooms.claimSeat(roomId, host, PvpRoomSide.LEFT)
        rooms.claimSeat(roomId, guest, PvpRoomSide.RIGHT)
        rooms.startPreview(roomId, host)
        assertEquals(PvpRoomError.INVALID_PHASE, rooms.updateSettings(roomId, host, settings(PvpRoomVisibility.PUBLIC)).errorOrNull())
    }

    @Test
    fun `closing a finished room releases every member for another room`() {
        val rooms = PvpRoomService { roomId }
        rooms.create(host, settings(PvpRoomVisibility.PUBLIC))
        rooms.join(roomId, guest)

        val closed = rooms.close(roomId)

        assertEquals(PvpRoomPhase.CLOSED, closed?.phase)
        assertNull(rooms.get(roomId))
        assertNull(rooms.roomFor(host))
        assertNull(rooms.roomFor(guest))
        assertEquals(host, rooms.create(host, settings(PvpRoomVisibility.PRIVATE)).room.hostId)
    }

    @Test
    fun `finishing a match returns the room to its lobby with seats and members intact`() {
        val rooms = PvpRoomService { roomId }
        rooms.create(host, settings(PvpRoomVisibility.PUBLIC))
        rooms.join(roomId, guest)
        rooms.join(roomId, spectator)
        rooms.claimSeat(roomId, host, PvpRoomSide.LEFT)
        rooms.claimSeat(roomId, guest, PvpRoomSide.RIGHT)
        rooms.startPreview(roomId, host)
        rooms.markActive(roomId)

        val settled = requireNotNull(rooms.finishMatch(roomId))

        assertEquals(PvpRoomPhase.LOBBY, settled.phase)
        assertEquals(host, settled.leftPlayerId)
        assertEquals(guest, settled.rightPlayerId)
        assertEquals(listOf(spectator), settled.spectatorIds)
        assertEquals(roomId, rooms.roomFor(host)?.roomId)
        assertEquals(roomId, rooms.roomFor(spectator)?.roomId)
    }

    @Test
    fun `a finished room can immediately host a rematch`() {
        val rooms = PvpRoomService { roomId }
        rooms.create(host, settings(PvpRoomVisibility.PUBLIC))
        rooms.join(roomId, guest)
        rooms.claimSeat(roomId, host, PvpRoomSide.LEFT)
        rooms.claimSeat(roomId, guest, PvpRoomSide.RIGHT)
        rooms.startPreview(roomId, host)
        rooms.markActive(roomId)
        rooms.finishMatch(roomId)

        assertTrue(rooms.updateSettings(roomId, host, settings(PvpRoomVisibility.PRIVATE)) is PvpRoomMutation.Applied)
        assertTrue(rooms.startPreview(roomId, host) is PvpRoomMutation.Applied)
        assertEquals(PvpRoomPhase.TEAM_PREVIEW, rooms.get(roomId)?.phase)
    }

    @Test
    fun `finishing an unknown or closed room reports nothing to restore`() {
        val rooms = PvpRoomService { roomId }
        assertNull(rooms.finishMatch(roomId))

        rooms.create(host, settings(PvpRoomVisibility.PUBLIC))
        rooms.close(roomId)
        assertNull(rooms.finishMatch(roomId))
    }

    private fun settings(visibility: PvpRoomVisibility) = PvpRoomSettings(
        visibility = visibility,
        format = PvpBattleFormat.SINGLE,
        enabledMechanics = setOf(PvpBattleMechanic.MEGA, PvpBattleMechanic.Z_MOVE),
    )

    private fun id(value: Long) = UUID(0, value)
}
