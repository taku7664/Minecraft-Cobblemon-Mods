package jbro.cobblemon.policy.support

import com.google.gson.GsonBuilder
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import jbro.cobblemon.mcc.api.support.MccPlayerSnapshot
import net.minecraft.server.MinecraftServer

/**
 * What a review reads of the player besides the log: their BP with its latest transactions, their records and each
 * content's wiki section (the Legends they caught among them). Loaded only when More Cobblemon Contents is installed.
 */
internal object InquiryPlayerData {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    /** Read on the server thread, from the review's own. */
    fun of(server: MinecraftServer, playerId: UUID, maxCharacters: Int = 20_000): String =
        gson.toJson(server.submit(Supplier { MccPlayerSnapshot.of(server, playerId) }).get(10, TimeUnit.SECONDS)).take(maxCharacters)
}
