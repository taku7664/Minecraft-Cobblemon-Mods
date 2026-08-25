package jbro.cobblemon.morebattlecontent.client

import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpRoomIntent

internal object PvpRoomNavigationContract {
    const val CLOSE_RETURNS_TO_ROOM_LIST = true
    const val CLOSE_LEAVES_ROOM = false

    /**
     * A cached room is presentation state, not proof that the current server connection still owns
     * that membership. Opening always performs the idempotent server-side join handshake again.
     */
    fun openIntent(requestId: UUID, roomId: UUID): PvpRoomIntent.Join = PvpRoomIntent.Join(requestId, roomId)

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
