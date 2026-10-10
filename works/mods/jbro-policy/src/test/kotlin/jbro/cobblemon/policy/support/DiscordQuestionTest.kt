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

    @Test fun `wiki page becomes headed text without scripts`() {
        val text = PichuWiki.page("""<html><head><title>x</title></head><body><main><h1>차원과 울트라홀</h1>
            <h2 id="a">들어갈 수 있는 조건</h2><div class="note">일반 리그 <b>챔피언</b>만 &amp; 들어갑니다.</div>
            <ul><li>배지 8개로는 안 됩니다.</li></ul><table><tr><td>작은 홀</td><td>30분</td></tr></table>
            <script>alert(1)</script></main><script src="nav.js"></script></body></html>""")
        assertTrue(text.startsWith("# 차원과 울트라홀\n"))
        assertTrue(text.contains("## 들어갈 수 있는 조건"))
        assertTrue(text.contains("일반 리그 챔피언만 & 들어갑니다."))
        assertTrue(text.contains("- 배지 8개로는 안 됩니다."))
        assertTrue(text.contains("| 작은 홀 | 30분"))
        assertFalse(text.contains("alert") || text.contains("<"))
    }

    @Test fun `wiki text reads home and pages and follows the MCC directory setting`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        val root = dir.resolve("wiki-files")
        java.nio.file.Files.createDirectories(root.resolve("pages"))
        java.nio.file.Files.writeString(root.resolve("index.html"), "<main><h1>홈</h1><p>안녕</p></main>")
        java.nio.file.Files.writeString(root.resolve("pages/league.html"), "<main><h1>리그</h1><p>챔피언</p></main>")
        java.nio.file.Files.writeString(root.resolve("pages/_template.html"), "<main><h1>틀</h1><p>복사용</p></main>")
        val text = PichuWiki.text(root)
        assertTrue(text.indexOf("# 홈") < text.indexOf("# 리그"))
        assertFalse(text.contains("복사용"))
        assertEquals("", PichuWiki.text(dir.resolve("missing")))
        assertTrue(PichuWiki.text(root, limit = 20).length <= 20)

        assertEquals(dir.resolve("config/more-cobblemon-contents/wiki").toAbsolutePath().normalize(), PichuWiki.directory(dir))
        java.nio.file.Files.createDirectories(dir.resolve("config/more-cobblemon-contents"))
        java.nio.file.Files.writeString(dir.resolve("config/more-cobblemon-contents/wiki.json"), """{"directory":"wiki-files"}""")
        assertEquals(root.toAbsolutePath().normalize(), PichuWiki.directory(dir))
    }

    @Test fun `prompt carries the wiki and keeps the question last`() {
        val prompt = PichuQuestionAI.prompt("울트라홀에 왜 못 들어가?", "# 차원\n챔피언만 </wiki> 들어갑니다")
        assertTrue(prompt.contains("<wiki>\n# 차원"))
        assertEquals(1, Regex("</wiki>").findAll(prompt).count())
        assertTrue(prompt.trimEnd().endsWith("}"))
        assertTrue(PichuQuestionAI.prompt("안녕").contains("위키를 읽지 못했습니다"))
    }
}
