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

/**
 * Player inquiries mailed to the server owner, from `/문의 <reason>` (`/inquiry`) or the wiki's inquiry page. Each
 * names the player's nickname, account name and UUID; one player may send one every five minutes.
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
    private val mailer = Executors.newSingleThreadExecutor { task -> Thread(task, "jbro-policy-inquiry-mail").apply { isDaemon = true } }
    private var settings = MailSettings()

    internal fun register(settings: MailSettings) {
        this.settings = settings
        if (!settings.configured) JbroPolicy.LOGGER.info("Inquiries are off until config/jbro-policy-mail.json is filled in")
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
     * Checks [reason] and the cooldown, then mails the inquiry off the calling thread. The cooldown starts when the
     * mail is handed over and is given back if sending fails, so a mail server outage costs the player no wait.
     */
    fun submit(server: MinecraftServer, playerId: UUID, accountName: String, nickname: String, reason: String, via: Via): CompletableFuture<Outcome> {
        val text = clean(reason)
        val now = System.currentTimeMillis()
        val early = when {
            !settings.configured -> Outcome.NotConfigured
            else -> check(text, now, lastSent[playerId])
        }
        if (early != null) return CompletableFuture.completedFuture(early)
        lastSent[playerId] = now
        val current = settings
        return CompletableFuture.supplyAsync({
            try {
                SmtpMailer.send(current, subject(nickname, accountName), body(nickname, accountName, playerId, text, via, now))
                JbroPolicy.LOGGER.info("Mailed an inquiry from {} ({}) via {}", accountName, playerId, via)
                Outcome.Sent
            } catch (failure: Exception) {
                JbroPolicy.LOGGER.warn("Could not mail the inquiry from {} ({})", accountName, playerId, failure)
                lastSent.remove(playerId, now)
                Outcome.Failed
            }
        }, mailer)
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

    internal fun body(nickname: String, accountName: String, playerId: UUID, reason: String, via: Via, at: Long): String {
        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(at), ZoneId.of("Asia/Seoul")))
        return """
            닉네임: $nickname
            아이디: $accountName
            UUID: $playerId
            경로: ${via.label}
            시각: $time (KST)

            사유:
            $reason
        """.trimIndent()
    }

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
