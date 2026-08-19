package jbro.cobblemon.morebattlecontent.internal.bp.shop

import java.io.Reader
import java.util.Collections

internal data class BattlePointShopLimits(
    val maxCartLines: Int,
    val maxQuantityPerLine: Int,
    val maxTotalItems: Int,
)

internal data class BattlePointShopEntry(
    val entryId: String,
    val itemId: String,
    val itemCount: Int,
    val priceBp: Long,
    val sortOrder: Int,
)

internal class BattlePointShopCatalog internal constructor(
    val catalogId: String,
    val revision: String,
    val limits: BattlePointShopLimits,
    entries: List<BattlePointShopEntry>,
) {
    private val orderedEntries = Collections.unmodifiableList(entries.sortedBy(BattlePointShopEntry::sortOrder))
    private val entriesById = Collections.unmodifiableMap(orderedEntries.associateBy(BattlePointShopEntry::entryId))

    fun entries(): List<BattlePointShopEntry> = orderedEntries

    fun entry(entryId: String): BattlePointShopEntry? = entriesById[entryId]
}

internal enum class BattlePointShopCatalogIssueCode {
    MALFORMED_JSON,
    UNSUPPORTED_SCHEMA,
    UNKNOWN_FIELD,
    MISSING_FIELD,
    INVALID_VALUE,
    DUPLICATE_ID,
    UNAVAILABLE_ITEM,
}

internal data class BattlePointShopCatalogIssue(
    val code: BattlePointShopCatalogIssueCode,
    val path: String,
    val message: String,
)

internal sealed interface BattlePointShopCatalogLoadResult {
    data class Loaded(val catalog: BattlePointShopCatalog) : BattlePointShopCatalogLoadResult
    data class Rejected(val issues: List<BattlePointShopCatalogIssue>) : BattlePointShopCatalogLoadResult {
        init {
            require(issues.isNotEmpty()) { "A rejected shop catalog must contain at least one issue" }
        }
    }
}

internal class BattlePointShopCatalogStore(
    private val itemExists: (String) -> Boolean,
) {
    @Volatile
    private var current: BattlePointShopCatalog? = null

    fun snapshot(): BattlePointShopCatalog? = current

    fun reload(reader: Reader): BattlePointShopCatalogLoadResult =
        BattlePointShopCatalogLoader.load(reader, itemExists).also { result ->
            if (result is BattlePointShopCatalogLoadResult.Loaded) current = result.catalog
        }
}
