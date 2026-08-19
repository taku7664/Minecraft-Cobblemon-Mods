package jbro.cobblemon.morebattlecontent.internal.pvp.network

import io.netty.buffer.Unpooled
import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpBattleFormat
import jbro.cobblemon.morebattlecontent.internal.pvp.ui.PvpSelectionIntent
import jbro.cobblemon.morebattlecontent.internal.pvp.ui.PvpSelectionPartySlot
import jbro.cobblemon.morebattlecontent.internal.pvp.ui.PvpSelectionSpectator
import jbro.cobblemon.morebattlecontent.internal.pvp.ui.PvpSelectionViewState
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PvpPlayPayloadsTest {
    private val matchId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val requestId = UUID.fromString("22222222-2222-2222-2222-222222222222")

    @Test
    fun `selection state round trips without exposing opponent selection`() {
        val state = PvpSelectionViewState(
            matchId = matchId,
            format = PvpBattleFormat.SINGLE,
            opponentName = "Opponent",
            ownParty = (1..6).map { index ->
                PvpSelectionPartySlot(
                    pokemonId = UUID(0, index.toLong()),
                    speciesId = "cobblemon:species_$index",
                    heldItemId = if (index == 6) null else "minecraft:item_$index",
                    originalLevel = 40 + index,
                    battleLevel = 50,
                )
            },
            opponentSpeciesIds = listOf("cobblemon:species_7", "cobblemon:species_8", "cobblemon:species_9"),
            selectedPokemonIds = setOf(UUID(0, 1), UUID(0, 2)),
            selectionDeadlineEpochMillis = 123_456L,
            waitingForOpponent = false,
            playerOnLeft = false,
            leftPlayerName = "Opponent",
            rightPlayerName = "Player",
            spectators = listOf(PvpSelectionSpectator(UUID(0, 99), "Viewer")),
        )

        assertEquals(
            PvpSelectionStatePayload(requestId, state),
            roundTrip(PvpSelectionStatePayload.CODEC, PvpSelectionStatePayload(requestId, state)),
        )
    }

    @Test
    fun `selection submit and cancel intents round trip`() {
        val submit = PvpSelectionIntent.Submit(
            requestId,
            matchId,
            listOf(UUID(0, 3), UUID(0, 2), UUID(0, 1)),
        )
        val cancel = PvpSelectionIntent.Cancel(requestId, matchId)
        val retry = PvpSelectionIntent.Retry(requestId, matchId)
        val unready = PvpSelectionIntent.Unready(requestId, matchId)

        assertEquals(PvpSelectionIntentPayload(submit), roundTrip(PvpSelectionIntentPayload.CODEC, PvpSelectionIntentPayload(submit)))
        assertEquals(PvpSelectionIntentPayload(cancel), roundTrip(PvpSelectionIntentPayload.CODEC, PvpSelectionIntentPayload(cancel)))
        assertEquals(PvpSelectionIntentPayload(retry), roundTrip(PvpSelectionIntentPayload.CODEC, PvpSelectionIntentPayload(retry)))
        assertEquals(PvpSelectionIntentPayload(unready), roundTrip(PvpSelectionIntentPayload.CODEC, PvpSelectionIntentPayload(unready)))
    }

    @Test
    fun `rejection payload round trips`() {
        val payload = PvpSelectionRejectedPayload(requestId, matchId, "pvp.selection.invalid")
        assertEquals(payload, roundTrip(PvpSelectionRejectedPayload.CODEC, payload))
        val closed = PvpSelectionClosedPayload(matchId, "pvp.selection.cancelled")
        assertEquals(closed, roundTrip(PvpSelectionClosedPayload.CODEC, closed))
    }

    private fun <T : Any> roundTrip(
        codec: net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf, T>,
        value: T,
    ): T {
        val buffer = RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY)
        codec.encode(buffer, value)
        return codec.decode(buffer)
    }
}
