package jbro.cobblemon.policy.support

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A minimal SMTP client for one plain-text UTF-8 mail at a time, so the mod needs no mail library. [MailSettings.security]
 * picks implicit TLS (`ssl`, usually port 465) or an upgrade after connecting (`starttls`, usually 587); both log in
 * with AUTH LOGIN.
 */
internal object SmtpMailer {
    private const val TIMEOUT_MILLIS = 15_000

    class SmtpException(message: String) : Exception(message)

    fun send(settings: MailSettings, subject: String, body: String) {
        val plain = Socket().apply { soTimeout = TIMEOUT_MILLIS; connect(InetSocketAddress(settings.host, settings.port), TIMEOUT_MILLIS) }
        var socket: Socket = if (settings.security == "ssl") tls(plain, settings.host) else plain
        try {
            var session = Session(socket)
            session.expect(220)
            session.command("EHLO jbro-policy", 250)
            if (settings.security == "starttls") {
                session.command("STARTTLS", 220)
                socket = tls(socket, settings.host)
                session = Session(socket)
                session.command("EHLO jbro-policy", 250)
            }
            session.command("AUTH LOGIN", 334)
            session.command(base64(settings.username), 334)
            session.command(base64(settings.password), 235, secret = true)
            session.command("MAIL FROM:<${settings.from}>", 250)
            session.command("RCPT TO:<${settings.to}>", 250)
            session.command("DATA", 354)
            session.write(message(settings, subject, body) + "\r\n.\r\n")
            session.expect(250)
            session.command("QUIT", 221)
        } finally {
            socket.close()
        }
    }

    /** RFC 5322 headers and a base64 body, so no line of it can start with the dot that ends DATA. */
    internal fun message(settings: MailSettings, subject: String, body: String): String = buildString {
        append("From: <${settings.from}>\r\n")
        append("To: <${settings.to}>\r\n")
        append("Subject: =?UTF-8?B?${base64(subject)}?=\r\n")
        append("MIME-Version: 1.0\r\n")
        append("Content-Type: text/plain; charset=UTF-8\r\n")
        append("Content-Transfer-Encoding: base64\r\n")
        append("\r\n")
        append(Base64.getMimeEncoder().encodeToString(body.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun base64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray(StandardCharsets.UTF_8))

    private fun tls(socket: Socket, host: String): SSLSocket =
        ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket, host, socket.port, true) as SSLSocket)
            .apply { soTimeout = TIMEOUT_MILLIS; startHandshake() }

    private class Session(socket: Socket) {
        private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        private val out: OutputStream = socket.getOutputStream()

        fun write(text: String) {
            out.write(text.toByteArray(StandardCharsets.UTF_8))
            out.flush()
        }

        fun command(line: String, expected: Int, secret: Boolean = false) {
            write("$line\r\n")
            expect(expected, if (secret) "(credentials)" else line.substringBefore(':'))
        }

        /** Reads one reply, multi-line ones included, and fails unless its code is [expected]. */
        fun expect(expected: Int, after: String = "connect") {
            var line: String
            do {
                line = reader.readLine() ?: throw SmtpException("connection closed after $after")
            } while (line.length > 3 && line[3] == '-')
            val code = line.take(3).toIntOrNull()
            if (code != expected) throw SmtpException("$after: expected $expected, got ${line.take(200)}")
        }
    }
}
