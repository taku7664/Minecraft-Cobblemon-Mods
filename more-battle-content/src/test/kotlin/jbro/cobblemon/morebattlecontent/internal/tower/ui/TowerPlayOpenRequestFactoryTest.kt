package jbro.cobblemon.morebattlecontent.internal.tower.ui

import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleFormat
import jbro.cobblemon.morebattlecontent.internal.tower.TowerProgress
import jbro.cobblemon.morebattlecontent.internal.tower.TowerRank
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
                    TowerProgress(format, TowerRank.RANK_4, 1)
                } else {
                    TowerProgress(format, TowerRank.RANK_8, 3)
                }
            },
            bpSource = { id -> assertEquals(playerId, id); 91 },
        )

        val request = factory.create(playerId, TowerBattleFormat.DOUBLE)

        assertEquals(party, request.party)
        assertEquals(TowerBattleFormat.DOUBLE, request.initialFormat)
        assertEquals(TowerRank.RANK_4, request.progressByFormat.getValue(TowerBattleFormat.SINGLE).rank)
        assertEquals(TowerRank.RANK_8, request.progressByFormat.getValue(TowerBattleFormat.DOUBLE).rank)
        assertEquals(TowerBattleFormat.entries, requestedFormats)
        assertEquals(91, request.bpBalance)
    }
}
