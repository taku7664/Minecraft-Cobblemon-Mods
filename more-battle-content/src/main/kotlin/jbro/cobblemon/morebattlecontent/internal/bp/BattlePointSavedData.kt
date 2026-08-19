package jbro.cobblemon.morebattlecontent.internal.bp

import java.util.UUID
import jbro.cobblemon.morebattlecontent.MoreBattleContent
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.level.saveddata.SavedData

internal class BattlePointSavedData(
    private val store: BattlePointStore = BattlePointStore(),
    val isAvailable: Boolean = true,
    private val preservedTag: CompoundTag? = null,
) : SavedData(), BattlePointAtomicApplier {
    fun balance(playerId: UUID): Long = if (isAvailable) store.balance(playerId) else 0L

    fun history(playerId: UUID, limit: Int): List<BattlePointTransaction> =
        if (isAvailable) store.history(playerId, limit) else emptyList()

    fun apply(request: BattlePointRequest): BattlePointApplyResult {
        if (!isAvailable) return BattlePointApplyResult(BattlePointApplyStatus.UNAVAILABLE, 0L)
        return store.apply(request).also { result ->
            if (result.status == BattlePointApplyStatus.APPLIED) setDirty()
        }
    }

    override fun applyAtomically(
        request: BattlePointRequest,
        commit: () -> Boolean,
    ): BattlePointApplyResult {
        if (!isAvailable) return BattlePointApplyResult(BattlePointApplyStatus.UNAVAILABLE, 0L)
        return store.applyAtomically(request, commit).also { result ->
            if (result.status == BattlePointApplyStatus.APPLIED) setDirty()
        }
    }

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag =
        preservedTag?.copy() ?: BattlePointNbtCodec.encode(store.allAccounts(), tag)

    internal fun saveForTest(): CompoundTag = preservedTag?.copy() ?: BattlePointNbtCodec.encode(store.allAccounts())

    companion object {
        const val FILE_ID = "cobblemon_more_battle_content_bp"
        val factory = Factory(::BattlePointSavedData, ::load, DataFixTypes.LEVEL)

        private fun load(tag: CompoundTag, registries: HolderLookup.Provider): BattlePointSavedData = loadSafely(tag, true)

        internal fun loadForTest(tag: CompoundTag): BattlePointSavedData = loadSafely(tag, false)

        private fun loadSafely(tag: CompoundTag, logFailure: Boolean): BattlePointSavedData = try {
            BattlePointSavedData(BattlePointStore(BattlePointNbtCodec.decode(tag)))
        } catch (exception: RuntimeException) {
            if (logFailure) {
                MoreBattleContent.LOGGER.error(
                    "Battle Point data could not be loaded; BP writes are disabled so the original file is preserved",
                    exception,
                )
            }
            BattlePointSavedData(isAvailable = false, preservedTag = tag.copy())
        }
    }
}

internal object BattlePointService {
    fun isAvailable(server: MinecraftServer): Boolean = data(server).isAvailable

    fun balance(server: MinecraftServer, playerId: UUID): Long = data(server).balance(playerId)

    fun history(server: MinecraftServer, playerId: UUID, limit: Int): List<BattlePointTransaction> =
        data(server).history(playerId, limit)

    fun apply(server: MinecraftServer, request: BattlePointRequest): BattlePointApplyResult = data(server).apply(request)

    fun applyAtomically(
        server: MinecraftServer,
        request: BattlePointRequest,
        commit: () -> Boolean,
    ): BattlePointApplyResult = data(server).applyAtomically(request, commit)

    private fun data(server: MinecraftServer): BattlePointSavedData =
        server.overworld().dataStorage.computeIfAbsent(BattlePointSavedData.factory, BattlePointSavedData.FILE_ID)
}
