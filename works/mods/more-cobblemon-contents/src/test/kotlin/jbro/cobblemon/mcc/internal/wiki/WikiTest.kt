package jbro.cobblemon.mcc.internal.wiki

import java.nio.file.Files
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WikiTest {
    @Test
    fun `the game port tells an HTTP request from a Minecraft handshake by its first bytes`() {
        fun bytes(vararg values: Int) = io.netty.buffer.Unpooled.wrappedBuffer(ByteArray(values.size) { values[it].toByte() })
        fun text(value: String) = io.netty.buffer.Unpooled.wrappedBuffer(value.toByteArray())
        assertTrue(WikiPortSharing.isHttp(text("GET /?t=abc HTTP/1.1\r\n")))
        assertTrue(WikiPortSharing.isHttp(text("HEAD / HTTP/1.1\r\n")))
        // A handshake: length, packet ID 0, protocol version 767 as a VarInt, then the host. 'G' (71) can be a length.
        assertFalse(WikiPortSharing.isHttp(bytes(71, 0x00, 0xFF, 0x05, 0x0E)))
        assertFalse(WikiPortSharing.isHttp(bytes(0xFE, 0x01, 0xFA)))
        assertFalse(WikiPortSharing.isHttp(text("GET")))
        // Read from the reader index, without moving it.
        val buffer = text("xxGET /")
        assertFalse(WikiPortSharing.isHttp(buffer))
        buffer.readerIndex(2)
        assertTrue(WikiPortSharing.isHttp(buffer))
        assertEquals(2, buffer.readerIndex())
    }

    @Test
    fun `a player whose client serves the wiki gets a localhost link that names the server for their data`() {
        val id = UUID.randomUUID()
        assertEquals("http://localhost:18100/?t=abc&s=http%3A%2F%2Fplay.example.com%3A25566",
            WikiServer.link(id, "abc", localPort = 18100, server = "http://play.example.com:25566"))
        assertEquals("http://play.example.com:25566/?t=abc", WikiServer.link(id, "abc", localPort = null, server = "http://play.example.com:25566"))
        assertTrue(WikiPortSharing.isHttp(io.netty.buffer.Unpooled.wrappedBuffer("OPTIONS /api/me HTTP/1.1\r\n".toByteArray())))
    }

    @Test
    fun `local wiki files stay inside the wiki and get their types`() {
        val root = Files.createTempDirectory("wiki")
        Files.writeString(root.resolve("index.html"), "<p>hi</p>")
        Files.createDirectories(root.resolve("assets"))
        Files.writeString(root.resolve("assets/wiki.css"), "p{}")
        Files.writeString(root.parent.resolve("secret.txt"), "no")
        assertEquals("text/html; charset=utf-8", WikiFiles.read(root, "/").type)
        assertEquals(200, WikiFiles.read(root, "/assets/wiki.css").status)
        assertEquals(403, WikiFiles.read(root, "/../secret.txt").status)
        assertEquals(403, WikiFiles.read(root, "/%2e%2e/secret.txt").status)
        assertEquals(404, WikiFiles.read(root, "/missing.html").status)
    }

    @Test
    fun `links use the address the player typed, bracketing IPv6 and dropping client markers`() {
        assertEquals("play.example.com:25566", WikiPortSharing.address("play.example.com.", 25566))
        assertEquals("116.33.1.2:25566", WikiPortSharing.address("116.33.1.2\u0000FML3\u0000", 25566))
        assertEquals("[2001:db8::1]:25566", WikiPortSharing.address("2001:db8::1", 25566))
        assertNull(WikiPortSharing.address(" ", 25566))
        assertNull(WikiPortSharing.address("host", 0))
    }

    @Test
    fun `the wiki is off by default and its file reads back`() {
        assertFalse(WikiConfig().enabled)
        val config = WikiConfig(enabled = true, port = 8123, publicUrl = "http://play.example.com:8123/")
        assertEquals(config, WikiConfig.read(WikiConfig.write(config)))
        assertEquals("http://play.example.com:8123", config.base)
        assertEquals("http://localhost:8100", WikiConfig().base)
        val path = Files.createTempDirectory("wiki").resolve("wiki.json")
        assertEquals(WikiConfig(), WikiConfig.load(path))
        assertTrue(Files.exists(path))
        Files.writeString(path, "{")
        assertFalse(WikiConfig.load(path).enabled)
    }

    @Test
    fun `a token names one player, survives a restart, and a reset ends the old one`() {
        val file = Files.createTempDirectory("wiki").resolve("data").resolve("tokens.json")
        val alex = UUID.randomUUID()
        val sam = UUID.randomUUID()
        val tokens = WikiTokens(file)
        val first = tokens.tokenFor(alex)
        assertEquals(first, tokens.tokenFor(alex))
        assertNotEquals(first, tokens.tokenFor(sam))
        assertTrue(first.length >= 32)
        assertEquals(alex, tokens.playerFor(first))
        assertNull(tokens.playerFor(first.dropLast(1)))

        val restarted = WikiTokens(file).also(WikiTokens::load)
        assertEquals(alex, restarted.playerFor(first))
        val second = restarted.reset(alex)
        assertNull(restarted.playerFor(first))
        assertEquals(alex, restarted.playerFor(second))
    }
}
