package jbro.cobblemon.mcc.internal.pvp.network

import io.netty.buffer.Unpooled
import java.util.UUID
import jbro.cobblemon.mcc.internal.pvp.PvpBattleFormat
import jbro.cobblemon.mcc.internal.pvp.PvpBattleMechanic
import jbro.cobblemon.mcc.internal.pvp.PvpRoomPhase
import jbro.cobblemon.mcc.internal.pvp.PvpRoomSettings
import jbro.cobblemon.mcc.internal.pvp.PvpRoomSide
import jbro.cobblemon.mcc.internal.pvp.PvpRoomVisibility
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PvpRoomPayloadsTest {
    private val roomId = UUID(0, 100)
    private val requestId = UUID(0, 200)
    private val host = PvpRoomMemberView(UUID(0, 1), "Host")
    private val guest = PvpRoomMemberView(UUID(0, 2), "Guest")
    private val viewer = PvpRoomMemberView(UUID(0, 3), "Viewer")
    private val inviteSettings = PvpRoomSettings(PvpRoomVisibility.PRIVATE, PvpBattleFormat.DOUBLE, setOf(PvpBattleMechanic.TERA),
        speciesClause = false, itemClause = true)

    @Test
    fun `room list and room state round trip with active public spectator entry`() {
        val summary = PvpRoomSummaryView(
            roomId,
            host,
            PvpRoomSettings(PvpRoomVisibility.PUBLIC, PvpBattleFormat.DOUBLE, PvpBattleMechanic.entries.toSet()),
            PvpRoomPhase.ACTIVE,
            host,
            guest,
            11,
        )
        val room = PvpRoomClientView(
            roomId,
            host.playerId,
            summary.settings,
            PvpRoomPhase.ACTIVE,
            host,
            guest,
            listOf(viewer),
            emptyList(),
        )

        assertEquals(
            PvpRoomListStatePayload(requestId, listOf(summary)),
            roundTrip(PvpRoomListStatePayload.CODEC, PvpRoomListStatePayload(requestId, listOf(summary))),
        )
        assertEquals(
            PvpRoomListStatePayload(null, listOf(summary), memberRoomId = roomId),
            roundTrip(PvpRoomListStatePayload.CODEC, PvpRoomListStatePayload(null, listOf(summary), memberRoomId = roomId)),
        )
        assertEquals(
            PvpRoomStatePayload(requestId, room),
            roundTrip(PvpRoomStatePayload.CODEC, PvpRoomStatePayload(requestId, room)),
        )
        assertEquals(
            PvpRoomStatePayload(null, room, reopen = true),
            roundTrip(PvpRoomStatePayload.CODEC, PvpRoomStatePayload(null, room, reopen = true)),
        )
    }

    @Test
    fun `room mutations round trip including multi mechanic settings and host actions`() {
        val settings = PvpRoomSettings(
            PvpRoomVisibility.PRIVATE,
            PvpBattleFormat.SINGLE,
            setOf(PvpBattleMechanic.MEGA, PvpBattleMechanic.Z_MOVE),
        )
        val intents = listOf(
            PvpRoomIntent.Refresh(requestId),
            PvpRoomIntent.Create(requestId, settings),
            PvpRoomIntent.Join(requestId, roomId),
            PvpRoomIntent.Leave(requestId, roomId),
            PvpRoomIntent.ClaimSeat(requestId, roomId, PvpRoomSide.RIGHT),
            PvpRoomIntent.Observe(requestId, roomId),
            PvpRoomIntent.UpdateSettings(requestId, roomId, settings),
            PvpRoomIntent.Invite(requestId, roomId, guest.playerId),
            PvpRoomIntent.DeclineInvite(requestId, roomId),
            PvpRoomIntent.TransferHost(requestId, roomId, guest.playerId),
            PvpRoomIntent.Start(requestId, roomId),
        )

        intents.forEach { intent ->
            val payload = PvpRoomIntentPayload(intent)
            assertEquals(payload, roundTrip(PvpRoomIntentPayload.CODEC, payload))
        }
        assertEquals(
            PvpLoungeSpectatorStatePayload(true),
            roundTrip(PvpLoungeSpectatorStatePayload.CODEC, PvpLoungeSpectatorStatePayload(true)),
        )
        assertEquals(
            PvpRoomInvitePayload(roomId, host.name, inviteSettings),
            roundTrip(PvpRoomInvitePayload.CODEC, PvpRoomInvitePayload(roomId, host.name, inviteSettings)),
        )
    }

    @Test
    fun `a room past the old 128 member bound round trips with the capped invite list`() {
        val spectators = List(300) { PvpRoomMemberView(UUID(1, it.toLong()), "Viewer$it") }
        val candidates = List(PVP_ROOM_MAX_INVITE_CANDIDATES) { PvpRoomMemberView(UUID(2, it.toLong()), "Guest$it") }
        val room = PvpRoomClientView(
            roomId,
            host.playerId,
            PvpRoomSettings(PvpRoomVisibility.PUBLIC, PvpBattleFormat.SINGLE, emptySet()),
            PvpRoomPhase.LOBBY,
            host,
            null,
            spectators,
            candidates,
        )
        val summary = PvpRoomSummaryView(roomId, host, room.settings, PvpRoomPhase.LOBBY, host, null, spectators.size)

        assertEquals(PvpRoomStatePayload(null, room), roundTrip(PvpRoomStatePayload.CODEC, PvpRoomStatePayload(null, room)))
        assertEquals(
            PvpRoomListStatePayload(null, listOf(summary)),
            roundTrip(PvpRoomListStatePayload.CODEC, PvpRoomListStatePayload(null, listOf(summary))),
        )
    }

    @Test
    fun `the sender refuses an invite list the client would reject instead of disconnecting it`() {
        val room = PvpRoomClientView(
            roomId,
            host.playerId,
            PvpRoomSettings(PvpRoomVisibility.PUBLIC, PvpBattleFormat.SINGLE, emptySet()),
            PvpRoomPhase.LOBBY,
            host,
            null,
            emptyList(),
            List(PVP_ROOM_MAX_INVITE_CANDIDATES + 1) { PvpRoomMemberView(UUID(2, it.toLong()), "Guest$it") },
        )

        assertThrows<IllegalArgumentException> {
            PvpRoomStatePayload.CODEC.encode(RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY), PvpRoomStatePayload(null, room))
        }
    }

    @Test
    fun `a lobby carries its duplicate rules and who is invited but not in yet`() {
        val room = PvpRoomClientView(roomId, host.playerId, inviteSettings, PvpRoomPhase.LOBBY, host, null, emptyList(),
            listOf(guest, viewer), invitedIds = setOf(guest.playerId))

        val decoded = roundTrip(PvpRoomStatePayload.CODEC, PvpRoomStatePayload(requestId, room)).room

        assertEquals(room, decoded)
        assertEquals(false, decoded.settings.speciesClause)
        assertEquals(true, decoded.settings.itemClause)
    }

    @Test
    fun `invite candidates leave out players busy in another room or match`() {
        val busy = UUID(4, 1)
        val candidates = pvpInviteCandidates(listOf(host.playerId, guest.playerId, busy), setOf(host.playerId),
            { id -> PvpRoomMemberView(id, id.toString()) }) { id -> id == busy }

        assertEquals(listOf(guest.playerId), candidates.map(PvpRoomMemberView::playerId))
    }

    @Test
    fun `invite candidates leave out room members and keep the first names up to the cap`() {
        val members = setOf(host.playerId)
        val online = listOf(host.playerId) + List(PVP_ROOM_MAX_INVITE_CANDIDATES + 30) { UUID(3, it.toLong()) }

        val candidates = pvpInviteCandidates(online, members, memberView = { id -> PvpRoomMemberView(id, "P%04d".format(id.leastSignificantBits)) })

        assertEquals(PVP_ROOM_MAX_INVITE_CANDIDATES, candidates.size)
        assertEquals("P0000", candidates.first().name)
        assertEquals(candidates.sortedBy(PvpRoomMemberView::name), candidates)
        assertTrue(candidates.none { it.playerId == host.playerId })
    }

    private fun <T : Any> roundTrip(
        codec: net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf, T>,
        value: T,
    ): T {
        val buffer = RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY)
        codec.encode(buffer, value)
        return codec.decode(buffer)
    }
}
