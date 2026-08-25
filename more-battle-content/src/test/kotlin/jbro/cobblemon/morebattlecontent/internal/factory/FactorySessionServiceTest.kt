package jbro.cobblemon.morebattlecontent.internal.factory

import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordStats
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FactorySessionServiceTest {
    private val playerId = UUID.randomUUID()
    private val battleId = UUID.randomUUID()

    @Test
    fun `owns one run per player and routes exact runtime callbacks`() {
        var records = 0
        val service = service {
            records++
            BattleRecordStats(it.key)
        }
        val started = service.start(playerId, team(), FactoryLevelMode.OPEN_LEVEL) {}
        assertTrue(started is FactorySessionStartResult.Started)
        assertEquals(
            FactorySessionStartResult.AlreadyActive,
            service.start(playerId, team(), FactoryLevelMode.LEVEL_50) {},
        )

        val launch = service.beginBattle(playerId, opponent(), "trainer.factory", 3, strategyBrief())
        assertEquals(FactoryBattleLaunchResult.Started(battleId), launch)
        val runId = (started as FactorySessionStartResult.Started).runId

        assertTrue(
            service.completeVictory(
                playerId,
                UUID.randomUUID(),
                battleId,
                opponent(),
                emptyMap(),
            ) is FactorySessionCompletionResult.StaleRun,
        )
        assertEquals(0, records)
        assertTrue(
            service.completeVictory(playerId, runId, battleId, opponent(), emptyMap())
                is FactorySessionCompletionResult.Completed,
        )
        assertEquals(1, records)
        assertEquals(FactoryRunPhase.SWAP_DECISION, service.snapshot(playerId)?.phase)
        assertEquals(FactoryLevelMode.OPEN_LEVEL, service.snapshot(playerId)?.levelMode)
        assertEquals(3, service.snapshot(playerId)?.swapOffers?.size)
        assertEquals(emptySet<String>(), service.snapshot(playerId)?.swapOffers?.first()?.revealedMoveIds)
    }

    @Test
    fun `unavailable launch preserves ready run and no contest permits retry`() {
        val unavailable = FactorySessionService(
            runBattles = FactoryRunBattleService(FactoryBattleLauncher { FactoryBattleLaunchResult.Unavailable }),
            completions = completionService(),
        )
        val started = unavailable.start(playerId, team(), FactoryLevelMode.LEVEL_50) {} as FactorySessionStartResult.Started

        assertEquals(
            FactoryBattleLaunchResult.Unavailable,
            unavailable.beginBattle(playerId, opponent(), "trainer.factory", 3, strategyBrief()),
        )
        assertEquals(FactoryRunPhase.READY, unavailable.snapshot(playerId)?.phase)

        val active = service()
        val activeRun = active.start(playerId, team(), FactoryLevelMode.LEVEL_50) {} as FactorySessionStartResult.Started
        active.beginBattle(playerId, opponent(), "trainer.factory", 3, strategyBrief())
        assertTrue(
            active.cancelBattle(playerId, activeRun.runId, battleId)
                is FactorySessionCompletionResult.Completed,
        )
        assertEquals(FactoryRunPhase.READY, active.snapshot(playerId)?.phase)
        assertEquals(started.runId, unavailable.snapshot(playerId)?.runId)
    }

    @Test
    fun `disconnect during an active battle records a loss before closing the run`() {
        val outcomes = ArrayList<BattleRecordOutcome>()
        val active = service { completion ->
            outcomes += completion.outcome
            BattleRecordStats(completion.key)
        }
        active.start(playerId, team(), FactoryLevelMode.LEVEL_50) {}
        active.beginBattle(playerId, opponent(), "trainer.factory", 3, strategyBrief())

        assertTrue(active.disconnect(playerId))

        assertEquals(listOf(BattleRecordOutcome.LOSS), outcomes)
        assertEquals(null, active.snapshot(playerId))
    }

    @Test
    fun `admin floor setter updates an idle run but rejects an active battle`() {
        val active = service()
        active.start(playerId, team(), FactoryLevelMode.LEVEL_50) {}

        assertTrue(active.adminSetWins(playerId, 14))
        assertEquals(14, active.snapshot(playerId)?.wins)

        active.beginBattle(playerId, opponent(), "trainer.factory", 3, strategyBrief())

        assertEquals(false, active.adminSetWins(playerId, 21))
        assertEquals(14, active.snapshot(playerId)?.wins)
    }

    private fun service(
        sink: FactoryBattleRecordSink = FactoryBattleRecordSink { BattleRecordStats(it.key) },
    ) = FactorySessionService(
        runBattles = FactoryRunBattleService(
            FactoryBattleLauncher { FactoryBattleLaunchResult.Started(battleId) },
        ),
        completions = completionService(sink),
    )

    private fun completionService(
        sink: FactoryBattleRecordSink = FactoryBattleRecordSink { BattleRecordStats(it.key) },
    ) = FactoryBattleCompletionService(FactoryBattleRecordService(sink))

    private fun team() = FactoryRentalDraft((1..6).map(::rental))
        .select(listOf("set1", "set2", "set3"), FactoryBattleFormat.SINGLE)

    private fun opponent() = (7..9).associate { index -> UUID(1, index.toLong()) to rental(index) }

    private fun strategyBrief() = jbro.cobblemon.morebattlecontent.api.ai.BattleStrategyBrief(
        "mbc:test_factory",
        "strategy.mbc.test_factory.name",
        "strategy.mbc.test_factory.description",
        "Apply balanced pressure.",
        setOf(jbro.cobblemon.morebattlecontent.api.ai.BattleStrategyObjective.BALANCED_PRESSURE),
    )

    private fun rental(index: Int) = FactoryRentalSet(
        "set$index",
        "cobblemon:species$index",
        listOf("cobblemon:move$index"),
        "cobblemon:ability$index",
        "cobblemon:item$index",
        "cobblemon:nature$index",
        FactoryStatSpread(0, 0, 0, 0, 0, 0),
        FactoryStatSpread(0, 0, 0, 0, 0, 0),
    )
}
