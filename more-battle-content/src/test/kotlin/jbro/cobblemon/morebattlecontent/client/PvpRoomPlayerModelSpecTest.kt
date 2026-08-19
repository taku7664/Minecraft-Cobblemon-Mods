package jbro.cobblemon.morebattlecontent.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PvpRoomPlayerModelSpecTest {
    @Test
    fun `room player model is front facing and advances standing animation`() {
        assertEquals(180f, PvpRoomPlayerModelSpec.BODY_YAW)
        assertEquals(180f, PvpRoomPlayerModelSpec.HEAD_YAW)
        assertEquals(0f, PvpRoomPlayerModelSpec.PITCH)
        assertFalse(PvpRoomPlayerModelSpec.FOLLOWS_MOUSE)
        assertTrue(PvpRoomPlayerModelSpec.ADVANCES_IDLE_ANIMATION)
    }
}
