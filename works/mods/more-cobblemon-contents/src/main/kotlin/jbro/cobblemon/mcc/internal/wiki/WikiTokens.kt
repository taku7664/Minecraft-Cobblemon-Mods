package jbro.cobblemon.mcc.internal.wiki

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import jbro.cobblemon.mcc.MoreCobblemonContents

/**
 * Each player's wiki token: a random secret the wiki's `/api/me` takes to know whose data to answer with. A player
 * gets one the first time they ask for their link and can replace it, which makes every old link stop working.
 * Kept in the world's data folder, so they survive restarts and stay with the world.
 */
internal class WikiTokens(private val file: Path) {
    private val byPlayer = HashMap<UUID, String>()
    private val byToken = HashMap<String, UUID>()

    @Synchronized
    fun tokenFor(playerId: UUID): String = byPlayer[playerId] ?: issue(playerId)

    /** A fresh token for [playerId]; the old one stops working. */
    @Synchronized
    fun reset(playerId: UUID): String {
        byPlayer.remove(playerId)?.let(byToken::remove)
        return issue(playerId)
    }

    @Synchronized
    fun playerFor(token: String): UUID? = byToken.entries.firstOrNull { (known, _) -> same(known, token) }?.value

    private fun issue(playerId: UUID): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        byPlayer[playerId] = token
        byToken[token] = playerId
        save()
        return token
    }

    @Synchronized
    fun load() {
        byPlayer.clear()
        byToken.clear()
        if (Files.notExists(file)) return
        try {
            JsonParser.parseString(Files.readString(file)).asJsonObject.entrySet().forEach { (id, token) ->
                val playerId = UUID.fromString(id)
                byPlayer[playerId] = token.asString
                byToken[token.asString] = playerId
            }
        } catch (failure: Exception) {
            MoreCobblemonContents.LOGGER.error("Wiki tokens {} could not be read; players get new links", file, failure)
        }
    }

    private fun save() {
        val json = JsonObject().also { root -> byPlayer.forEach { (id, token) -> root.addProperty(id.toString(), token) } }
        try {
            Files.createDirectories(file.parent)
            Files.writeString(file, GsonBuilder().setPrettyPrinting().create().toJson(json) + "\n")
        } catch (failure: Exception) {
            MoreCobblemonContents.LOGGER.error("Wiki tokens {} could not be saved", file, failure)
        }
    }

    private companion object {
        const val TOKEN_BYTES = 24
        val random = SecureRandom()

        /** Compares in constant time, so a wrong guess does not tell how much of it was right. */
        fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    }
}
