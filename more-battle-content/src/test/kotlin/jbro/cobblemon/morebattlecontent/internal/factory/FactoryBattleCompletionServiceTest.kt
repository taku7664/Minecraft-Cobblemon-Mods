package jbro.cobblemon.morebattlecontent.internal.factory

import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordCompletion
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordStats
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FactoryBattleCompletionServiceTest {
    private val playerId = UUID.randomUUID()
    private val battleId = UUID.randomUUID()

    @Test
    fun `victory records progress once then exposes a swap decision`() {
        val completions = ArrayList<BattleRecordCompletion>()
        val rewards = ArrayList<Pair<UUID, UUID>>()
        val service = FactoryBattleCompletionService(recordService(completions)) { rewardedPlayerId, rewardedBattleId ->
            rewards += rewardedPlayerId to rewardedBattleId
        }
        val session = session()
        session.beginBattle(battleId)

        val result = service.completeVictory(
            playerId = playerId,
            session = session,
            battleId = battleId,
            opponentSets = opponent(),
            observations = emptyMap(),
        )

        assertTrue(result is FactoryBattleCompletionResult.Victory)
        assertEquals(1, completions.size)
        assertEquals("single_level_50", completions.single().key.category.formatId)
        assertEquals(1L, completions.single().progressMetrics.values.single())
        assertEquals(1, session.wins)
        assertEquals(FactoryRunPhase.SWAP_DECISION, session.phase)
        assertEquals(listOf(playerId to battleId), rewards)

        val duplicate = service.completeVictory(playerId, session, battleId, opponent(), emptyMap())
        assertTrue(duplicate is FactoryBattleCompletionResult.NoActiveBattle)
        assertEquals(1, completions.size)
        assertEquals(listOf(playerId to battleId), rewards)
    }

    @Test
    fun `record failure leaves active battle and run progress unchanged for safe retry`() {
        var healed = 0
        val session = session { healed++ }
        session.beginBattle(battleId)
        val service = FactoryBattleCompletionService(
            FactoryBattleRecordService { throw IllegalStateException("storage unavailable") },
        )

        assertThrows<IllegalStateException> {
            service.completeVictory(playerId, session, battleId, opponent(), emptyMap())
        }

        assertEquals(1, healed)
        assertEquals(0, session.wins)
        assertEquals(FactoryRunPhase.IN_BATTLE, session.phase)
        assertEquals(battleId, session.activeBattleId)
    }

    @Test
    fun `loss resets current progress records best cleared battle and completes run`() {
        val completions = ArrayList<BattleRecordCompletion>()
        val rewards = ArrayList<Pair<UUID, UUID>>()
        val service = FactoryBattleCompletionService(recordService(completions)) { rewardedPlayerId, rewardedBattleId ->
            rewards += rewardedPlayerId to rewardedBattleId
        }
        val session = session()
        repeat(2) { index ->
            val wonBattle = UUID(0, index.toLong() + 1)
            session.beginBattle(wonBattle)
            service.completeVictory(playerId, session, wonBattle, opponent(index * 10), emptyMap())
            session.keepTeam()
        }
        session.beginBattle(battleId)

        val result = service.completeLoss(playerId, session, battleId)

        assertTrue(result is FactoryBattleCompletionResult.Loss)
        assertEquals(FactoryRunPhase.COMPLETE, session.phase)
        assertEquals(3, completions.size)
        val loss = completions.last()
        assertEquals(0L, loss.progressMetrics.values.single())
        assertEquals(2L, loss.bestMetrics.values.single())
        assertEquals(2, rewards.size)
        assertFalse(rewards.any { it.second == battleId })
    }

    @Test
    fun `no contest releases exact active battle without changing records or wins`() {
        val completions = ArrayList<BattleRecordCompletion>()
        val service = FactoryBattleCompletionService(recordService(completions))
        val session = session()
        session.beginBattle(battleId)

        assertTrue(service.cancel(session, battleId) is FactoryBattleCompletionResult.Cancelled)
        assertEquals(FactoryRunPhase.READY, session.phase)
        assertEquals(0, session.wins)
        assertTrue(completions.isEmpty())
        assertFalse(service.cancel(session, battleId) is FactoryBattleCompletionResult.Cancelled)
    }

    private fun recordService(completions: MutableList<BattleRecordCompletion>) = FactoryBattleRecordService { completion ->
        completions += completion
        BattleRecordStats(completion.key)
    }

    private fun session(heal: (FactoryRentalTeam) -> Unit = {}) = FactoryRunSession(
        UUID.randomUUID(),
        FactoryRentalDraft((1..6).map(::rental)).select(listOf("set1", "set2", "set3"), FactoryBattleFormat.SINGLE),
        FactoryLevelMode.LEVEL_50,
        heal,
    )

    private fun opponent(offset: Int = 0): Map<UUID, FactoryRentalSet> = (7..9).associate { index ->
        UUID(1, (index + offset).toLong()) to rental(index + offset)
    }

    private fun rental(index: Int) = FactoryRentalSet(
        setId = "set$index",
        speciesId = "cobblemon:species$index",
        moveIds = listOf("cobblemon:move$index"),
        abilityId = "cobblemon:ability$index",
        heldItemId = "cobblemon:item$index",
        natureId = "cobblemon:nature$index",
        ivs = FactoryStatSpread(0, 0, 0, 0, 0, 0),
        evs = FactoryStatSpread(0, 0, 0, 0, 0, 0),
    )
}
