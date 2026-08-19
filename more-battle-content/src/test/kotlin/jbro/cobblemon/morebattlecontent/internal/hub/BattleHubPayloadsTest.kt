package jbro.cobblemon.morebattlecontent.internal.hub

import io.netty.buffer.Unpooled
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BattleHubPayloadsTest {
    @Test
    fun `hub state and every tab intent round trip`() {
        val state = BattleHubStatePayload
        assertEquals(state, roundTrip(BattleHubStatePayload.CODEC, state))
        val header = BattleHubHeaderStatePayload(275L)
        assertEquals(header, roundTrip(BattleHubHeaderStatePayload.CODEC, header))
        BattleHubContent.entries.forEach { content ->
            val payload = BattleHubOpenContentPayload(content)
            assertEquals(payload, roundTrip(BattleHubOpenContentPayload.CODEC, payload))
        }
    }

    @Test
    fun `legacy hub state remains an empty payload while BP uses the header payload`() {
        assertEquals(0, encodedSize(BattleHubStatePayload.CODEC, BattleHubStatePayload))
        assertEquals(2, encodedSize(BattleHubHeaderStatePayload.CODEC, BattleHubHeaderStatePayload(275L)))
    }

    private fun <T : Any> roundTrip(
        codec: net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf, T>,
        value: T,
    ): T {
        val buffer = RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY)
        codec.encode(buffer, value)
        return codec.decode(buffer)
    }

    private fun <T : Any> encodedSize(
        codec: net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf, T>,
        value: T,
    ): Int {
        val buffer = RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY)
        codec.encode(buffer, value)
        return buffer.readableBytes()
    }
}
