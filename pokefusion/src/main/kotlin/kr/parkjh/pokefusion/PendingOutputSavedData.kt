package kr.parkjh.pokefusion

import java.util.UUID
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.saveddata.SavedData
import org.slf4j.LoggerFactory

class PendingOutputSavedData(
    private val ledger: PendingOutputLedger<ItemStack> = PendingOutputLedger(),
    val isAvailable: Boolean = true,
    private val preservedRoot: CompoundTag? = null
) : SavedData() {
    fun enqueue(playerId: UUID, items: Iterable<ItemStack>) {
        check(isAvailable) { "Pokefusion pending-output storage is unavailable" }
        val copies = items.filterNot(ItemStack::isEmpty).map(ItemStack::copy)
        if (copies.isEmpty()) return
        ledger.enqueue(playerId, copies)
        setDirty()
    }

    fun count(playerId: UUID): Int = ledger.count(playerId)

    internal fun snapshot(playerId: UUID): List<ItemStack> = ledger.snapshot(playerId)

    fun deliver(playerId: UUID, delivery: (ItemStack) -> Boolean) {
        if (!isAvailable || ledger.count(playerId) == 0) return
        ledger.deliver(playerId, delivery)
        setDirty()
    }

    override fun save(root: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
        preservedRoot?.let { return it.copy() }
        val players = ListTag()
        for (playerId in ledger.players().sortedBy(UUID::toString)) {
            val items = ListTag()
            for (stack in ledger.snapshot(playerId)) {
                if (!stack.isEmpty) items.add(stack.save(registries))
            }
            if (!items.isEmpty()) {
                val entry = CompoundTag()
                entry.putUUID(PLAYER_KEY, playerId)
                entry.put(ITEMS_KEY, items)
                players.add(entry)
            }
        }
        root.put(PLAYERS_KEY, players)
        return root
    }

    companion object {
        private const val FILE_ID = "pokefusion_pending_outputs"
        private const val PLAYERS_KEY = "players"
        private const val PLAYER_KEY = "player"
        private const val ITEMS_KEY = "items"
        private val FACTORY = Factory(::PendingOutputSavedData, ::load, DataFixTypes.LEVEL)

        fun get(server: MinecraftServer): PendingOutputSavedData =
            server.overworld().dataStorage.computeIfAbsent(FACTORY, FILE_ID)

        private fun load(root: CompoundTag, registries: HolderLookup.Provider): PendingOutputSavedData =
            loadSafely(root, registries, true)

        private fun loadSafely(
            root: CompoundTag,
            registries: HolderLookup.Provider,
            logFailure: Boolean
        ): PendingOutputSavedData = try {
            val ledger = PendingOutputLedger<ItemStack>()
            require(root.contains(PLAYERS_KEY, Tag.TAG_LIST.toInt())) { "Missing pending-output player list" }
            val players = root.get(PLAYERS_KEY) as ListTag
            require(players.isEmpty() || players.elementType == Tag.TAG_COMPOUND) {
                "Invalid pending-output player list"
            }
            for (playerIndex in 0 until players.size) {
                val entry = players.getCompound(playerIndex)
                require(entry.hasUUID(PLAYER_KEY)) { "Missing pending-output player UUID at index $playerIndex" }
                require(entry.contains(ITEMS_KEY, Tag.TAG_LIST.toInt())) {
                    "Missing pending-output item list for player ${entry.getUUID(PLAYER_KEY)}"
                }
                val items = entry.get(ITEMS_KEY) as ListTag
                require(items.isEmpty() || items.elementType == Tag.TAG_COMPOUND) {
                    "Invalid pending-output item list for player ${entry.getUUID(PLAYER_KEY)}"
                }
                val decoded = buildList {
                    for (itemIndex in 0 until items.size) {
                        add(
                            ItemStack.parse(registries, items.getCompound(itemIndex)).orElseThrow {
                                IllegalArgumentException("Invalid pending ItemStack for player ${entry.getUUID(PLAYER_KEY)}")
                            }
                        )
                    }
                }
                ledger.enqueue(entry.getUUID(PLAYER_KEY), decoded)
            }
            PendingOutputSavedData(ledger)
        } catch (exception: RuntimeException) {
            if (logFailure) {
                LOGGER.error(
                    "Pokefusion 미지급 결과 저장 데이터를 읽지 못했습니다. 원본을 보존하고 추가 합성을 차단합니다.",
                    exception
                )
            }
            PendingOutputSavedData(isAvailable = false, preservedRoot = root.copy())
        }

        internal fun loadForTest(root: CompoundTag, registries: HolderLookup.Provider): PendingOutputSavedData =
            loadSafely(root, registries, false)

        private val LOGGER = LoggerFactory.getLogger(PendingOutputSavedData::class.java)
    }
}
