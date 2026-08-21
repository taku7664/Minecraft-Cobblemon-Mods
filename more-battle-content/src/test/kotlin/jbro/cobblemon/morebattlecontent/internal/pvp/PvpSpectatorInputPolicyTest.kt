package jbro.cobblemon.morebattlecontent.internal.pvp

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PvpSpectatorInputPolicyTest {
    @Test
    fun `pure spectator blocks player control and pokemon send while retaining camera controls`() {
        val blocked = listOf(
            "key.forward",
            "key.attack",
            "key.inventory",
            "key.hotbar.1",
            "key.cobblemon.send_out_pokemon",
        )
        blocked.forEach { key -> assertTrue(PvpSpectatorInputPolicy.blocks(key), key) }

        assertFalse(PvpSpectatorInputPolicy.blocks("key.battlecam.cycle_mode"))
        assertFalse(PvpSpectatorInputPolicy.blocks("key.screenshot"))
        assertFalse(PvpSpectatorInputPolicy.blocks("key.chat"))
    }
}
