package jbro.cobblemon.mcc.internal.pvp.network

import java.util.UUID
import jbro.cobblemon.mcc.internal.pvp.PvpRoomService
import jbro.cobblemon.mcc.internal.pvp.PvpRoomView

internal enum class PvpRoomRefreshResponse {
    ROOM_LIST,
}

internal object PvpRoomRefreshContract {
    fun response(@Suppress("UNUSED_PARAMETER") hasRoomMembership: Boolean): PvpRoomRefreshResponse =
        PvpRoomRefreshResponse.ROOM_LIST
}

/** The room list [playerId] sees, naming the room they belong to so their client forgets one they have left. */
internal fun PvpRoomService.listStateFor(
    playerId: UUID,
    requestId: UUID?,
    summary: (PvpRoomView) -> PvpRoomSummaryView,
): PvpRoomListStatePayload = PvpRoomListStatePayload(requestId, visibleRoomsFor(playerId).map(summary), roomFor(playerId)?.roomId)
