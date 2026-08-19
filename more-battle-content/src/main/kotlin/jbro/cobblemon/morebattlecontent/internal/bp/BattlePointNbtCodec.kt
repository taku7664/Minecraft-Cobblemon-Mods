package jbro.cobblemon.morebattlecontent.internal.bp

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag

internal object BattlePointNbtCodec {
    private const val SCHEMA_VERSION = 1

    fun encode(accounts: Collection<BattlePointAccount>, destination: CompoundTag = CompoundTag()): CompoundTag =
        destination.also { root ->
            root.putInt("SchemaVersion", SCHEMA_VERSION)
            root.put("Accounts", ListTag().also { list -> accounts.forEach { list.add(encodeAccount(it)) } })
        }

    fun decode(root: CompoundTag): List<BattlePointAccount> {
        requireTag(root, "SchemaVersion", Tag.TAG_INT)
        val schemaVersion = root.getInt("SchemaVersion")
        require(schemaVersion == SCHEMA_VERSION) {
            "Unsupported More Battle Content Battle Point schema version: $schemaVersion"
        }
        val accounts = requireCompoundList(root, "Accounts")
        return List(accounts.size) { index -> decodeAccount(accounts.getCompound(index)) }
    }

    private fun encodeAccount(account: BattlePointAccount): CompoundTag = CompoundTag().also { tag ->
        tag.putUUID("Player", account.playerId)
        tag.putLong("Balance", account.balance)
        tag.put(
            "Transactions",
            ListTag().also { list -> account.transactions.forEach { list.add(encodeTransaction(it)) } },
        )
    }

    private fun decodeAccount(tag: CompoundTag): BattlePointAccount {
        require(tag.hasUUID("Player")) { "Missing or invalid Battle Point account Player" }
        requireTag(tag, "Balance", Tag.TAG_LONG)
        val playerId = tag.getUUID("Player")
        val transactions = requireCompoundList(tag, "Transactions")
        return BattlePointAccount(
            playerId = playerId,
            balance = tag.getLong("Balance"),
            transactions = List(transactions.size) { index -> decodeTransaction(playerId, transactions.getCompound(index)) },
        )
    }

    private fun encodeTransaction(transaction: BattlePointTransaction): CompoundTag = CompoundTag().also { tag ->
        tag.putUUID("Id", transaction.transactionId)
        tag.putString("Kind", transaction.kind.name)
        tag.putLong("RequestedValue", transaction.requestedValue)
        tag.putLong("BalanceBefore", transaction.balanceBefore)
        tag.putLong("BalanceAfter", transaction.balanceAfter)
        tag.putString("Source", transaction.sourceId.value)
        tag.putString("Reason", transaction.reason)
        tag.putLong("RecordedAt", transaction.recordedAtEpochMillis)
    }

    private fun decodeTransaction(playerId: java.util.UUID, tag: CompoundTag): BattlePointTransaction {
        require(tag.hasUUID("Id")) { "Missing or invalid Battle Point transaction Id" }
        requireTag(tag, "Kind", Tag.TAG_STRING)
        requireTag(tag, "RequestedValue", Tag.TAG_LONG)
        requireTag(tag, "BalanceBefore", Tag.TAG_LONG)
        requireTag(tag, "BalanceAfter", Tag.TAG_LONG)
        requireTag(tag, "Source", Tag.TAG_STRING)
        requireTag(tag, "Reason", Tag.TAG_STRING)
        requireTag(tag, "RecordedAt", Tag.TAG_LONG)
        return BattlePointTransaction(
            transactionId = tag.getUUID("Id"),
            playerId = playerId,
            kind = BattlePointTransactionKind.valueOf(tag.getString("Kind")),
            requestedValue = tag.getLong("RequestedValue"),
            balanceBefore = tag.getLong("BalanceBefore"),
            balanceAfter = tag.getLong("BalanceAfter"),
            sourceId = BattlePointSourceId(tag.getString("Source")),
            reason = tag.getString("Reason"),
            recordedAtEpochMillis = tag.getLong("RecordedAt"),
        )
    }

    private fun requireCompoundList(tag: CompoundTag, key: String): ListTag {
        requireTag(tag, key, Tag.TAG_LIST)
        val list = tag.get(key) as ListTag
        require(list.all { it is CompoundTag }) { "Battle Point $key must contain only compound tags" }
        return list
    }

    private fun requireTag(tag: CompoundTag, key: String, type: Byte) {
        require(tag.contains(key, type.toInt())) { "Missing or invalid Battle Point tag: $key" }
    }
}
