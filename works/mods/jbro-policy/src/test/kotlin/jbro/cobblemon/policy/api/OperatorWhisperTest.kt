package jbro.cobblemon.policy.api

import net.minecraft.ChatFormatting
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OperatorWhisperTest {
    @Test
    fun `the header, the underlined quote, then the message, all gray`() {
        val whisper = OperatorWhisper.component("광장을 다시 열었어요.", "광장 이동이 안 돼요")
        assertEquals("message.jbro_policy.operator_whisper\n\"광장 이동이 안 돼요\"\n광장을 다시 열었어요.", whisper.string)
        assertEquals(ChatFormatting.GRAY.color, whisper.style.color?.value)
        assertTrue(whisper.siblings.single { it.string == "\"광장 이동이 안 돼요\"" }.style.isUnderlined)
    }

    @Test
    fun `no quote, no quote line`() {
        assertEquals("message.jbro_policy.operator_whisper\n안녕하세요.", OperatorWhisper.component("안녕하세요.", null).string)
    }
}
