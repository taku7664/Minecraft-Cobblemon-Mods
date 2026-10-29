package jbro.cobblemon.policy.support

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DiscordReplyVisibilityTest {
    @Test
    fun `online player reply follows file edits without recreating the bot`(@TempDir dir: Path) {
        val file = dir.resolve("jbro-policy-discord-replies.json")
        val warnings = mutableListOf<String>()
        val visibility = DiscordReplyVisibility(file) { message, _ -> warnings += message }

        assertTrue(Files.readString(file).contains("\"onlinePlayersPrivate\": true"))
        assertEquals(true, visibility.isPrivate("접속자", false))
        Files.writeString(file, """{"onlinePlayersPrivate":false}""")
        assertEquals(false, visibility.isPrivate("접속자", true))
        assertEquals(true, visibility.isPrivate("verify", true))

        Files.writeString(file, """{"onlinePlayersPrivate":"broken"}""")
        assertEquals(false, visibility.isPrivate("접속자", true))
        assertEquals(1, warnings.size)
        assertEquals(false, visibility.isPrivate("접속자", true))
        assertEquals(1, warnings.size)

        Files.writeString(file, """{"onlinePlayersPrivate":true}""")
        assertEquals(true, visibility.isPrivate("접속자", false))
    }
}
