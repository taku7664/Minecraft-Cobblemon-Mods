package jbro.cobblemon.morebattlecontent.internal.bp.shop

import java.util.UUID
import jbro.cobblemon.morebattlecontent.MoreBattleContent
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

internal data class ShopEntryView(
    val entryId: String,
    val itemId: String,
    val itemCount: Int,
    val priceBp: Long,
)

internal data class HomeLeaderboardStatePayload(
    val singles: List<HomeLeaderboardEntry>,
    val doubles: List<HomeLeaderboardEntry>,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<HomeLeaderboardStatePayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<HomeLeaderboardStatePayload>(id("home_leaderboard_state"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, HomeLeaderboardStatePayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeLeaderboardEntries(payload.singles)
                buffer.writeLeaderboardEntries(payload.doubles)
            },
            { buffer ->
                HomeLeaderboardStatePayload(
                    singles = buffer.readLeaderboardEntries(),
                    doubles = buffer.readLeaderboardEntries(),
                )
            },
        )
    }
}

internal data class HomeLeaderboardBoard(
    val contentId: String,
    val formatId: String,
    val entries: List<HomeLeaderboardEntry>,
)

internal data class HomeLeaderboardCatalogPayload(
    val boards: List<HomeLeaderboardBoard>,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<HomeLeaderboardCatalogPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<HomeLeaderboardCatalogPayload>(id("home_leaderboard_catalog"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, HomeLeaderboardCatalogPayload> = StreamCodec.of(
            { buffer, payload ->
                require(payload.boards.size <= MAX_LEADERBOARD_BOARDS) { "Too many leaderboard boards" }
                buffer.writeVarInt(payload.boards.size)
                payload.boards.forEach { board ->
                    buffer.writeShopString(board.contentId)
                    buffer.writeShopString(board.formatId)
                    buffer.writeExtendedLeaderboardEntries(board.entries)
                }
            },
            { buffer ->
                HomeLeaderboardCatalogPayload(
                    buildList {
                        repeat(buffer.readBoundedShopCount(MAX_LEADERBOARD_BOARDS, "leaderboard board")) {
                            add(
                                HomeLeaderboardBoard(
                                    buffer.readShopString(),
                                    buffer.readShopString(),
                                    buffer.readExtendedLeaderboardEntries(),
                                ),
                            )
                        }
                    },
                )
            },
        )
    }
}

internal data object ShopOpenPayload : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ShopOpenPayload> = TYPE

    val TYPE = CustomPacketPayload.Type<ShopOpenPayload>(id("bp_shop_open"))
    val CODEC: StreamCodec<RegistryFriendlyByteBuf, ShopOpenPayload> = StreamCodec.of(
        { _, _ -> Unit },
        { ShopOpenPayload },
    )
}

internal data class ShopStatePayload(
    val catalogId: String,
    val catalogRevision: String,
    val balanceBp: Long,
    val limits: BattlePointShopLimits,
    val entries: List<ShopEntryView>,
    val result: BattlePointShopPurchaseStatus?,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ShopStatePayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ShopStatePayload>(id("bp_shop_state"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, ShopStatePayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeShopString(payload.catalogId)
                buffer.writeShopString(payload.catalogRevision)
                buffer.writeVarLong(payload.balanceBp)
                buffer.writeVarInt(payload.limits.maxCartLines)
                buffer.writeVarInt(payload.limits.maxQuantityPerLine)
                buffer.writeVarInt(payload.limits.maxTotalItems)
                buffer.writeVarInt(payload.entries.size)
                payload.entries.forEach { entry ->
                    buffer.writeShopString(entry.entryId)
                    buffer.writeShopString(entry.itemId)
                    buffer.writeVarInt(entry.itemCount)
                    buffer.writeVarLong(entry.priceBp)
                }
                buffer.writeBoolean(payload.result != null)
                payload.result?.let { buffer.writeShopString(it.name.lowercase()) }
            },
            { buffer ->
                val catalogId = buffer.readShopString()
                val revision = buffer.readShopString()
                val balance = buffer.readVarLong().also { require(it >= 0L) { "Negative shop BP balance" } }
                val limits = BattlePointShopLimits(
                    maxCartLines = buffer.readPositiveShopLimit("cart line"),
                    maxQuantityPerLine = buffer.readPositiveShopLimit("quantity"),
                    maxTotalItems = buffer.readPositiveShopLimit("total item"),
                )
                val entries = buildList {
                    repeat(buffer.readBoundedShopCount(MAX_ENTRIES, "entry")) {
                        add(
                            ShopEntryView(
                                buffer.readShopString(),
                                buffer.readShopString(),
                                buffer.readVarInt().also { require(it > 0) { "Invalid shop item count" } },
                                buffer.readVarLong().also { require(it > 0L) { "Invalid shop price" } },
                            ),
                        )
                    }
                }
                val result = if (buffer.readBoolean()) {
                    val name = buffer.readShopString()
                    BattlePointShopPurchaseStatus.entries.singleOrNull { it.name.equals(name, true) }
                        ?: throw IllegalArgumentException("Unsupported shop result: $name")
                } else {
                    null
                }
                ShopStatePayload(catalogId, revision, balance, limits, entries, result)
            },
        )
    }
}

