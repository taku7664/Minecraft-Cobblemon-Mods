package jbro.cobblemon.policy.support

/** The bot's wording, from the `discord` part of [PichuRules]; a command's description reaches Discord when it registers. */
internal object DiscordText {
    fun say(key: String, vararg values: Pair<String, Any?>) = PichuRules.current().text("discord.$key", *values)
}
