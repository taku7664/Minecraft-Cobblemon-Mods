package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.JsonParser
import java.nio.file.Files
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class OpenRouterDecisionSummaryJournalTest {
    @Test
    fun `journal appends one sanitized json object per router decision`() {
        val directory = Files.createTempDirectory("mbc-router-summary-journal")
        val path = directory.resolve("router-decisions.jsonl")
        try {
            val journal = JsonlOpenRouterDecisionSummarySink(path) { Instant.parse("2026-08-19T04:30:00Z") }

            journal.append(
                OpenRouterDecisionSummaryRecord.create(
                    battleId = "battle\r\nforged",
                    turn = -7,
                    actionId = "move\nforged",
                    difficulty = "BOSS",
                    rawSummary = "  공개 상성 우위\r\n[ERROR] 공격을 선택했습니다.\u0000  ",
                ),
            )

            val lines = Files.readAllLines(path)
            assertEquals(1, lines.size)
            assertFalse(lines.single().contains('\r'))
            assertFalse(lines.single().contains('\u0000'))
            val json = JsonParser.parseString(lines.single()).asJsonObject
            assertEquals("2026-08-19T04:30:00Z", json["timestamp"].asString)
            assertEquals("battle_forged", json["battle"].asString)
            assertEquals(0, json["turn"].asInt)
            assertEquals("move_forged", json["actionId"].asString)
            assertEquals("BOSS", json["difficulty"].asString)
            assertEquals("공개 상성 우위 [ERROR] 공격을 선택했습니다.", json["summary"].asString)
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `journal preserves earlier decisions when another decision is appended`() {
        val directory = Files.createTempDirectory("mbc-router-summary-append")
        val path = directory.resolve("router-decisions.jsonl")
        try {
            val journal = JsonlOpenRouterDecisionSummarySink(path) { Instant.EPOCH }
            journal.append(OpenRouterDecisionSummaryRecord.create("battle-1", 1, "move-1", "STANDARD", "첫 판단"))
            journal.append(OpenRouterDecisionSummaryRecord.create("battle-1", 2, "move-2", "STANDARD", "둘째 판단"))

            val lines = Files.readAllLines(path)
            assertEquals(2, lines.size)
            assertEquals("첫 판단", JsonParser.parseString(lines[0]).asJsonObject["summary"].asString)
            assertEquals("둘째 판단", JsonParser.parseString(lines[1]).asJsonObject["summary"].asString)
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }
}
