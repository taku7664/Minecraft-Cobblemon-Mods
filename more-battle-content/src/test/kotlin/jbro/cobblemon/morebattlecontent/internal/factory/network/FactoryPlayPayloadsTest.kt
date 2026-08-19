package jbro.cobblemon.morebattlecontent.internal.factory.network

import io.netty.buffer.Unpooled
import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryBattleFormat
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryLevelMode
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryPlayError
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryPlayPhase
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryPlayView
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryRentalSet
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryStatSpread
import jbro.cobblemon.morebattlecontent.internal.factory.FactorySwapOffer
import jbro.cobblemon.morebattlecontent.internal.factory.ui.FactoryPlayIntent
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FactoryPlayPayloadsTest {
    private val requestId = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val playerId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

    @Test
    fun `state preserves rental details and partially revealed swap offers`() {
        val state = FactoryPlayView(
            playerId,
            FactoryPlayPhase.SWAP_DECISION,
            FactoryBattleFormat.SINGLE,
            FactoryLevelMode.LEVEL_50,
            2,
            3,
            teamSets = listOf(rental("team")),
            swapOffers = listOf(
                FactorySwapOffer(requestId, "cobblemon:rotom", setOf("cobblemon:hydropump"), null, null, "wash"),
            ),
            canReviseSelection = true,
        )

        val decoded = roundTrip(FactoryPlayStatePayload.CODEC, FactoryPlayStatePayload(null, state)).state

        assertEquals(state.playerId, decoded.playerId)
        assertEquals(state.phase, decoded.phase)
        assertEquals(state.teamSets.single().moveIds, decoded.teamSets.single().moveIds)
        assertEquals(state.teamSets.single().ivs, decoded.teamSets.single().ivs)
        assertEquals(state.swapOffers.single().revealedMoveIds, decoded.swapOffers.single().revealedMoveIds)
        assertEquals(null, decoded.swapOffers.single().revealedAbilityId)
        assertEquals("wash", decoded.swapOffers.single().formId)
        assertEquals(true, decoded.canReviseSelection)
    }

    @Test
    fun `all screen intents and rejection round trip`() {
        val intents = listOf(
            FactoryPlayIntent.Start(requestId, FactoryBattleFormat.DOUBLE, FactoryLevelMode.OPEN_LEVEL),
            FactoryPlayIntent.SelectRentals(requestId, listOf("set_1", "set_2", "set_3", "set_4")),
            FactoryPlayIntent.ReviseSelection(requestId),
            FactoryPlayIntent.BeginBattle(requestId, listOf("set_3", "set_1", "set_2")),
            FactoryPlayIntent.KeepTeam(requestId),
            FactoryPlayIntent.Swap(requestId, "set_1", playerId),
            FactoryPlayIntent.Abandon(requestId),
        )
        intents.forEach { intent ->
            val payload = FactoryPlayIntentPayload(intent)
            assertEquals(payload, roundTrip(FactoryPlayIntentPayload.CODEC, payload))
        }
        val rejected = FactoryPlayRejectedPayload(requestId, FactoryPlayError.INVALID_SELECTION)
        assertEquals(rejected, roundTrip(FactoryPlayRejectedPayload.CODEC, rejected))
    }

    private fun rental(id: String) = FactoryRentalSet(
        id,
        "cobblemon:lucario",
        listOf("cobblemon:close_combat", "cobblemon:bullet_punch"),
        "cobblemon:inner_focus",
        "minecraft:life_orb",
        "cobblemon:jolly",
        FactoryStatSpread(31, 30, 29, 28, 27, 26),
        FactoryStatSpread(4, 252, 0, 0, 0, 252),
        "normal",
    )

    private fun <T : Any> roundTrip(
        codec: net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf, T>,
        value: T,
    ): T {
        val buffer = RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY)
        codec.encode(buffer, value)
        return codec.decode(buffer)
    }
}