internal data class ShopPurchasePayload(
    val purchaseId: UUID,
    val catalogId: String,
    val catalogRevision: String,
    val lines: List<BattlePointShopCartLine>,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ShopPurchasePayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ShopPurchasePayload>(id("bp_shop_purchase"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, ShopPurchasePayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeUUID(payload.purchaseId)
                buffer.writeShopString(payload.catalogId)
                buffer.writeShopString(payload.catalogRevision)
                buffer.writeVarInt(payload.lines.size)
                payload.lines.forEach { line ->
                    buffer.writeShopString(line.entryId)
                    buffer.writeVarInt(line.quantity)
                }
            },
            { buffer ->
                ShopPurchasePayload(
                    buffer.readUUID(),
                    buffer.readShopString(),
                    buffer.readShopString(),
                    buildList {
                        repeat(buffer.readBoundedShopCount(MAX_CART_LINES, "cart line")) {
                            add(
                                BattlePointShopCartLine(
                                    buffer.readShopString(),
                                    buffer.readVarInt().also { require(it > 0) { "Invalid shop quantity" } },
                                ),
                            )
                        }
                    },
                )
            },
        )
    }
}

private fun RegistryFriendlyByteBuf.writeShopString(value: String) = writeUtf(value, MAX_STRING_LENGTH)
private fun RegistryFriendlyByteBuf.readShopString(): String = readUtf(MAX_STRING_LENGTH)
private fun RegistryFriendlyByteBuf.writeLeaderboardEntries(entries: List<HomeLeaderboardEntry>) {
    require(entries.size <= HomeLeaderboard.MAX_ENTRIES) { "Too many leaderboard entries" }
    writeVarInt(entries.size)
    entries.forEach { entry ->
        writeVarInt(entry.place)
        writeUUID(entry.playerId)
        writeShopString(entry.playerName)
        writeVarLong(entry.highestRank)
        writeVarLong(entry.rankProgress)
        writeVarLong(entry.totalWins)
    }
}
private fun RegistryFriendlyByteBuf.readLeaderboardEntries(): List<HomeLeaderboardEntry> = buildList {
    repeat(readBoundedShopCount(HomeLeaderboard.MAX_ENTRIES, "leaderboard entry")) {
        add(
            HomeLeaderboardEntry(
                place = readVarInt().also { require(it > 0) { "Invalid leaderboard place" } },
                playerId = readUUID(),
                playerName = readShopString(),
                highestRank = readVarLong().also { require(it >= 0) { "Invalid leaderboard rank" } },
                rankProgress = readVarLong().also { require(it >= 0) { "Invalid leaderboard progress" } },
                totalWins = readVarLong().also { require(it >= 0) { "Invalid leaderboard wins" } },
            ),
        )
    }
}
private fun RegistryFriendlyByteBuf.writeExtendedLeaderboardEntries(entries: List<HomeLeaderboardEntry>) {
    writeLeaderboardEntries(entries)
    entries.forEach { entry ->
        writeVarLong(entry.highestFloor)
        writeVarLong(entry.totalLosses)
        writeVarInt(entry.bestWinStreak)
    }
}
private fun RegistryFriendlyByteBuf.readExtendedLeaderboardEntries(): List<HomeLeaderboardEntry> {
    val base = readLeaderboardEntries()
    return base.map { entry ->
        entry.copy(
            highestFloor = readVarLong().also { require(it >= 0) { "Invalid leaderboard floor" } },
            totalLosses = readVarLong().also { require(it >= 0) { "Invalid leaderboard losses" } },
            bestWinStreak = readVarInt().also { require(it >= 0) { "Invalid leaderboard streak" } },
        )
    }
}
private fun RegistryFriendlyByteBuf.readBoundedShopCount(maximum: Int, label: String): Int =
    readVarInt().also { require(it in 0..maximum) { "Invalid shop $label count: $it" } }
private fun RegistryFriendlyByteBuf.readPositiveShopLimit(label: String): Int =
    readVarInt().also { require(it in 1..MAX_LIMIT) { "Invalid shop $label limit: $it" } }

private fun id(path: String) = ResourceLocation.fromNamespaceAndPath(MoreBattleContent.MOD_ID, path)

private const val MAX_ENTRIES = 256
private const val MAX_CART_LINES = 64
private const val MAX_STRING_LENGTH = 160
private const val MAX_LIMIT = 4096
private const val MAX_LEADERBOARD_BOARDS = 8
