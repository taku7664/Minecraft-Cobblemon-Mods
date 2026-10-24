package jbro.cobblemon.mcc.internal.bp.recovery

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import jbro.cobblemon.mcc.internal.bp.*
import jbro.cobblemon.mcc.internal.bp.shop.BattlePointShopGrant
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

internal enum class CompensationPhase { READY, APPLYING_BP, CHARGED, DELIVERING, COMPLETE, OPERATOR }

/** Immutable entitlement comes from the original server operation, never from a model or an inquiry. */
internal data class CompensationEntry(
    val player: UUID,
    val transaction: UUID,
    val kind: BattlePointTransactionKind,
    val amount: Long,
    val source: String,
    val reason: String,
    val grants: List<BattlePointShopGrant>,
    val createdAt: Long,
    val baseline: UUID? = null,
    val baselineKnown: Boolean = true,
    val phase: CompensationPhase = CompensationPhase.READY,
    val note: String = "",
    val version: Int = 1,
) {
    fun request() = BattlePointRequest(transaction, player, when (kind) {
        BattlePointTransactionKind.CONTENT_REWARD -> BattlePointOperation.ContentReward(amount)
        BattlePointTransactionKind.SHOP_PURCHASE -> BattlePointOperation.ShopPurchase(amount)
        else -> error("Not a recoverable operation")
    }, BattlePointSourceId(source), reason)

    fun validate(): CompensationEntry = apply {
        require(version == 1 && createdAt >= 0 && amount > 0)
        request()
        require(grants.size <= 64 && grants.all { it.count > 0 && it.count <= 4096 && ITEM_ID.matches(it.itemId) })
        require(if (kind == BattlePointTransactionKind.CONTENT_REWARD) grants.isEmpty() else grants.isNotEmpty())
    }

    fun matches(request: BattlePointRequest, items: List<BattlePointShopGrant>) =
        request() == request && grants == items

    companion object { private val ITEM_ID = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+") }
}

/** One forced, atomically replaced file per original transaction. No pruning of completion markers. */
internal class CompensationJournal(private val root: Path) {
    private val gson = GsonBuilder().serializeNulls().create()

    private fun owner(player: UUID): Path {
        val directory = root.resolve(player.toString())
        val initialized = root.resolve("${player}.initialized")
        if (!Files.exists(directory)) {
            check(!Files.exists(initialized)) { "Compensation journal was removed" }
            Files.createDirectories(directory)
        }
        if (!Files.exists(initialized)) forceWrite(initialized, "1")
        return directory
    }

    fun read(player: UUID, transaction: UUID): CompensationEntry? {
        val file = owner(player).resolve("$transaction.json")
        if (!Files.exists(file)) return null
        check(Files.size(file) <= 128_000) { "Compensation entry too large" }
        val json = JsonParser.parseString(Files.readString(file)).asJsonObject
        // Gson may bypass constructors; require every persisted field before accepting it.
        require(listOf("player", "transaction", "kind", "amount", "source", "reason", "grants", "createdAt", "phase", "note", "version", "baselineKnown")
            .all { json.has(it) && !json.get(it).isJsonNull })
        require(json.has("baseline"))
        CompensationPhase.valueOf(json.get("phase").asString)
        BattlePointTransactionKind.valueOf(json.get("kind").asString)
        val entry = gson.fromJson(json, CompensationEntry::class.java).validate()
        require(entry.player == player && entry.transaction == transaction) { "Compensation identity mismatch" }
        return entry
    }

    fun write(entry: CompensationEntry) {
        entry.validate()
        forceWrite(owner(entry.player).resolve("${entry.transaction}.json"), gson.toJson(entry))
    }

    fun list(player: UUID): List<CompensationEntry> = Files.list(owner(player)).use { paths ->
        paths.filter { it.fileName.toString().endsWith(".json") }.map { path ->
            read(player, UUID.fromString(path.fileName.toString().removeSuffix(".json")))!!
        }.toList()
    }

    private fun forceWrite(destination: Path, content: String) {
        Files.createDirectories(destination.parent)
        val temporary = Files.createTempFile(destination.parent, "pending-", ".tmp")
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(content.toByteArray(Charsets.UTF_8))
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            // Windows cannot open a directory as a FileChannel; Unix can also force the rename metadata.
            runCatching { FileChannel.open(destination.parent, StandardOpenOption.READ).use { it.force(true) } }
        } finally { Files.deleteIfExists(temporary) }
    }
}
