package jbro.cobblemon.ui.extended

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BattleConfirmGateTest {
    @AfterEach
    fun releaseConfirm() {
        BattleConfirmGate.released()
    }

    @Test
    fun `menu press can be consumed only once until release`() {
        BattleConfirmGate.pressed(true)

        assertTrue(BattleConfirmGate.consumeMenuPress())
        assertFalse(BattleConfirmGate.consumeMenuPress())

        BattleConfirmGate.released()
        BattleConfirmGate.pressed(true)
        assertTrue(BattleConfirmGate.consumeMenuPress())
    }

    @Test
    fun `press that began outside the menu is never available`() {
        BattleConfirmGate.pressed(false)

        assertFalse(BattleConfirmGate.consumeMenuPress())
    }
}
