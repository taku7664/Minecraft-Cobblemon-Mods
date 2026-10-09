package jbro.cobblemon.policy.support

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DiscordQuestionTest {
    @Test fun `AI output rejects errors blank answers and unexpected tools`() {
        assertEquals("피츄!", PichuQuestionAI.parseOutput("""{"event":"result","result":{"status":"SUCCESS","response":"피츄!"}}"""))
        assertThrows(IllegalStateException::class.java) {
            PichuQuestionAI.parseOutput("""{"event":"result","result":{"status":"ERROR","response":"secret"}}""")
        }
        assertThrows(IllegalStateException::class.java) {
            PichuQuestionAI.parseOutput("""{"event":"result","result":{"status":"SUCCESS","response":" "}}""")
        }
        assertThrows(IllegalStateException::class.java) {
            PichuQuestionAI.parseOutput("""{"event":"step_update","step_update":{"step_type":"tool","tool_name":"run_command"}}
{"event":"result","result":{"status":"SUCCESS","response":"answer"}}""")
        }
    }

    @Test fun `cooldown belongs to user across channels and expires at five minutes`() {
        var now = 0L
        val gate = QuestionCooldown { now }
        assertEquals(0L, gate.acquire("one"))
        assertTrue(gate.acquire("one") > 0)
        assertEquals(0L, gate.acquire("two"))
        gate.finish("one", true)
        now = 299_999L
        assertEquals(1L, gate.acquire("one"))
        now = 300_000L
        assertEquals(0L, gate.acquire("one"))
    }

    @Test fun `pending calls cannot bypass cooldown and failures allow retry`() {
        var now = 0L
        val gate = QuestionCooldown { now }
        assertEquals(0L, gate.acquire("one"))
        now = 600_000L
        assertTrue(gate.acquire("one") > 0)
        gate.finish("one", false)
        assertEquals(0L, gate.acquire("one"))
    }

    @Test fun `simultaneous requests admit only one call per user`() {
        val gate = QuestionCooldown { 0L }
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val calls = (1..8).map { pool.submit<Long> { start.await(); gate.acquire("one") } }
            start.countDown()
            assertEquals(1, calls.count { it.get(5, TimeUnit.SECONDS) == 0L })
        } finally { pool.shutdownNow() }
    }

    @Test fun `question command bypasses command channel and bounds its input`() {
        val command = DiscordQuestion { CompletableFuture.completedFuture("피카츄로 진화해요!") }
        val option = command.definition().getAsJsonArray("options")[0].asJsonObject
        assertEquals("피츄", command.name)
        assertEquals(500, option.get("max_length").asInt)
        assertNull(DiscordAdminAccess.channelRefusal(DiscordSettings(commandChannelId = "100"), command, "chat"))
        val caller = DiscordCaller("one", "user", emptyList(), "chat")
        assertTrue(command.replyAsync(mapOf("질문" to "피츄는 어떻게 진화해?"), caller).get().get("content").asString.contains("피카츄"))
        assertTrue(command.replyAsync(mapOf("질문" to "다시 질문"), caller).get().get("content").asString.contains("초"))
    }

    @Test fun `provider failure does not consume cooldown and invalid input never reaches provider`() {
        var calls = 0
        val command = DiscordQuestion {
            calls++
            if (calls == 1) CompletableFuture.failedFuture(IllegalStateException("private provider error"))
            else CompletableFuture.completedFuture("안녕하세요!")
        }
        val caller = DiscordCaller("one", "user", emptyList(), "chat")
        command.replyAsync(mapOf("질문" to " "), caller).get()
        command.replyAsync(mapOf("질문" to "x".repeat(501)), caller).get()
        assertEquals(0, calls)
        val failed = command.replyAsync(mapOf("질문" to "안녕"), caller).get().toString()
        assertFalse(failed.contains("private provider error"))
        assertTrue(command.replyAsync(mapOf("질문" to "안녕"), caller).get().toString().contains("안녕하세요"))
    }
}
