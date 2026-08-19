package jbro.cobblemon.morebattlecontent.client

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PvpInviteChatActionTest {
    private val roomId = UUID(0, 42)

    @Test
    fun `chat insertion markers round trip direct join and decline actions`() {
        PvpInviteChatAction.entries.forEach { action ->
            val marker = PvpInviteChatActionMarker.encode(action, roomId)

            assertEquals(PvpInviteChatTarget(action, roomId), PvpInviteChatActionMarker.decode(marker))
        }
    }

    @Test
    fun `unrelated and malformed insertion markers are ignored`() {
        assertNull(PvpInviteChatActionMarker.decode(null))
        assertNull(PvpInviteChatActionMarker.decode("https://example.invalid"))
        assertNull(PvpInviteChatActionMarker.decode("mbc:pvp_invite:join:not-a-uuid"))
        assertNull(PvpInviteChatActionMarker.decode("mbc:pvp_invite:delete:$roomId"))
    }
}
