package jbro.cobblemon.morebattlecontent.client

import java.util.UUID

internal object PvpRoomNavigationContract {
    const val CLOSE_RETURNS_TO_ROOM_LIST = true
    const val CLOSE_LEAVES_ROOM = false

    fun shouldOpenFromRoomList(requestId: UUID?, pendingRequestIds: MutableSet<UUID>): Boolean =
        requestId != null && pendingRequestIds.remove(requestId)
}
