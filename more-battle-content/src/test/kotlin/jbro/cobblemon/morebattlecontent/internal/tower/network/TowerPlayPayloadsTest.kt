package jbro.cobblemon.morebattlecontent.internal.tower.network

import io.netty.buffer.Unpooled
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.rules.MajorBattleMechanic
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleFormat
import jbro.cobblemon.morebattlecontent.internal.tower.ui.TowerPlayIntent
import jbro.cobblemon.morebattlecontent.internal.tower.ui.TowerPlayMutationResult
import jbro.cobblemon.morebattlecontent.internal.tower.ui.TowerPlayPartySlot
import jbro.cobblemon.morebattlecontent.internal.tower.ui.TowerPlayPhase
import jbro.cobblemon.morebattlecontent.internal.tower.ui.TowerPlayViewState
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TowerPlayPayloadsTest {
    private val requestId = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val contextId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

    @Test
    fun `state payload round trips open and accepted response`() {
        val open = TowerPlayStatePayload(null, state())
        val accepted = TowerPlayStatePayload(requestId, state(revision = 8))

        assertEquals(open, roundTrip(TowerPlayStatePayload.CODEC, open))
        assertEquals(accepted, roundTrip(TowerPlayStatePayload.CODEC, accepted))
    }

    @Test
    fun `state payload preserves pokemon selection order`() {
        val selectedInClickOrder = listOf(UUID(0, 3), UUID(0, 1), UUID(0, 2))
        val payload = TowerPlayStatePayload(null, state(selectedPokemonIds = selectedInClickOrder))

        val decoded = roundTrip(TowerPlayStatePayload.CODEC, payload)

        assertEquals(selectedInClickOrder, decoded.state.selectedPokemonOrder)
    }

    @Test
    fun `every mutation intent round trips`() {
        val intents = listOf(
            TowerPlayIntent.ToggleSelection(requestId, contextId, 7, UUID(0, 1)),
            TowerPlayIntent.ChangeFormat(requestId, contextId, 7, TowerBattleFormat.DOUBLE),
            TowerPlayIntent.ChangeMechanic(requestId, contextId, 7, MajorBattleMechanic.DYNAMAX),
            TowerPlayIntent.LockTeam(requestId, contextId, 7),
            TowerPlayIntent.Start(requestId, contextId, 7),
            TowerPlayIntent.Resume(requestId, contextId, 7),
            TowerPlayIntent.Abandon(requestId, contextId, 7),
        )

        intents.forEach { intent ->
            val payload = TowerPlayIntentPayload(intent)
            assertEquals(payload, roundTrip(TowerPlayIntentPayload.CODEC, payload))
        }
    }

    @Test
    fun `rejection payload round trips field errors`() {
        val rejected = TowerPlayMutationResult.Rejected(
            requestId,
            7,
            "screen.cobblemon_more_battle_content.tower.error.team_invalid",
            mapOf("selection" to "screen.cobblemon_more_battle_content.tower.error.selection_size"),
        )
        val payload = TowerPlayRejectedPayload(rejected)

        assertEquals(payload, roundTrip(TowerPlayRejectedPayload.CODEC, payload))
    }

    private fun state(
        revision: Long = 7,
        selectedPokemonIds: Collection<UUID> = setOf(UUID(0, 1)),
    ) = TowerPlayViewState(
        entryContextId = contextId,
        revision = revision,
        format = TowerBattleFormat.SINGLE,
        phase = TowerPlayPhase.SELECTING,
        party = (1..6).map { index ->
            TowerPlayPartySlot(
                index - 1,
                UUID(0, index.toLong()),
                "cobblemon:species_$index",
                if (index == 6) null else "minecraft:item_$index",
                40 + index,
                minOf(40 + index, 50),
            )
        },
        selectedPokemonIds = selectedPokemonIds,
        currentWinStreak = 7,
        bestWinStreak = 12,
        bpBalance = 123,
        errorKeys = listOf("screen.cobblemon_more_battle_content.tower.warning.example"),
        selectedMechanic = MajorBattleMechanic.DYNAMAX,
        mechanicLocked = true,
        legendaryClassAllowed = true,
        legendaryClassLocked = true,
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
