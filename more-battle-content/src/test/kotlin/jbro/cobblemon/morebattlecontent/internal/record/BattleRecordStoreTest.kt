package jbro.cobblemon.morebattlecontent.internal.record

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BattleRecordStoreTest {
    private val playerId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    private val towerSingles = BattleRecordKey(playerId, BattleRecordCategory("battle_tower", "single"))

    @Test
    fun `wins and losses update current and best streaks`() {
        val store = BattleRecordStore()

        repeat(3) { store.recordOutcome(towerSingles, BattleRecordOutcome.WIN) }
        store.recordOutcome(towerSingles, BattleRecordOutcome.LOSS)
        store.recordOutcome(towerSingles, BattleRecordOutcome.WIN)

        assertEquals(
            BattleRecordStats(
                key = towerSingles,
                totalWins = 4,
                totalLosses = 1,
                currentWinStreak = 1,
                bestWinStreak = 3,
            ),
            store.get(towerSingles),
        )
    }

    @Test
    fun `progress and best metrics remain separate`() {
        val store = BattleRecordStore()
        val currentFloor = BattleRecordMetrics.CURRENT_FLOOR
        val currentRound = BattleRecordMetricId("current_round")
        val highestFloor = BattleRecordMetrics.HIGHEST_FLOOR

        store.setProgressMetric(towerSingles, currentFloor, 7)
        store.setProgressMetric(towerSingles, currentRound, 2)
        store.submitBestMetric(towerSingles, highestFloor, 23)
        store.submitBestMetric(towerSingles, highestFloor, 19)

        val stats = store.get(towerSingles)
        assertEquals(mapOf(currentFloor to 7L, currentRound to 2L), stats.progressMetrics)
        assertEquals(mapOf(highestFloor to 23L), stats.bestMetrics)
    }

    @Test
    fun `admin streak setter raises but never lowers the best streak`() {
        val store = BattleRecordStore()

        val raised = store.setCurrentWinStreak(towerSingles, 12)
        val lowered = store.setCurrentWinStreak(towerSingles, 3)

        assertEquals(12, raised.currentWinStreak)
        assertEquals(12, raised.bestWinStreak)
        assertEquals(3, lowered.currentWinStreak)
        assertEquals(12, lowered.bestWinStreak)
    }

    @Test
    fun `admin floor setter raises but never lowers the highest floor`() {
        val factorySingles = BattleRecordKey(
            playerId,
            BattleRecordCategory("battle_factory", "single_level_50"),
        )
        val store = BattleRecordStore()

        val raised = store.setProgressAndBestMetric(
            factorySingles,
            BattleRecordMetrics.CURRENT_FLOOR,
            BattleRecordMetrics.HIGHEST_FLOOR,
            21,
        )
        val lowered = store.setProgressAndBestMetric(
            factorySingles,
            BattleRecordMetrics.CURRENT_FLOOR,
            BattleRecordMetrics.HIGHEST_FLOOR,
            7,
        )

        assertEquals(21, raised.progressMetrics.getValue(BattleRecordMetrics.CURRENT_FLOOR))
        assertEquals(21, raised.bestMetrics.getValue(BattleRecordMetrics.HIGHEST_FLOOR))
        assertEquals(7, lowered.progressMetrics.getValue(BattleRecordMetrics.CURRENT_FLOOR))
        assertEquals(21, lowered.bestMetrics.getValue(BattleRecordMetrics.HIGHEST_FLOOR))
    }

    @Test
    fun `current reset preserves best streak and floor`() {
        val factorySingles = BattleRecordKey(
            playerId,
            BattleRecordCategory("battle_factory", "single_level_50"),
        )
        val store = BattleRecordStore()
        store.setCurrentWinStreak(towerSingles, 12)
        store.setProgressAndBestMetric(
            factorySingles,
            BattleRecordMetrics.CURRENT_FLOOR,
            BattleRecordMetrics.HIGHEST_FLOOR,
            21,
        )

        val tower = store.resetWinStreak(towerSingles, resetBest = false)
        val factory = store.resetProgressAndBestMetric(
            factorySingles,
            BattleRecordMetrics.CURRENT_FLOOR,
            BattleRecordMetrics.HIGHEST_FLOOR,
            resetBest = false,
        )

        assertEquals(0, tower.currentWinStreak)
        assertEquals(12, tower.bestWinStreak)
        assertEquals(0, factory.progressMetrics.getValue(BattleRecordMetrics.CURRENT_FLOOR))
        assertEquals(21, factory.bestMetrics.getValue(BattleRecordMetrics.HIGHEST_FLOOR))
    }

    @Test
    fun `all reset clears current and best streak and floor`() {
        val factorySingles = BattleRecordKey(
            playerId,
            BattleRecordCategory("battle_factory", "single_level_50"),
        )
        val store = BattleRecordStore()
        store.setCurrentWinStreak(towerSingles, 12)
        store.setProgressAndBestMetric(
            factorySingles,
            BattleRecordMetrics.CURRENT_FLOOR,
            BattleRecordMetrics.HIGHEST_FLOOR,
            21,
        )

        val tower = store.resetWinStreak(towerSingles, resetBest = true)
        val factory = store.resetProgressAndBestMetric(
            factorySingles,
            BattleRecordMetrics.CURRENT_FLOOR,
            BattleRecordMetrics.HIGHEST_FLOOR,
            resetBest = true,
        )

        assertEquals(0, tower.currentWinStreak)
        assertEquals(0, tower.bestWinStreak)
        assertEquals(0, factory.progressMetrics.getValue(BattleRecordMetrics.CURRENT_FLOOR))
        assertEquals(0, factory.bestMetrics.getValue(BattleRecordMetrics.HIGHEST_FLOOR))
    }

    @Test
    fun `completed battle updates outcome progress and best metrics as one record mutation`() {
        val store = BattleRecordStore()

        val result = store.recordCompletedBattle(
            BattleRecordCompletion(
                key = towerSingles,
                outcome = BattleRecordOutcome.WIN,
                progressMetrics = mapOf(
                    BattleRecordMetrics.CURRENT_FLOOR to 4L,
                    BattleRecordMetricId("current_round") to 1L,
                ),
                bestMetrics = mapOf(BattleRecordMetrics.HIGHEST_FLOOR to 12L),
            ),
        )

        assertEquals(1, result.totalWins)
        assertEquals(1, result.currentWinStreak)
        assertEquals(1, result.bestWinStreak)
        assertEquals(4, result.progressMetrics.getValue(BattleRecordMetrics.CURRENT_FLOOR))
        assertEquals(1, result.progressMetrics.getValue(BattleRecordMetricId("current_round")))
        assertEquals(12, result.bestMetrics.getValue(BattleRecordMetrics.HIGHEST_FLOOR))
    }

    @Test
    fun `completed battle leaves the prior record untouched when any counter overflows`() {
        val before = BattleRecordStats(
            key = towerSingles,
            totalWins = Long.MAX_VALUE,
            currentWinStreak = 7,
            bestWinStreak = 7,
            progressMetrics = mapOf(BattleRecordMetrics.CURRENT_FLOOR to 3L),
        )
        val store = BattleRecordStore(listOf(before))

        assertThrows<ArithmeticException> {
            store.recordCompletedBattle(
                BattleRecordCompletion(
                    key = towerSingles,
                    outcome = BattleRecordOutcome.WIN,
                    progressMetrics = mapOf(BattleRecordMetrics.CURRENT_FLOOR to 4L),
                    bestMetrics = mapOf(BattleRecordMetrics.HIGHEST_FLOOR to 20L),
                ),
            )
        }

        assertEquals(before, store.get(towerSingles))
    }

    @Test
    fun `records are isolated by content and format`() {
        val towerDoubles = BattleRecordKey(playerId, BattleRecordCategory("battle_tower", "double"))
        val factorySingles = BattleRecordKey(playerId, BattleRecordCategory("battle_factory", "single"))
        val store = BattleRecordStore()

        store.recordOutcome(towerSingles, BattleRecordOutcome.WIN)
        store.recordOutcome(towerDoubles, BattleRecordOutcome.LOSS)
        store.submitBestMetric(factorySingles, BattleRecordMetricId("highest_floor"), 8)

        assertEquals(1, store.get(towerSingles).totalWins)
        assertEquals(1, store.get(towerDoubles).totalLosses)
        assertEquals(8, store.get(factorySingles).bestMetrics.getValue(BattleRecordMetricId("highest_floor")))
        assertEquals(1, store.all(BattleRecordCategory("battle_tower", "single")).size)
    }

    @Test
    fun `nbt round trip stores only aggregate records`() {
        val store = BattleRecordStore()
        store.recordOutcome(towerSingles, BattleRecordOutcome.WIN)
        store.setProgressMetric(towerSingles, BattleRecordMetricId("current_round"), 4)
        store.submitBestMetric(towerSingles, BattleRecordMetricId("highest_floor"), 17)

        val tag = BattleRecordNbtCodec.encode(store.all())
        val restored = BattleRecordStore(BattleRecordNbtCodec.decode(tag))

        assertEquals(store.all(), restored.all())
        assertFalse(tag.contains("Sessions"))
        assertFalse(tag.contains("Settlements"))
        assertFalse(tag.contains("BattleHistory"))
    }

    @Test
    fun `saved data becomes dirty only after an aggregate mutation`() {
        val data = BattleRecordSavedData()

        data.get(towerSingles)
        assertFalse(data.isDirty)

        data.recordOutcome(towerSingles, BattleRecordOutcome.WIN)
        assertEquals(true, data.isDirty)
    }

    @Test
    fun `saved data marks one completed battle aggregate dirty`() {
        val data = BattleRecordSavedData()

        val after = data.recordCompletedBattle(
            BattleRecordCompletion(
                key = towerSingles,
                outcome = BattleRecordOutcome.LOSS,
                bestMetrics = mapOf(BattleRecordMetrics.BEST_SCORE to 900L),
            ),
        )

        assertEquals(1, after.totalLosses)
        assertEquals(900, after.bestMetrics.getValue(BattleRecordMetrics.BEST_SCORE))
        assertEquals(true, data.isDirty)
    }

    @Test
    fun `unsupported saved data disables records without becoming dirty or overwriting it`() {
        val unsupported = net.minecraft.nbt.CompoundTag().also { tag ->
            tag.putInt("SchemaVersion", 999)
        }

        val data = BattleRecordSavedData.loadForTest(unsupported)
        val after = data.recordOutcome(towerSingles, BattleRecordOutcome.WIN)

        assertFalse(data.isAvailable)
        assertFalse(data.isDirty)
        assertEquals(0, after.totalWins)
        assertEquals(999, data.preservedTagForTest().getInt("SchemaVersion"))
    }

    @Test
    fun `supported schema with missing records is preserved as corrupt data`() {
        val missingRecords = net.minecraft.nbt.CompoundTag().also { tag ->
            tag.putInt("SchemaVersion", 1)
        }

        val data = BattleRecordSavedData.loadForTest(missingRecords)

        assertFalse(data.isAvailable)
        assertFalse(data.isDirty)
        assertFalse(data.preservedTagForTest().contains("Records"))
    }

    @Test
    fun `identifiers and metric values reject invalid data`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            BattleRecordCategory("Battle Tower", "single")
        }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            BattleRecordMetricId("Highest Floor")
        }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            BattleRecordStore().setProgressMetric(towerSingles, BattleRecordMetricId("current_round"), -1)
        }
    }
}
