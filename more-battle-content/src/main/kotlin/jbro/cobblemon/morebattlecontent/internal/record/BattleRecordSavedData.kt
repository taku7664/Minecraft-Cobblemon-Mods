package jbro.cobblemon.morebattlecontent.internal.record

import jbro.cobblemon.morebattlecontent.MoreBattleContent
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
        ifAvailable(key) { store.recordOutcome(key, outcome).also { setDirty() } }

    fun recordCompletedBattle(completion: BattleRecordCompletion): BattleRecordStats =
        ifAvailable(completion.key) { store.recordCompletedBattle(completion).also { setDirty() } }

    fun recordCompletedBattles(completions: List<BattleRecordCompletion>): List<BattleRecordStats> {
        require(completions.isNotEmpty()) { "At least one battle record completion is required" }
        if (!isAvailable) return completions.map { store.get(it.key) }
        return store.recordCompletedBattles(completions).also { setDirty() }
    }

    fun setProgressMetric(
        key: BattleRecordKey,
        metricId: BattleRecordMetricId,
        value: Long,
    ): BattleRecordStats = ifAvailable(key) { store.setProgressMetric(key, metricId, value).also { setDirty() } }

    fun submitBestMetric(
        key: BattleRecordKey,
        metricId: BattleRecordMetricId,
        candidate: Long,
    ): BattleRecordStats {
        if (!isAvailable) return store.get(key)
        val before = store.get(key)
        val after = store.submitBestMetric(key, metricId, candidate)
        if (after != before) setDirty()
        return after
    }

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag =
        preservedTag?.copy() ?: BattleRecordNbtCodec.encode(store.all(), tag)

    internal fun preservedTagForTest(): CompoundTag = checkNotNull(preservedTag).copy()

    private inline fun ifAvailable(
        key: BattleRecordKey,
        mutation: () -> BattleRecordStats,
    ): BattleRecordStats = if (isAvailable) mutation() else store.get(key)

    companion object {
        const val FILE_ID = "cobblemon_more_battle_content_records"
        val factory = Factory(::BattleRecordSavedData, ::load, DataFixTypes.LEVEL)

        private fun load(tag: CompoundTag, registries: HolderLookup.Provider): BattleRecordSavedData = loadSafely(tag, true)

        internal fun loadForTest(tag: CompoundTag): BattleRecordSavedData = loadSafely(tag, false)

        private fun loadSafely(tag: CompoundTag, logFailure: Boolean): BattleRecordSavedData = try {
            BattleRecordSavedData(BattleRecordStore(BattleRecordNbtCodec.decode(tag)))
        } catch (exception: RuntimeException) {
            if (logFailure) {
                MoreBattleContent.LOGGER.error(
                    "Battle record data could not be loaded; record writes are disabled so the original file is preserved",
                    exception,
                )
            }
            BattleRecordSavedData(isAvailable = false, preservedTag = tag.copy())
        }
    }
}

internal object BattleRecordService {
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

    fun submitBestMetric(
        server: MinecraftServer,
        key: BattleRecordKey,
        metricId: BattleRecordMetricId,
        candidate: Long,
    ): BattleRecordStats = data(server).submitBestMetric(key, metricId, candidate)

    private fun data(server: MinecraftServer): BattleRecordSavedData =
        server.overworld().dataStorage.computeIfAbsent(BattleRecordSavedData.factory, BattleRecordSavedData.FILE_ID)
}
