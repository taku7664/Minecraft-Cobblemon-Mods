package jbro.cobblemon.morebattlecontent.internal.shadow

import io.netty.buffer.Unpooled
import java.util.UUID
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ShadowTrainerProjectionTest {
    @Test
    fun `projection payload preserves player appearance and placement`() {
        val projection = projection(UUID.randomUUID())
        val payload = ShowShadowTrainerPayload(projection)
        val buffer = RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY)

        ShowShadowTrainerPayload.CODEC.encode(buffer, payload)

        assertEquals(payload, ShowShadowTrainerPayload.CODEC.decode(buffer))
    }

    @Test
    fun `stale battle completion cannot remove a newer projection`() {
        val firstBattle = UUID.randomUUID()
        val secondBattle = UUID.randomUUID()
        val state = ShadowTrainerProjectionState()
        state.show(projection(firstBattle))
        state.show(projection(secondBattle))

        state.hide(firstBattle)
        assertEquals(secondBattle, state.current()?.battleId)

        state.hide(secondBattle)
        assertNull(state.current())
    }

    private fun projection(battleId: UUID) = ShadowTrainerProjection(
        battleId = battleId,
        profileId = UUID.fromString("ed9d3b59-753f-42f7-a840-9b766ef0a86b"),
        profileName = "Park_JH",
        x = 4.5,
        y = 72.0,
        z = 14.5,
        yaw = 180.0F,
    )
}
