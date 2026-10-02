package jbro.cobblemon.policy.support

import com.mojang.brigadier.arguments.StringArgumentType
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import jbro.cobblemon.policy.JbroPolicy
import jbro.cobblemon.policy.api.PlayerLog
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.ChatFormatting
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/** One inquiry as the operators receive it on Discord. */
data class Inquiry(
    val nickname: String,
    val accountName: String,
    val playerId: UUID,
    val reason: String,
    val via: Inquiries.Via,
    val at: Long,
    /** Eight hex digits that name the inquiry on its card and in its review. */
    val id: String = UUID.randomUUID().toString().take(8),
) {
    val subject: String get() = "[빡켓몬 문의] $nickname ($accountName)"
    val time: String get() = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .format(ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(at), ZoneId.of("Asia/Seoul")))
}

/**
 * Player inquiries sent to the operators, from `/문의 <reason>` (`/inquiry`) or the wiki's inquiry page, posted to a
 * Discord channel. Each names the player's nickname, account name and UUID; one player may send one every five minutes.
 */
object Inquiries {
    const val MAX_REASON_LENGTH = 100
    const val COOLDOWN_MILLIS = 5 * 60 * 1000L
    private const val KEY = "message.${JbroPolicy.MOD_ID}.inquiry."

    enum class Via(val label: String) { COMMAND("명령어"), WIKI("위키") }

    sealed interface Outcome {
        data object Sent : Outcome
        data object Empty : Outcome
        data object TooLong : Outcome
        data class Cooldown(val remainingMillis: Long) : Outcome {
            /** Whole minutes, rounded up, so "1분" still means there is some wait left. */
            val minutes: Long get() = (remainingMillis + 59_999) / 60_000
        }
        data object NotConfigured : Outcome
        data object Failed : Outcome
    }

    private val lastSent = ConcurrentHashMap<UUID, Long>()
    private val sender = Executors.newSingleThreadExecutor { task -> Thread(task, "jbro-policy-inquiry").apply { isDaemon = true } }
    private var discord = DiscordSettings()

    private val configured: Boolean get() = discord.configured

    internal fun register(discord: DiscordSettings) {
        this.discord = discord
        if (!configured) {
            JbroPolicy.LOGGER.info("Inquiries are off until config/jbro-policy-discord.json names an inquiry channel or webhook")
        }
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            for (name in listOf("문의", "inquiry")) {
                dispatcher.register(Commands.literal(name)
                    .then(Commands.argument("reason", StringArgumentType.greedyString()).executes { context ->
                        val player = context.source.playerOrException
                        submit(player.server, player.uuid, player.gameProfile.name, player.displayName?.string ?: player.gameProfile.name,
                            StringArgumentType.getString(context, "reason"), Via.COMMAND).thenAccept { outcome ->
                            player.server.execute { tell(player, outcome) }
                        }
                        1
                    }))
            }
        }
    }

    /**
     * Checks [reason] and the cooldown, then posts the inquiry off the calling thread. The cooldown starts when the
     * inquiry is handed over and is given back if Discord fails, so an outage costs the player no wait.
     */
    fun submit(server: MinecraftServer, playerId: UUID, accountName: String, nickname: String, reason: String, via: Via): CompletableFuture<Outcome> {
        val text = clean(reason)
        val now = System.currentTimeMillis()
        val route = discord.inquiryRoute ?: return CompletableFuture.completedFuture(Outcome.NotConfigured)
        check(text, now, lastSent[playerId])?.let { return CompletableFuture.completedFuture(it) }
        lastSent[playerId] = now
        val inquiry = Inquiry(nickname, accountName, playerId, text, via, now)
        return CompletableFuture.supplyAsync({
            try {
                val card = DiscordWebhook.send(route, inquiry, DiscordLinks.discordOf(playerId))
                JbroPolicy.LOGGER.info("Sent inquiry {} from {} ({}) via {} to Discord", inquiry.id, accountName, playerId, via)
                // Reviews answer under the card, which only the bot's own posts let them do.
                if (card != null) InquiryReview.enqueue(inquiry, card.messageId, card.channelId)
                Outcome.Sent
            } catch (failure: Exception) {
                JbroPolicy.LOGGER.warn("Could not send the inquiry from {} ({}) to Discord", accountName, playerId, failure)
                lastSent.remove(playerId, now)
                Outcome.Failed
            }
        }, sender)
    }

    /** Newlines and runs of spaces fold into one space, so a reason stays one line. */
    internal fun clean(reason: String): String = reason.replace(Regex("\\s+"), " ").trim()

    /** Why [reason] cannot be sent at [now], or null when it can. */
    internal fun check(reason: String, now: Long, last: Long?): Outcome? = when {
        reason.isEmpty() -> Outcome.Empty
        reason.codePointCount(0, reason.length) > MAX_REASON_LENGTH -> Outcome.TooLong
        last != null && now - last < COOLDOWN_MILLIS -> Outcome.Cooldown(COOLDOWN_MILLIS - (now - last))
        else -> null
    }

    internal fun subject(nickname: String, accountName: String) = "[빡켓몬 문의] $nickname ($accountName)"

    private fun tell(player: ServerPlayer, outcome: Outcome) {
        when (outcome) {
            Outcome.Sent -> PlayerLog.send(player, Component.translatable(KEY + "sent"), ChatFormatting.GREEN)
            Outcome.Empty -> PlayerLog.send(player, Component.translatable(KEY + "empty"), ChatFormatting.RED)
            Outcome.TooLong -> PlayerLog.send(player, Component.translatable(KEY + "too_long", MAX_REASON_LENGTH), ChatFormatting.RED)
            is Outcome.Cooldown -> PlayerLog.send(player, Component.translatable(KEY + "cooldown", outcome.minutes), ChatFormatting.RED)
            Outcome.NotConfigured -> PlayerLog.send(player, Component.translatable(KEY + "not_configured"), ChatFormatting.RED)
            Outcome.Failed -> PlayerLog.send(player, Component.translatable(KEY + "failed"), ChatFormatting.RED)
        }
    }
}
