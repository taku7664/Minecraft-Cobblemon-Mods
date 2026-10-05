package jbro.cobblemon.mcc.internal.bp.shop

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
    /** The shop tab the entry is listed under; it only sorts the catalog for viewers and is not part of the revision. */
    val category: String = DEFAULT_CATEGORY,
) {
    companion object {
        const val DEFAULT_CATEGORY = "misc"
    }
}

/**
 * How the shop's keeper may look, tried in order on each client: the first whose texture the client has wins, so
 * a server can list an optional resource pack's skin ahead of a texture every client has.
 */
internal sealed interface BattlePointShopkeeperAppearance {
    /** A player-format skin, such as a Radical Cobblemon Trainers texture. */
    data class Skin(val texture: String, val slim: Boolean) : BattlePointShopkeeperAppearance

    /** A villager with a profession overlay, such as Cobblemon's nurse. */
    data class Villager(val profession: String, val type: String) : BattlePointShopkeeperAppearance

    companion object {
        /** Cobblemon's nurse villager, which every client with Cobblemon has. */
        val DEFAULT: List<BattlePointShopkeeperAppearance> = listOf(Villager("cobblemon:nurse_joy", "minecraft:plains"))
        const val MAX_APPEARANCES = 8
    }
}

internal class BattlePointShopCatalog internal constructor(
    val catalogId: String,
    val revision: String,
    val limits: BattlePointShopLimits,
    entries: List<BattlePointShopEntry>,
    val shopkeeper: List<BattlePointShopkeeperAppearance> = BattlePointShopkeeperAppearance.DEFAULT,
    categoryOrder: List<String> = emptyList(),
) {
    private val orderedEntries = Collections.unmodifiableList(entries.sortedBy(BattlePointShopEntry::sortOrder))
    private val entriesById = Collections.unmodifiableMap(orderedEntries.associateBy(BattlePointShopEntry::entryId))

    /** The categories that hold entries: those [categoryOrder] names first, in its order, then the rest as they first appear. */
    val categories: List<String> = orderedEntries.map(BattlePointShopEntry::category).distinct().let { present ->
        Collections.unmodifiableList(categoryOrder.filter(present::contains) + present.filterNot(categoryOrder::contains))
    }

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

    fun clear() {
        current = null
    }

    fun reload(reader: Reader): BattlePointShopCatalogLoadResult =
        BattlePointShopCatalogLoader.load(reader, itemExists).also { result ->
            if (result is BattlePointShopCatalogLoadResult.Loaded) current = result.catalog
        }

    fun reloadSeparated(
        ruleFragments: List<Pair<String, Reader>>,
        entryFragments: List<Pair<String, Reader>>,
    ): BattlePointShopCatalogLoadResult =
        BattlePointShopCatalogLoader.loadSeparated(ruleFragments, entryFragments, itemExists).also { result ->
            if (result is BattlePointShopCatalogLoadResult.Loaded) current = result.catalog
        }
}
