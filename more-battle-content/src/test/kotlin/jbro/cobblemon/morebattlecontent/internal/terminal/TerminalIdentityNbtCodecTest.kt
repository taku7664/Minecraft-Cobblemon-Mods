package jbro.cobblemon.morebattlecontent.internal.terminal

import java.util.UUID
import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class TerminalIdentityNbtCodecTest {
    private val terminalId = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val fallbackId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

    @Test
    fun `writes and reads only the terminal identifier`() {
        val tag = CompoundTag()

        TerminalIdentityNbtCodec.write(tag, terminalId)

        assertEquals(terminalId, TerminalIdentityNbtCodec.read(tag, fallbackId))
        assertEquals(setOf("terminal_id"), tag.allKeys)
        assertFalse(tag.contains("party"))
        assertFalse(tag.contains("bp"))
        assertFalse(tag.contains("progress"))
        assertFalse(tag.contains("session"))
    }

    @Test
    fun `missing or wrong type identifier preserves the generated fallback`() {
        assertEquals(fallbackId, TerminalIdentityNbtCodec.read(CompoundTag(), fallbackId))
        assertEquals(
            fallbackId,
            TerminalIdentityNbtCodec.read(CompoundTag().apply { putString("terminal_id", "not-a-uuid-tag") }, fallbackId),
        )
    }
}
