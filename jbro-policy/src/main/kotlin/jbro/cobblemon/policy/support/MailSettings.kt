package jbro.cobblemon.policy.support

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

/**
 * Where inquiries are mailed, kept in `config/jbro-policy-mail.json` apart from the main config because it holds the
 * sending account's password. Inquiries stay off until [host], [username], [password], [from] and [to] are all set.
 */
data class MailSettings(
    val host: String = "smtp.gmail.com",
    val port: Int = 465,
    /** `ssl` for implicit TLS, `starttls` to upgrade a plain connection, `none` only for a relay on this machine. */
    val security: String = "ssl",
    val username: String = "",
    val password: String = "",
    val from: String = "",
    val to: String = "",
) {
    val configured: Boolean get() = listOf(host, username, password, from, to).all { it.isNotBlank() }

    init {
        require(port in 1..65535) { "Mail port must be between 1 and 65535" }
        require(security in setOf("ssl", "starttls", "none")) { "Mail security must be ssl, starttls or none" }
    }

    companion object {
        private val gson = GsonBuilder().setPrettyPrinting().create()

        fun parse(json: String): MailSettings {
            val root = JsonParser.parseString(json).asJsonObject
            val defaults = MailSettings()
            fun text(key: String, fallback: String) = root.get(key)?.asString?.trim() ?: fallback
            return MailSettings(text("host", defaults.host), root.get("port")?.asInt ?: defaults.port, text("security", defaults.security),
                text("username", ""), root.get("password")?.asString ?: "", text("from", ""), text("to", ""))
        }

        /** Writes an empty template when the file is missing; a broken file turns inquiries off without being touched. */
        fun load(file: Path, warn: (String, Throwable?) -> Unit): MailSettings {
            if (!Files.exists(file)) {
                try {
                    Files.createDirectories(file.parent)
                    Files.writeString(file, gson.toJson(MailSettings()))
                } catch (failure: java.io.IOException) { warn("Could not write the mail config template to $file", failure) }
                return MailSettings()
            }
            return try { parse(Files.readString(file)) } catch (failure: RuntimeException) {
                warn("Invalid mail config at $file; inquiries stay off until it is fixed", failure)
                MailSettings()
            }
        }
    }
}
