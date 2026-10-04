package jbro.cobblemon.policy.support

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What Pichu may settle without an operator. The reviewer reads text a player wrote, so its word alone never runs a
 * command: only a verdict backed by the records, of a kind that can be settled, with commands of the one allowed
 * shape that pay the asking player within the limits, runs. Everything else goes to an operator.
 */
internal object InquiryAutoResolve {
    data class Limits(val maxBpPerInquiry: Long, val maxBpPerDay: Long)

    sealed interface Plan {
        /** Run these, as the server; each pays the BP at its place in [amounts]. */
        data class Run(val commands: List<String>, val amounts: List<Long>) : Plan

        /** Settled by the answer alone; nothing to run. */
        data object Explain : Plan

        data class HandOver(val why: String) : Plan
    }

    private val BP_ADD = Regex("^/?bp add (\\S+) (\\d{1,9})(?: .*)?$", RegexOption.IGNORE_CASE)

    private val AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /**
     * Wins in the log window that no content reward answers, counted by the server itself: `Battle record: … WIN`
     * lines for [accountName] minus the CONTENT_REWARD entries of [playerData]'s BP history in the same window. A
     * reward lands in the same second as its win, just before the record, so the window is widened by a few seconds.
     * Null when the data cannot settle it: no player data, or a history too short to reach back to [from].
     */
    fun unpaidWins(windowText: String, accountName: String, playerData: String?, from: LocalDateTime, to: LocalDateTime): Int? {
        val history = playerData?.let { data ->
            runCatching { JsonParser.parseString(data).asJsonObject.getAsJsonArray("bp_history") }.getOrNull()
        } ?: return null
        val times = history.mapNotNull { entry ->
            val item = entry as? JsonObject ?: return@mapNotNull null
            val at = item.get("at")?.asString?.removeSuffix(" KST")?.let { runCatching { LocalDateTime.parse(it, AT) }.getOrNull() }
                ?: return@mapNotNull null
            at to (item.get("kind")?.asString == "CONTENT_REWARD")
        }
        // The history holds the newest transactions only; if even its oldest is inside the window, rewards may be missing from it.
        if (times.size >= HISTORY_LIMIT && times.minOf { it.first } >= from) return null
        val start = from.minusSeconds(SLACK_SECONDS)
        val end = to.plusSeconds(SLACK_SECONDS)
        val rewards = times.count { (at, reward) -> reward && !at.isBefore(start) && !at.isAfter(end) }
        val wins = windowText.lineSequence().count { line -> "Battle record: $accountName (" in line && ") WIN " in line }
        return wins - rewards
    }

    private const val HISTORY_LIMIT = 20
    private const val SLACK_SECONDS = 5L

    fun plan(verdict: InquiryVerdict, accountName: String, inquiryId: String, paidToday: Long, limits: Limits, unpaidWins: Int? = null): Plan {
        if (verdict.resolution != InquiryVerdict.Resolution.AUTO) return Plan.HandOver("검토자가 운영자 판단이 필요하다고 봤어요")
        if (verdict.verdict == InquiryVerdict.Verdict.UNKNOWN) return Plan.HandOver("판단할 기록이 없어요")
        if (verdict.category == InquiryVerdict.Category.REPORT || verdict.category == InquiryVerdict.Category.SUGGESTION) {
            return Plan.HandOver("${verdict.category.label}은 운영자가 봐요")
        }
        if (verdict.autoCommands.isEmpty()) return Plan.Explain
        if (verdict.verdict != InquiryVerdict.Verdict.MATCH || verdict.category != InquiryVerdict.Category.REWARD) {
            return Plan.HandOver("보상 누락이 기록으로 확인된 문의만 자동으로 처리해요")
        }
        // The reviewer's word is not enough to pay: the server must count a win that went unpaid.
        if (unpaidWins == null) return Plan.HandOver("보상 기록을 서버가 직접 확인할 수 없어요")
        if (unpaidWins <= 0) return Plan.HandOver("검토 구간의 승리는 모두 보상을 받은 것으로 보여요")
        val amounts = verdict.autoCommands.map { command ->
            val match = BP_ADD.matchEntire(command.trim()) ?: return Plan.HandOver("자동으로 실행할 수 없는 명령이에요: $command")
            if (!match.groupValues[1].equals(accountName, ignoreCase = true)) return Plan.HandOver("문의한 플레이어가 아닌 대상이에요: $command")
            match.groupValues[2].toLong().takeIf { it > 0 } ?: return Plan.HandOver("지급량이 0이에요")
        }
        if (amounts.size > unpaidWins) return Plan.HandOver("지급 명령이 보상 못 받은 승리 수(${unpaidWins}번)보다 많아요")
        val total = amounts.sum()
        if (total > limits.maxBpPerInquiry) return Plan.HandOver("한 번에 자동 지급할 수 있는 ${limits.maxBpPerInquiry} BP를 넘어요 ($total BP)")
        if (paidToday + total > limits.maxBpPerDay) return Plan.HandOver("오늘 자동 지급 한도 ${limits.maxBpPerDay} BP를 넘어요")
        // Written by the server, not the reviewer: the reason names the inquiry so the BP history shows where it came from.
        return Plan.Run(amounts.map { "bp add $accountName $it 피츄 자동 처리 (문의 $inquiryId)" }, amounts)
    }

    /** BP Pichu paid each player per day (KST), so the daily limit holds across restarts. */
    class Ledger(private val file: Path) {
        private val gson = Gson()

        @Synchronized
        fun paidToday(playerId: String, today: LocalDate = today()): Long =
            read().get("$playerId:$today")?.asLong ?: 0L

        @Synchronized
        fun add(playerId: String, bp: Long, today: LocalDate = today()) {
            val days = read()
            // Only today's entries matter; older ones are dropped as the file is rewritten.
            val kept = JsonObject().apply { days.entrySet().filter { it.key.endsWith(":$today") }.forEach { add(it.key, it.value) } }
            kept.addProperty("$playerId:$today", (kept.get("$playerId:$today")?.asLong ?: 0L) + bp)
            Files.createDirectories(file.parent)
            Files.writeString(file, gson.toJson(kept))
        }

        private fun read(): JsonObject = try {
            if (Files.exists(file)) JsonParser.parseString(Files.readString(file)).asJsonObject else JsonObject()
        } catch (failure: RuntimeException) { JsonObject() }

        private fun today(): LocalDate = LocalDate.now(ZoneId.of("Asia/Seoul"))
    }
}
