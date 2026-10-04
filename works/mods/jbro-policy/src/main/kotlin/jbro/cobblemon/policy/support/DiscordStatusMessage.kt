package jbro.cobblemon.policy.support

/** Posts a fresh chat message for each server start or normal shutdown; no status card or saved message ID. */
internal class DiscordStatusMessage(
    private val rest: DiscordRest,
    private val channelId: String,
    private val translate: (String) -> String? = KoreanText::translate,
) {
    /** Posts the server's lifecycle notification. Discord failures are reported to the caller for logging. */
    fun show(open: Boolean) {
        val response = rest.request("POST", "/channels/$channelId/messages", payload(open, translate))
        check(response.ok) { "Discord answered ${response.status} while posting the server status: ${response.body.take(200)}" }
    }

    companion object {
        private const val KEY = "message.jbro_policy.discord_status."

        fun payload(open: Boolean, translate: (String) -> String?) = DiscordRest.message(
            translate(KEY + if (open) "open" else "closed")
                ?: if (open) "🟢 The server is open!" else "🔴 The server is closed.",
        )
    }
}
