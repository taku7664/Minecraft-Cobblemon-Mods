package jbro.cobblemon.mcc.internal.hub

import io.netty.buffer.Unpooled
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BattleHubPayloadsTest {
    @Test
    fun `hub state and every tab intent round trip`() {
        val state = BattleHubStatePayload(listOf(BattleHubIds.DASHBOARD, BattleHubIds.SHOP, "more_cobblemon_contents:pvp"), BattleHubIds.SHOP)
        assertEquals(state, roundTrip(BattleHubStatePayload.CODEC, state))
        val header = BattleHubHeaderStatePayload(275L)
        assertEquals(header, roundTrip(BattleHubHeaderStatePayload.CODEC, header))
        listOf(BattleHubIds.SHOP, BattleHubIds.BOSS_RAID, "more_cobblemon_contents:battle_tower").forEach { contentId ->
            val payload = BattleHubOpenContentPayload(contentId)
            assertEquals(payload, roundTrip(BattleHubOpenContentPayload.CODEC, payload))
        }
    }

    @Test
    fun `hub state names at least one tab and opens on one of them`() {
        assertThrows(IllegalArgumentException::class.java) { BattleHubStatePayload(emptyList(), BattleHubIds.DASHBOARD) }
        assertThrows(IllegalArgumentException::class.java) { BattleHubStatePayload(listOf(BattleHubIds.SHOP), BattleHubIds.DASHBOARD) }
        assertThrows(IllegalArgumentException::class.java) {
            BattleHubStatePayload(listOf(BattleHubIds.SHOP, BattleHubIds.SHOP), BattleHubIds.SHOP)
        }
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
