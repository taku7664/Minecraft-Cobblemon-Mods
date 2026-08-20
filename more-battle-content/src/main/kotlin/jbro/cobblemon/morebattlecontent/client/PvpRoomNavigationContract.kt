package jbro.cobblemon.morebattlecontent.client

import java.util.UUID

internal object PvpRoomNavigationContract {
    const val CLOSE_RETURNS_TO_ROOM_LIST = true
    const val CLOSE_LEAVES_ROOM = false

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
