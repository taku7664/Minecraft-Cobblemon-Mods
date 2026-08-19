package jbro.cobblemon.morebattlecontent.internal.pvp.network

internal enum class PvpRoomRefreshResponse {
    ROOM_LIST,
}

internal object PvpRoomRefreshContract {
    fun response(@Suppress("UNUSED_PARAMETER") hasRoomMembership: Boolean): PvpRoomRefreshResponse =
        PvpRoomRefreshResponse.ROOM_LIST
}
