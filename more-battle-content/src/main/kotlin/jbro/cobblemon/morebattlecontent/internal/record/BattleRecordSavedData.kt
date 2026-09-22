package jbro.cobblemon.morebattlecontent.internal.record

import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.persistence.loadSavedDataSafely
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.level.saveddata.SavedData

internal class BattleRecordSavedData(
    private val store: BattleRecordStore = BattleRecordStore(),
    val isAvailable: Boolean = true,
    private val preservedTag: CompoundTag? = null,
) : SavedData() {
    fun get(key: BattleRecordKey): BattleRecordStats = store.get(key)

    fun all(category: BattleRecordCategory? = null): List<BattleRecordStats> = store.all(category)

    fun recordOutcome(key: BattleRecordKey, outcome: BattleRecordOutcome): BattleRecordStats =
        mutate { store.recordOutcome(key, outcome).also { setDirty() } }

    fun recordCompletedBattle(completion: BattleRecordCompletion): BattleRecordStats =
        mutate { store.recordCompletedBattle(completion).also { setDirty() } }

    fun recordCompletedBattles(completions: List<BattleRecordCompletion>): List<BattleRecordStats> {
        require(completions.isNotEmpty()) { "At least one battle record completion is required" }
        requireAvailable()
        return store.recordCompletedBattles(completions).also { setDirty() }
    }

    fun setProgressMetric(
        key: BattleRecordKey,
        metricId: BattleRecordMetricId,
        value: Long,
    ): BattleRecordStats = mutate { store.setProgressMetric(key, metricId, value).also { setDirty() } }

    fun setCurrentWinStreak(key: BattleRecordKey, value: Int): BattleRecordStats =
        mutate { store.setCurrentWinStreak(key, value).also { setDirty() } }

    fun resetWinStreak(key: BattleRecordKey, resetBest: Boolean): BattleRecordStats =
        mutate { store.resetWinStreak(key, resetBest).also { setDirty() } }

    fun setProgressAndBestMetric(
        key: BattleRecordKey,
        progressMetricId: BattleRecordMetricId,
        bestMetricId: BattleRecordMetricId,
        value: Long,
    ): BattleRecordStats = mutate {
        store.setProgressAndBestMetric(key, progressMetricId, bestMetricId, value).also { setDirty() }
    }

    fun resetProgressAndBestMetric(
        key: BattleRecordKey,
        progressMetricId: BattleRecordMetricId,
        bestMetricId: BattleRecordMetricId,
        resetBest: Boolean,
    ): BattleRecordStats = mutate {
        store.resetProgressAndBestMetric(key, progressMetricId, bestMetricId, resetBest).also { setDirty() }
    }

    fun submitBestMetric(
        key: BattleRecordKey,
        metricId: BattleRecordMetricId,
        candidate: Long,
    ): BattleRecordStats {
        requireAvailable()
        val before = store.get(key)
        val after = store.submitBestMetric(key, metricId, candidate)
        if (after != before) setDirty()
        return after
    }

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag =
        preservedTag?.copy() ?: BattleRecordNbtCodec.encode(store.all(), tag)

    internal fun preservedTagForTest(): CompoundTag = checkNotNull(preservedTag).copy()

    private inline fun mutate(mutation: () -> BattleRecordStats): BattleRecordStats {
        requireAvailable()
        return mutation()
    }

    private fun requireAvailable() {
        check(isAvailable) { "Battle record storage is unavailable" }
    }

    companion object {
        const val FILE_ID = "cobblemon_more_battle_content_records"
        val factory = Factory(::BattleRecordSavedData, ::load, DataFixTypes.LEVEL)

        private fun load(tag: CompoundTag, registries: HolderLookup.Provider): BattleRecordSavedData = loadSafely(tag, true)

        internal fun loadForTest(tag: CompoundTag): BattleRecordSavedData = loadSafely(tag, false)

        private fun loadSafely(tag: CompoundTag, logFailure: Boolean): BattleRecordSavedData =
            loadSavedDataSafely(
                load = { BattleRecordSavedData(BattleRecordStore(BattleRecordNbtCodec.decode(tag))) },
                reportFailure = { failure ->
                    if (logFailure) {
                        MoreBattleContent.LOGGER.error(
                            "Battle record data could not be loaded; record writes are disabled so the original file is preserved",
                            failure,
                        )
                    }
                },
                unavailable = { BattleRecordSavedData(isAvailable = false, preservedTag = tag.copy()) },
            )
    }
}

internal object BattleRecordService {
    fun isAvailable(server: MinecraftServer): Boolean = data(server).isAvailable

    fun get(server: MinecraftServer, key: BattleRecordKey): BattleRecordStats = data(server).get(key)

    fun all(server: MinecraftServer, category: BattleRecordCategory): List<BattleRecordStats> =
        data(server).all(category)

    fun recordOutcome(
        server: MinecraftServer,
        key: BattleRecordKey,
        outcome: BattleRecordOutcome,
    ): BattleRecordStats = data(server).recordOutcome(key, outcome)

    fun recordCompletedBattle(
        server: MinecraftServer,
        completion: BattleRecordCompletion,
    ): BattleRecordStats = data(server).recordCompletedBattle(completion)

    fun recordCompletedBattles(
        server: MinecraftServer,
        completions: List<BattleRecordCompletion>,
    ): List<BattleRecordStats> = data(server).recordCompletedBattles(completions)

    fun setProgressMetric(
        server: MinecraftServer,
        key: BattleRecordKey,
        metricId: BattleRecordMetricId,
        value: Long,
    ): BattleRecordStats = data(server).setProgressMetric(key, metricId, value)

    fun setCurrentWinStreak(
        server: MinecraftServer,
        key: BattleRecordKey,
        value: Int,
    ): BattleRecordStats = data(server).setCurrentWinStreak(key, value)

    fun resetWinStreak(
        server: MinecraftServer,
        key: BattleRecordKey,
        resetBest: Boolean,
    ): BattleRecordStats = data(server).resetWinStreak(key, resetBest)

    fun setProgressAndBestMetric(
        server: MinecraftServer,
        key: BattleRecordKey,
        progressMetricId: BattleRecordMetricId,
        bestMetricId: BattleRecordMetricId,
        value: Long,
    ): BattleRecordStats = data(server).setProgressAndBestMetric(
        key,
        progressMetricId,
        bestMetricId,
        value,
    )

    fun resetProgressAndBestMetric(
        server: MinecraftServer,
        key: BattleRecordKey,
        progressMetricId: BattleRecordMetricId,
        bestMetricId: BattleRecordMetricId,
        resetBest: Boolean,
    ): BattleRecordStats = data(server).resetProgressAndBestMetric(
        key,
        progressMetricId,
        bestMetricId,
        resetBest,
    )

    fun submitBestMetric(
        server: MinecraftServer,
        key: BattleRecordKey,
        metricId: BattleRecordMetricId,
        candidate: Long,
    ): BattleRecordStats = data(server).submitBestMetric(key, metricId, candidate)

    private fun data(server: MinecraftServer): BattleRecordSavedData =
        server.overworld().dataStorage.computeIfAbsent(BattleRecordSavedData.factory, BattleRecordSavedData.FILE_ID)
}
