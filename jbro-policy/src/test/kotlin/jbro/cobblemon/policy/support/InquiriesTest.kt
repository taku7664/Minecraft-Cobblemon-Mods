package jbro.cobblemon.policy.support

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import kotlin.concurrent.thread
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class InquiriesTest {
    private val id = UUID.fromString("f3d28cb0-7225-3cb1-baeb-2dadd2be89ae")

    @Test
    fun `reasons are one line of at most 100 characters`() {
        assertEquals("버그 신고 합니다", Inquiries.clean("  버그\n신고   합니다 "))
        assertEquals(Inquiries.Outcome.Empty, Inquiries.check("", 0, null))
        assertNull(Inquiries.check("가".repeat(100), 0, null))
        assertEquals(Inquiries.Outcome.TooLong, Inquiries.check("가".repeat(101), 0, null))
    }

    @Test
    fun `one inquiry every five minutes`() {
        val wait = Inquiries.check("질문", 60_000, 0) as Inquiries.Outcome.Cooldown
        assertEquals(4, wait.minutes)
        assertEquals(1, (Inquiries.check("질문", Inquiries.COOLDOWN_MILLIS - 1, 0) as Inquiries.Outcome.Cooldown).minutes)
        assertNull(Inquiries.check("질문", Inquiries.COOLDOWN_MILLIS, 0))
    }

    @Test
    fun `the mail names the player`() {
        val body = Inquiries.body("김빡주", "Park_JH", id, "광장에 못 가요", Inquiries.Via.WIKI, 0)
        for (part in listOf("닉네임: 김빡주", "아이디: Park_JH", "UUID: $id", "경로: 위키", "1970-01-01 09:00:00", "광장에 못 가요")) {
            assertTrue(part in body) { "missing $part" }
        }
        assertEquals("[빡켓몬 문의] 김빡주 (Park_JH)", Inquiries.subject("김빡주", "Park_JH"))
    }

    @Test
    fun `mail settings stay off until filled in`() {
        assertFalse(MailSettings().configured)
        val filled = MailSettings.parse("""{"username": "a@b.c", "password": "p", "from": "a@b.c", "to": "d@e.f"}""")
        assertTrue(filled.configured)
        assertEquals("smtp.gmail.com", filled.host)
        assertThrows<IllegalArgumentException> { MailSettings.parse("""{"security": "tls"}""") }
    }

    @Test
    fun `an SMTP server receives the inquiry`() {
        ServerSocket(0).use { server ->
            var received = ""
            val auth = mutableListOf<String>()
            val fake = thread {
                server.accept().use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                    val out = socket.getOutputStream()
                    fun reply(line: String) { out.write("$line\r\n".toByteArray()); out.flush() }
                    reply("220 fake")
                    while (true) {
                        val line = reader.readLine() ?: break
                        when {
                            line.startsWith("EHLO") -> { reply("250-fake"); reply("250 AUTH LOGIN") }
                            line == "AUTH LOGIN" -> reply("334 VXNlcm5hbWU6")
                            auth.size < 2 && !line.contains(':') && !line.contains(' ') -> {
                                auth += String(Base64.getDecoder().decode(line)); reply(if (auth.size == 1) "334 UGFzc3dvcmQ6" else "235 ok")
                            }
                            line.startsWith("MAIL FROM") || line.startsWith("RCPT TO") -> reply("250 ok")
                            line == "DATA" -> {
                                reply("354 go")
                                received = generateSequence { reader.readLine() }.takeWhile { it != "." }.joinToString("\n")
                                reply("250 queued")
                            }
                            line == "QUIT" -> { reply("221 bye"); break }
                        }
                    }
                }
            }
            val settings = MailSettings("127.0.0.1", server.localPort, "none", "bot@example.com", "secret", "bot@example.com", "owner@example.com")
            SmtpMailer.send(settings, "[빡켓몬 문의] 김빡주 (Park_JH)", Inquiries.body("김빡주", "Park_JH", id, "질문", Inquiries.Via.COMMAND, 0))
            fake.join(5_000)
            assertEquals(listOf("bot@example.com", "secret"), auth)
            assertTrue("To: <owner@example.com>" in received)
            val subject = received.lines().first { it.startsWith("Subject:") }.removePrefix("Subject: =?UTF-8?B?").removeSuffix("?=")
            assertEquals("[빡켓몬 문의] 김빡주 (Park_JH)", String(Base64.getDecoder().decode(subject), StandardCharsets.UTF_8))
            val body = String(Base64.getMimeDecoder().decode(received.substringAfter("\n\n")), StandardCharsets.UTF_8)
            assertTrue("UUID: $id" in body && "질문" in body)
        }
    }
}
