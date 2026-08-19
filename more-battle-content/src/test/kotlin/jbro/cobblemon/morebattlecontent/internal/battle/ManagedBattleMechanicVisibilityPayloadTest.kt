package jbro.cobblemon.morebattlecontent.internal.battle

import io.netty.buffer.Unpooled
import java.util.UUID
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ManagedBattleMechanicVisibilityPayloadTest {
    private val battleId = UUID.randomUUID()

    @Test
    fun `show payload preserves empty singleton and multiple mechanic policies`() {
        listOf(
            emptySet(),
            setOf(ManagedBattleMechanic.MEGA),
            setOf(ManagedBattleMechanic.DYNAMAX, ManagedBattleMechanic.TERA, ManagedBattleMechanic.Z_MOVE),
        ).forEach { mechanics ->
            val payload = ShowManagedBattleMechanicsPayload(battleId, mechanics)
            val buffer = RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY)

            ShowManagedBattleMechanicsPayload.CODEC.encode(buffer, payload)

            assertEquals(payload, ShowManagedBattleMechanicsPayload.CODEC.decode(buffer))
        }
    }

    @Test
    fun `hide payload preserves the managed battle id`() {
        val payload = HideManagedBattleMechanicsPayload(battleId)
        val buffer = RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY)

        HideManagedBattleMechanicsPayload.CODEC.encode(buffer, payload)

        assertEquals(payload, HideManagedBattleMechanicsPayload.CODEC.decode(buffer))
    }
}
