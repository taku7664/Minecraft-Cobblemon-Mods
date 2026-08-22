package jbro.cobblemon.morebattlecontent.internal.tower.ui

import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleFormat
import jbro.cobblemon.morebattlecontent.internal.tower.TowerProgress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TowerPlayOpenRequestFactoryTest {
    private val playerId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

    @Test
    fun `factory loads party both format progresses and BP without mixing formats`() {
        val requestedFormats = mutableListOf<TowerBattleFormat>()
        val party = listOf(
            TowerPlayPartySlot(0, UUID(0, 1), "cobblemon:bulbasaur", null, 65, 50),
        )
        val factory = TowerPlayOpenRequestFactory(
            partySource = { id -> assertEquals(playerId, id); party },
            progressSource = { id, format ->
                assertEquals(playerId, id)
                requestedFormats += format
                if (format == TowerBattleFormat.SINGLE) {
                    TowerProgress(format, 6, 9)
                } else {
                    TowerProgress(format, 14, 20)
                }
            },
            bpSource = { id -> assertEquals(playerId, id); 91 },
        )

        val request = factory.create(playerId, TowerBattleFormat.DOUBLE)

        assertEquals(party, request.party)
        assertEquals(TowerBattleFormat.DOUBLE, request.initialFormat)
        assertEquals(6, request.progressByFormat.getValue(TowerBattleFormat.SINGLE).currentWinStreak)
        assertEquals(20, request.progressByFormat.getValue(TowerBattleFormat.DOUBLE).bestWinStreak)
        assertEquals(TowerBattleFormat.entries, requestedFormats)
        assertEquals(91, request.bpBalance)
    }
}
