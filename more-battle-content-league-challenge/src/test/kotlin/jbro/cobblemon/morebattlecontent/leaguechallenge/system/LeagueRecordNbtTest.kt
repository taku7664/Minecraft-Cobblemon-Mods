package jbro.cobblemon.morebattlecontent.leaguechallenge.system

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import jbro.cobblemon.morebattlecontent.leaguechallenge.server.LeagueRecordNbt

class LeagueRecordNbtTest {
    @Test fun `large valid progress survives NBT serialization without writeUTF overflow`() {
        val state = LeagueProgress(cleared = (1..1000).map { "test:challenge_${it}_with_a_long_identifier_to_exceed_the_nbt_string_limit" }.toSet())
        val raw = LeagueProgressCodec.encode(state)
        assertTrue(raw.length > 65535)
        val entries = CompoundTag()
        LeagueRecordNbt.write(entries, "player", raw)
        val bytes = ByteArrayOutputStream()
        assertDoesNotThrow { NbtIo.write(entries, DataOutputStream(bytes)) }
        val restored = NbtIo.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray())), NbtAccounter.unlimitedHeap())
        assertEquals(state, LeagueProgressCodec.decode(LeagueRecordNbt.read(restored, "player")))
    }

    @Test fun `legacy strings remain readable and corrupt types fail closed`() {
        val entries = CompoundTag()
        entries.putString("old", "legacy")
        assertEquals("legacy", LeagueRecordNbt.read(entries, "old"))
        entries.putInt("broken", 1)
        assertThrows(IllegalStateException::class.java) { LeagueRecordNbt.read(entries, "broken") }
    }

    @Test fun `invalid UTF8 is reported through the server runtime error boundary`() {
        val entries = CompoundTag()
        entries.putByteArray("broken", byteArrayOf(0xC3.toByte(), 0x28))
        assertThrows(IllegalArgumentException::class.java) { LeagueRecordNbt.read(entries, "broken") }
    }
}
