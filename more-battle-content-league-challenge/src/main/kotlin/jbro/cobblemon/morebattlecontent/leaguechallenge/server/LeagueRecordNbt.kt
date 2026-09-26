package jbro.cobblemon.morebattlecontent.leaguechallenge.server

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import net.minecraft.nbt.CompoundTag

/** NBT strings use writeUTF (64 KiB); party snapshots and long-lived receipts need byte arrays. */
internal object LeagueRecordNbt {
    private const val MAX_BYTES = 8_388_608

    fun read(entries: CompoundTag, key: String): String {
        if (entries.contains(key, 8)) return entries.getString(key) // Pre-release legacy records.
        check(entries.contains(key, 7)) { "Corrupt League progress $key" }
        val bytes = entries.getByteArray(key)
        require(bytes.size <= MAX_BYTES) { "Progress is oversized" }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (failure: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("Corrupt League UTF-8 progress $key", failure)
        }
    }

    fun write(entries: CompoundTag, key: String, raw: String) {
        val bytes = raw.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Progress is oversized" }
        entries.putByteArray(key, bytes)
    }
}
