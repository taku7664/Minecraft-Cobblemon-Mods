package jbro.cobblemon.morebattlecontent.internal.pvp.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PvpRoomRefreshContractTest {
    @Test
    fun `refresh returns the room list even while the player remains a room member`() {
        assertEquals(
            PvpRoomRefreshResponse.ROOM_LIST,
            PvpRoomRefreshContract.response(hasRoomMembership = true),
        )
    }

    @Test
    fun `refresh returns the room list when the player has no room`() {
        assertEquals(
            PvpRoomRefreshResponse.ROOM_LIST,
            PvpRoomRefreshContract.response(hasRoomMembership = false),
        )
    }
}
