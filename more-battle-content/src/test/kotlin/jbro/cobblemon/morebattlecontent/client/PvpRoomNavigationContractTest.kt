package jbro.cobblemon.morebattlecontent.client

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PvpRoomNavigationContractTest {
    @Test
    fun `escape and close return to room list without leaving membership`() {
        assertTrue(PvpRoomNavigationContract.CLOSE_RETURNS_TO_ROOM_LIST)
        assertFalse(PvpRoomNavigationContract.CLOSE_LEAVES_ROOM)
    }

    @Test
    fun `only the local create or join response opens a room over the room list`() {
        val ownRequest = UUID.randomUUID()
        val anotherMembersRequest = UUID.randomUUID()
        val pending = hashSetOf(ownRequest)

        assertFalse(PvpRoomNavigationContract.shouldOpenFromRoomList(null, pending))
        assertFalse(PvpRoomNavigationContract.shouldOpenFromRoomList(anotherMembersRequest, pending))
        assertTrue(PvpRoomNavigationContract.shouldOpenFromRoomList(ownRequest, pending))
        assertFalse(ownRequest in pending)
    }
}
