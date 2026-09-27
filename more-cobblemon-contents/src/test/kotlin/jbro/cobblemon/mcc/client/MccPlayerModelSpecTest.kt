package jbro.cobblemon.mcc.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MccPlayerModelSpecTest {
    @Test
    fun `room player model is front facing and advances standing animation`() {
        assertEquals(180f, MccPlayerModelSpec.BODY_YAW)
        assertEquals(180f, MccPlayerModelSpec.HEAD_YAW)
        assertEquals(0f, MccPlayerModelSpec.PITCH)
        assertFalse(MccPlayerModelSpec.FOLLOWS_MOUSE)
        assertTrue(MccPlayerModelSpec.ADVANCES_IDLE_ANIMATION)
    }
}
