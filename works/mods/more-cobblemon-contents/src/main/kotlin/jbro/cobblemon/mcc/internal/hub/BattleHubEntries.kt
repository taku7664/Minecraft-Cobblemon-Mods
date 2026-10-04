package jbro.cobblemon.mcc.internal.hub

import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.mcc.internal.terminal.TerminalInteractionResult
import net.minecraft.server.level.ServerPlayer

/** Hub-owned entries that are not battle contents. */
object BattleHubIds {
    const val DASHBOARD: String = "${MoreCobblemonContents.MOD_ID}:dashboard"
    const val SHOP: String = "${MoreCobblemonContents.MOD_ID}:shop"
    const val BOSS_RAID: String = "${MoreCobblemonContents.MOD_ID}:boss_raid"
}

/**
 * One screen the hub can open. [open] receives the verified terminal the hub was opened from, or null for
 * a command entry. [accessContentId] names the content whose OPEN policy gates the entry; null never gates.
 */
class BattleHubEntry(
    val contentId: String,
    val accessContentId: String? = contentId,
    val open: (ServerPlayer, TerminalInteractionResult.Verified?) -> Boolean,
) {
    init {
        require(ManagedBattleContentIds.isValid(contentId)) { "Invalid hub content ID: $contentId" }
        require(accessContentId == null || ManagedBattleContentIds.isValid(accessContentId)) {
            "Invalid hub access content ID: $accessContentId"
        }
    }
}

object BattleHubEntries {
    private val entries = LinkedHashMap<String, BattleHubEntry>()

    @Synchronized
    fun register(entry: BattleHubEntry): AutoCloseable {
        require(entries.putIfAbsent(entry.contentId, entry) == null) { "Duplicate hub content ID: ${entry.contentId}" }
        return AutoCloseable { unregister(entry) }
    }

    @Synchronized
    fun get(contentId: String): BattleHubEntry? = entries[contentId]

    @Synchronized
    fun all(): List<BattleHubEntry> = entries.values.toList()

    @Synchronized
    private fun unregister(entry: BattleHubEntry) {
        entries.remove(entry.contentId, entry)
    }
}
