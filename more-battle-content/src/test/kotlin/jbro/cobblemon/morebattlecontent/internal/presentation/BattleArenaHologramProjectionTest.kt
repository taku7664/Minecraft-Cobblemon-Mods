package jbro.cobblemon.morebattlecontent.internal.presentation

import io.netty.buffer.Unpooled
import java.util.UUID
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BattleArenaHologramProjectionTest {
    @Test
    fun `show payload preserves a common arena center and viewer-relative opponent direction`() {
        val payload = ShowBattleArenaHologramPayload(
            BattleArenaHologramProjection.between(
                battleId = UUID.randomUUID(),
                perspectivePosition = Vec3(-7.0, 64.0, 0.0),
                opponentPosition = Vec3(7.0, 64.0, 0.0),
            ),
        )
        val buffer = RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY)

        ShowBattleArenaHologramPayload.CODEC.encode(buffer, payload)

        assertEquals(payload, ShowBattleArenaHologramPayload.CODEC.decode(buffer))
        assertEquals(0.0, payload.projection.centerX)
        assertEquals(64.0, payload.projection.centerY)
        assertEquals(1.0, payload.projection.opponentDirectionX)
        assertEquals(0.0, payload.projection.opponentDirectionZ)
    }

    @Test
    fun `coincident positions use a stable world direction`() {
        val projection = BattleArenaHologramProjection.between(
            battleId = UUID.randomUUID(),
            perspectivePosition = Vec3(2.0, 70.0, 3.0),
            opponentPosition = Vec3(2.0, 70.0, 3.0),
        )

        assertEquals(0.0, projection.opponentDirectionX)
        assertEquals(1.0, projection.opponentDirectionZ)
    }
}
