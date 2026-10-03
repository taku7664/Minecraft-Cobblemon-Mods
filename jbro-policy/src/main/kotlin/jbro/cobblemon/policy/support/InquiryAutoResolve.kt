package jbro.cobblemon.policy.support

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId

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

    fun plan(verdict: InquiryVerdict, accountName: String, inquiryId: String, paidToday: Long, limits: Limits): Plan {
        if (verdict.resolution != InquiryVerdict.Resolution.AUTO) return Plan.HandOver("검토자가 운영자 판단이 필요하다고 봤어요")
        if (verdict.verdict == InquiryVerdict.Verdict.UNKNOWN) return Plan.HandOver("판단할 기록이 없어요")
        if (verdict.category == InquiryVerdict.Category.REPORT || verdict.category == InquiryVerdict.Category.SUGGESTION) {
            return Plan.HandOver("${verdict.category.label}은 운영자가 봐요")
        }
        if (verdict.autoCommands.isEmpty()) return Plan.Explain
        if (verdict.verdict != InquiryVerdict.Verdict.MATCH || verdict.category != InquiryVerdict.Category.REWARD) {
            return Plan.HandOver("보상 누락이 기록으로 확인된 문의만 자동으로 처리해요")
        }
        val amounts = verdict.autoCommands.map { command ->
            val match = BP_ADD.matchEntire(command.trim()) ?: return Plan.HandOver("자동으로 실행할 수 없는 명령이에요: $command")
            if (!match.groupValues[1].equals(accountName, ignoreCase = true)) return Plan.HandOver("문의한 플레이어가 아닌 대상이에요: $command")
            match.groupValues[2].toLong().takeIf { it > 0 } ?: return Plan.HandOver("지급량이 0이에요")
        }
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
