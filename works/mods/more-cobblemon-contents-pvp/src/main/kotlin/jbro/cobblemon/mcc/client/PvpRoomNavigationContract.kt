package jbro.cobblemon.mcc.client

import java.util.UUID
import jbro.cobblemon.mcc.internal.pvp.network.PvpRoomClientView
import jbro.cobblemon.mcc.internal.pvp.network.PvpRoomIntent

internal object PvpRoomNavigationContract {
    const val CLOSE_RETURNS_TO_ROOM_LIST = true
    const val CLOSE_LEAVES_ROOM = false

    /**
     * A cached room is presentation state, not proof that the current server connection still owns
     * that membership. Opening always performs the idempotent server-side join handshake again.
     */
    fun openIntent(requestId: UUID, roomId: UUID): PvpRoomIntent.Join = PvpRoomIntent.Join(requestId, roomId)

    fun sendTrackedOpenRequest(requestId: UUID, pendingRequestIds: MutableSet<UUID>, send: () -> Unit) {
        pendingRequestIds += requestId
        try {
            send()
        } catch (failure: Throwable) {
            pendingRequestIds.remove(requestId)
            throw failure
        }
    }

    /**
     * What a client still remembers after a room list: only the room the server says it belongs to. A room that is
     * still listed may be one this player just left, and reopening a remembered room joins it again.
     */
    fun rememberedAfterRoomList(remembered: PvpRoomClientView?, memberRoomId: UUID?): PvpRoomClientView? =
        remembered?.takeIf { it.roomId == memberRoomId }

    /** A spectator's team preview belongs to their room and goes with it; a participant's challenge preview stays. */
    fun keepsSelectionAfterRoomList(spectatorMode: Boolean, memberRoomId: UUID?): Boolean = !spectatorMode || memberRoomId != null

    /** Past the lobby only a spectator may leave; the server refuses a seated player (`leaveRequestError`). */
    fun canLeave(lobby: Boolean, spectator: Boolean): Boolean = lobby || spectator

    fun shouldOpenFromRoomList(requestId: UUID?, pendingRequestIds: MutableSet<UUID>): Boolean =
        requestId != null && pendingRequestIds.remove(requestId)

    /**
     * A server-driven reopen (a match ended and the room returned to its lobby) has no pending client
     * request behind it, so it opens the room screen on its own.
     */
    fun shouldOpen(requestId: UUID?, pendingRequestIds: MutableSet<UUID>, reopen: Boolean): Boolean {
        val requested = shouldOpenFromRoomList(requestId, pendingRequestIds)
        return reopen || requested
    }
}
