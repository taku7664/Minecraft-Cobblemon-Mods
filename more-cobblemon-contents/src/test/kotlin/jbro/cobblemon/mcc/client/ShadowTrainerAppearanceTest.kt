package jbro.cobblemon.mcc.client

import java.util.UUID
import jbro.cobblemon.mcc.internal.shadow.ShadowTrainerProjection
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShadowTrainerAppearanceTest {
    private fun projection(skin: String?) = ShadowTrainerProjection(
        battleId = UUID.randomUUID(),
        x = 0.0, y = 64.0, z = 8.0, yaw = 180F, resourceSkin = skin,
    )

    @Test
    fun `a battle without a trainer skin shows the challenger's hologram, one with a skin shows that trainer`() {
        assertTrue(projection(null).isHologram)
        assertFalse(projection("rctmod:textures/trainers/single/gym_leader_roark_0395.png").isHologram)
    }
}
