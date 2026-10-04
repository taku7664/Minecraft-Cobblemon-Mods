package jbro.cobblemon.policy.support

import com.google.gson.JsonObject
import jbro.cobblemon.mcc.api.wiki.WikiApi
import java.util.concurrent.TimeUnit

/**
 * The wiki's `/api/support/send?reason=…`. The wiki token names the player, so nobody can send in another's name;
 * the answer is `{"ok":true}` or `{"error":…}`, with `minutes` left for a cooldown. Loaded only when MCC is installed.
 */
internal object InquiryWikiEndpoint {
    fun register() {
        WikiApi.register("support/send") { request ->
            val playerId = request.viewer ?: return@register error("unknown_token")
            val server = request.server
            // Player data lives on the server thread; this handler runs on the wiki's HTTP thread.
            val (accountName, nickname) = server.submit<Pair<String, String>> {
                val online = server.playerList.getPlayer(playerId)
                val account = online?.gameProfile?.name ?: server.profileCache?.get(playerId)?.map { it.name }?.orElse(null) ?: playerId.toString()
                account to (online?.displayName?.string ?: account)
            }.get(10, TimeUnit.SECONDS)
            when (val outcome = Inquiries.submit(server, playerId, accountName, nickname, request.query["reason"].orEmpty(), Inquiries.Via.WIKI).get(30, TimeUnit.SECONDS)) {
                Inquiries.Outcome.Sent -> JsonObject().apply { addProperty("ok", true) }
                Inquiries.Outcome.Empty -> error("empty")
                Inquiries.Outcome.TooLong -> error("too_long").apply { addProperty("max", Inquiries.MAX_REASON_LENGTH) }
                is Inquiries.Outcome.Cooldown -> error("cooldown").apply { addProperty("minutes", outcome.minutes) }
                Inquiries.Outcome.NotConfigured -> error("not_configured")
                Inquiries.Outcome.Failed -> error("failed")
            }
        }
    }

    private fun error(code: String) = JsonObject().apply { addProperty("error", code) }
}
