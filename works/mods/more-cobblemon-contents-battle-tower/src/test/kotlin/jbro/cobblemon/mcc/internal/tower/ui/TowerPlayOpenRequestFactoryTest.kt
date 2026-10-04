package jbro.cobblemon.mcc.internal.tower.ui

import java.util.UUID
import jbro.cobblemon.mcc.internal.tower.TowerMode
import jbro.cobblemon.mcc.internal.tower.TowerTrack
import jbro.cobblemon.mcc.internal.tower.TowerBattleFormat
import jbro.cobblemon.mcc.internal.tower.TowerProgress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TowerPlayOpenRequestFactoryTest {
    private val playerId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

    @Test
    fun `factory loads party both format progresses and BP without mixing formats`() {
        val requestedTracks = mutableListOf<TowerTrack>()
        val party = listOf(
            TowerPlayPartySlot(0, UUID(0, 1), "cobblemon:bulbasaur", null, 65, 50),
        )
        val factory = TowerPlayOpenRequestFactory(
            partySource = { id -> assertEquals(playerId, id); party },
            progressSource = { id, track ->
                assertEquals(playerId, id)
                requestedTracks += track
                when {
                    track.mode == TowerMode.NORMAL -> TowerProgress.initial(track.format, TowerMode.NORMAL)
                    track.format == TowerBattleFormat.SINGLE -> TowerProgress(track.format, 6, 9)
                    else -> TowerProgress(track.format, 14, 20)
                }
            },
            bpSource = { id -> assertEquals(playerId, id); 91 },
        )

        val request = factory.create(playerId, TowerBattleFormat.DOUBLE)

        assertEquals(party, request.party)
        assertEquals(TowerBattleFormat.DOUBLE, request.initialFormat)
        assertEquals(6, request.progressByTrack.getValue(TowerTrack(TowerBattleFormat.SINGLE, TowerMode.ENDLESS)).currentWinStreak)
        assertEquals(20, request.progressByTrack.getValue(TowerTrack(TowerBattleFormat.DOUBLE, TowerMode.ENDLESS)).bestWinStreak)
        assertEquals(TowerTrack.entries, requestedTracks)
        // The 20 wins reached before Normal existed keep Endless open, so the screen opens there.
        assertEquals(TowerMode.ENDLESS, request.initialMode)
        assertEquals(91, request.bpBalance)
    }
}
