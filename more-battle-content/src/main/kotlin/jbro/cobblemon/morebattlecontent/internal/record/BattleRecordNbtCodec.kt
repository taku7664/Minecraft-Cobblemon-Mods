package jbro.cobblemon.morebattlecontent.internal.record

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag

internal object BattleRecordNbtCodec {
    private const val SCHEMA_VERSION = 1

    fun encode(records: Collection<BattleRecordStats>, destination: CompoundTag = CompoundTag()): CompoundTag =
        destination.also { root ->
            root.putInt("SchemaVersion", SCHEMA_VERSION)
            root.put("Records", ListTag().also { list -> records.forEach { list.add(encodeRecord(it)) } })
        }

    fun decode(root: CompoundTag): List<BattleRecordStats> {
        requireTag(root, "SchemaVersion", Tag.TAG_INT)
        val schemaVersion = root.getInt("SchemaVersion")
        require(schemaVersion == SCHEMA_VERSION) {
            "Unsupported More Battle Content record schema version: $schemaVersion"
        }
        val list = requireCompoundList(root, "Records")
        return List(list.size) { index -> decodeRecord(list.getCompound(index)) }
    }

    private fun encodeRecord(value: BattleRecordStats): CompoundTag = CompoundTag().also { tag ->
        tag.putUUID("Player", value.key.playerId)
        tag.putString("Content", value.key.category.contentId)
        tag.putString("Format", value.key.category.formatId)
        tag.putLong("TotalWins", value.totalWins)
        tag.putLong("TotalLosses", value.totalLosses)
        tag.putInt("CurrentWinStreak", value.currentWinStreak)
        tag.putInt("BestWinStreak", value.bestWinStreak)
        tag.put("ProgressMetrics", encodeMetrics(value.progressMetrics))
        tag.put("BestMetrics", encodeMetrics(value.bestMetrics))
    }

    private fun decodeRecord(tag: CompoundTag): BattleRecordStats {
        require(tag.hasUUID("Player")) { "Missing or invalid battle record Player" }
        requireTag(tag, "Content", Tag.TAG_STRING)
        requireTag(tag, "Format", Tag.TAG_STRING)
        requireTag(tag, "TotalWins", Tag.TAG_LONG)
        requireTag(tag, "TotalLosses", Tag.TAG_LONG)
        requireTag(tag, "CurrentWinStreak", Tag.TAG_INT)
        requireTag(tag, "BestWinStreak", Tag.TAG_INT)
        requireTag(tag, "ProgressMetrics", Tag.TAG_COMPOUND)
        requireTag(tag, "BestMetrics", Tag.TAG_COMPOUND)
        return BattleRecordStats(
            key = BattleRecordKey(
                playerId = tag.getUUID("Player"),
                category = BattleRecordCategory(tag.getString("Content"), tag.getString("Format")),
            ),
            totalWins = tag.getLong("TotalWins"),
            totalLosses = tag.getLong("TotalLosses"),
            currentWinStreak = tag.getInt("CurrentWinStreak"),
            bestWinStreak = tag.getInt("BestWinStreak"),
            progressMetrics = decodeMetrics(tag.getCompound("ProgressMetrics")),
            bestMetrics = decodeMetrics(tag.getCompound("BestMetrics")),
        )
    }

    private fun encodeMetrics(metrics: Map<BattleRecordMetricId, Long>): CompoundTag = CompoundTag().also { tag ->
        metrics.toSortedMap(compareBy(BattleRecordMetricId::value)).forEach { (id, value) -> tag.putLong(id.value, value) }
    }

    private fun decodeMetrics(tag: CompoundTag): Map<BattleRecordMetricId, Long> = tag.allKeys
        .sorted()
        .associate { key ->
            requireTag(tag, key, Tag.TAG_LONG)
            BattleRecordMetricId(key) to tag.getLong(key)
        }

    private fun requireCompoundList(tag: CompoundTag, key: String): ListTag {
        requireTag(tag, key, Tag.TAG_LIST)
        val list = tag.get(key) as ListTag
        require(list.all { it is CompoundTag }) { "Battle record $key must contain only compound tags" }
        return list
    }

    private fun requireTag(tag: CompoundTag, key: String, type: Byte) {
        require(tag.contains(key, type.toInt())) { "Missing or invalid battle record tag: $key" }
    }
}
