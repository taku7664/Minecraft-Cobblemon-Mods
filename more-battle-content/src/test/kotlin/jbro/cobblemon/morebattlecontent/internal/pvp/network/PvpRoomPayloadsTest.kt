package jbro.cobblemon.morebattlecontent.internal.pvp.network

import io.netty.buffer.Unpooled
import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpBattleFormat
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpBattleMechanic
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpRoomPhase
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpRoomSettings
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpRoomSide
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpRoomVisibility
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PvpRoomPayloadsTest {
    private val roomId = UUID(0, 100)
    private val requestId = UUID(0, 200)
    private val host = PvpRoomMemberView(UUID(0, 1), "Host")
    private val guest = PvpRoomMemberView(UUID(0, 2), "Guest")
    private val viewer = PvpRoomMemberView(UUID(0, 3), "Viewer")

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
            PvpRoomInvitePayload(roomId, host.name),
            roundTrip(PvpRoomInvitePayload.CODEC, PvpRoomInvitePayload(roomId, host.name)),
        )
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
