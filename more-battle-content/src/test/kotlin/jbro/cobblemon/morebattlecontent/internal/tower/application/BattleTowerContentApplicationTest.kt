package jbro.cobblemon.morebattlecontent.internal.tower.application

import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.application.BattleApplicationRequestContext
import jbro.cobblemon.morebattlecontent.internal.application.BattleContentPhase
import jbro.cobblemon.morebattlecontent.internal.application.BattleEntryPoint
import jbro.cobblemon.morebattlecontent.internal.application.BattleFormatId
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleFormat
import jbro.cobblemon.morebattlecontent.internal.tower.TowerProgress
import jbro.cobblemon.morebattlecontent.internal.tower.ui.TowerPlayPhase
import jbro.cobblemon.morebattlecontent.internal.tower.ui.TowerPlayViewState
import jbro.cobblemon.morebattlecontent.internal.tower.ui.TowerSessionAbandonResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BattleTowerContentApplicationTest {
    private val playerId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    private val context = BattleApplicationRequestContext(
        UUID.fromString("11111111-2222-3333-4444-555555555555"),
        playerId,
        BattleEntryPoint.COMMAND,
    )

    @Test
    fun `descriptor exposes only the supported tower formats`() {
        val application = BattleTowerContentApplication(FakeBackend())

        assertEquals("battle_tower", application.descriptor.contentId.value)
        assertEquals(listOf("double", "single"), application.descriptor.formats.map { it.value })
    }

    @Test
    fun `start opens the requested format and reports the server session`() {
        val backend = FakeBackend()
        val application = BattleTowerContentApplication(backend)

        val status = application.start(context, BattleFormatId("double"))

        assertEquals(listOf(TowerBattleFormat.DOUBLE), backend.opens)
        assertEquals(BattleContentPhase.PREPARING, status.phase)
        assertEquals(BattleFormatId("double"), status.formatId)
        assertEquals(
            mapOf(
                "single_current_win_streak" to 0L,
                "single_best_win_streak" to 0L,
                "single_bp_per_win" to 1L,
                "double_current_win_streak" to 0L,
                "double_best_win_streak" to 0L,
                "double_bp_per_win" to 1L,
            ),
            status.progress,
        )
    }

    @Test
    fun `status without a session still reports persisted progress for both formats`() {
        val application = BattleTowerContentApplication(FakeBackend())

        val status = application.status(context)

        assertEquals(BattleContentPhase.AVAILABLE, status.phase)
        assertEquals(null, status.formatId)
        assertEquals(
            setOf("single_current_win_streak", "double_current_win_streak"),
            status.progress.keys.filter { it.endsWith("current_win_streak") }.toSet(),
        )
    }

    @Test
    fun `resume reopens the active server session without changing its format`() {
        val backend = FakeBackend().apply { state = state(TowerBattleFormat.DOUBLE, TowerPlayPhase.ACTIVE) }
        val application = BattleTowerContentApplication(backend)

        val status = application.resume(context)

        assertEquals(listOf(TowerBattleFormat.DOUBLE), backend.opens)
        assertEquals(BattleContentPhase.ACTIVE, status.phase)
        assertEquals(BattleFormatId("double"), status.formatId)
    }

    @Test
    fun `active abandon delegates one server forfeit and remains active until battle completion`() {
        val backend = FakeBackend().apply { state = state(TowerBattleFormat.SINGLE, TowerPlayPhase.ACTIVE) }
        val application = BattleTowerContentApplication(backend)

        val status = application.abandon(context)

        assertEquals(1, backend.abandons)
        assertEquals(BattleContentPhase.ACTIVE, status.phase)
    }

    @Test
    fun `preparation abandon closes the in memory session`() {
        val backend = FakeBackend().apply { state = state(TowerBattleFormat.SINGLE, TowerPlayPhase.TEAM_LOCKED) }
        val application = BattleTowerContentApplication(backend)

        val status = application.abandon(context)

        assertEquals(BattleContentPhase.AVAILABLE, status.phase)
        assertEquals(null, status.formatId)
    }

    private inner class FakeBackend : BattleTowerApplicationBackend {
        var state: TowerPlayViewState? = null
        val opens = ArrayList<TowerBattleFormat>()
        var abandons = 0

        override fun current(playerId: UUID): TowerPlayViewState? = state

        override fun progress(playerId: UUID): Map<TowerBattleFormat, TowerProgress> =
            TowerBattleFormat.entries.associateWith(TowerProgress::initial)

        override fun open(playerId: UUID, format: TowerBattleFormat): Boolean {
            opens += format
            if (state == null) state = state(format, TowerPlayPhase.SELECTING)
            return true
        }

        override fun abandon(playerId: UUID): TowerSessionAbandonResult {
            abandons++
            return if (state?.phase == TowerPlayPhase.ACTIVE) {
                TowerSessionAbandonResult.ForfeitRequested(UUID(0, 99))
            } else {
                state = null
                TowerSessionAbandonResult.SessionClosed
            }
        }
    }

    private fun state(format: TowerBattleFormat, phase: TowerPlayPhase) = TowerPlayViewState(
        entryContextId = UUID.fromString("22222222-3333-4444-5555-666666666666"),
        revision = 0,
        format = format,
        phase = phase,
        party = emptyList(),
        selectedPokemonIds = emptySet(),
        currentWinStreak = 0,
        bestWinStreak = 0,
        bpBalance = 0,
        errorKeys = emptyList(),
    )
}
