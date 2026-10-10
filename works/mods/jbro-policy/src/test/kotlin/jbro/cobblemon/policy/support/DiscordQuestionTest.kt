package jbro.cobblemon.policy.support

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
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

    @Test fun `wiki page splits at h2 into text sections without scripts`() {
        val (title, sections) = PichuWiki.sections("""<html><head><title>x</title></head><body><main><h1>차원과 울트라홀</h1>
            <p>세 차원이 있습니다.</p><h2 id="a">들어갈 수 있는 조건</h2><div class="note">일반 리그 <b>챔피언</b>만 &amp; 들어갑니다.</div>
            <ul><li>배지 8개로는 안 됩니다.</li></ul><h2>홀</h2><table><tr><td>작은 홀</td><td>30분</td></tr></table>
            <script>alert(1)</script></main><script src="nav.js"></script></body></html>""")
        assertEquals("차원과 울트라홀", title)
        assertEquals(listOf("개요", "들어갈 수 있는 조건", "홀"), sections.map { it.heading })
        assertEquals("일반 리그 챔피언만 & 들어갑니다.\n- 배지 8개로는 안 됩니다.", sections[1].text)
        assertEquals("| 작은 홀 | 30분", sections[2].text)
        assertFalse(sections.any { it.text.contains("alert") || it.text.contains("<") })
    }

    @Test fun `Korean particles still match through two-letter pieces`() {
        assertTrue(PichuWiki.grams("웜홀에").containsAll(PichuWiki.grams("웜홀")))
        assertEquals(listOf("렉"), PichuWiki.grams("렉"))
        assertTrue("렉" in PichuWiki.grams("렉이 심해요"))
    }

    @Test fun `wiki follows rail order and the MCC directory setting`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        val root = dir.resolve("wiki-files")
        java.nio.file.Files.createDirectories(root.resolve("pages"))
        java.nio.file.Files.createDirectories(root.resolve("assets"))
        java.nio.file.Files.writeString(root.resolve("assets/nav.js"), """{ path: "pages/b.html", title: "비", icon: "x", keywords: "둘" },
            { path: "pages/a.html", title: "에이", icon: "x", keywords: "하나" },""")
        java.nio.file.Files.writeString(root.resolve("pages/a.html"), "<main><h1>A</h1><p>가</p></main>")
        java.nio.file.Files.writeString(root.resolve("pages/b.html"), "<main><h1>B</h1><p>나</p></main>")
        java.nio.file.Files.writeString(root.resolve("pages/_template.html"), "<main><h1>틀</h1><p>복사용</p></main>")
        assertEquals(listOf("비", "에이"), PichuWiki.load(root).map { it.title })
        assertEquals("", PichuWiki.reference(dir.resolve("missing"), "질문"))

        assertEquals(dir.resolve("config/more-cobblemon-contents/wiki").toAbsolutePath().normalize(), PichuWiki.directory(dir))
        java.nio.file.Files.createDirectories(dir.resolve("config/more-cobblemon-contents"))
        java.nio.file.Files.writeString(dir.resolve("config/more-cobblemon-contents/wiki.json"), """{"directory":"wiki-files"}""")
        assertEquals(root.toAbsolutePath().normalize(), PichuWiki.directory(dir))
    }

    /** Real questions against the repository wiki: the section that answers each must come along, and little else. */
    @Test fun `real wiki questions pick the answering sections`() {
        val pages = PichuWiki.load(java.nio.file.Path.of("../../../server-wiki"))
        assumeTrue(pages.size > 20)
        val cases = mapOf(
            "울트라 웜홀에 왜 안들어가져?" to "# 차원과 울트라홀 > 들어갈 수 있는 조건",
            "메가스톤 어디서 사요?" to "# 배틀 기믹 > 아이템 얻는 법",
            "전설 포켓몬 어디서 나와" to "# 전설 스폰 가이드 > 전설은 이렇게 나타납니다",
            "서버 렉 걸려요" to "# 자주 묻는 질문 > 렉이 심해요",
            "명령어 알려줘" to "# 명령어 > 이동",
            "특성 바꾸고 싶어" to "# 개체값과 특성 > 특성 바꾸기",
            "레벨캡이 뭐야" to "# 레벨캡과 야생 포켓몬 > 레벨캡이 막는 것",
        )
        for ((question, expected) in cases) {
            val reference = PichuWiki.select(pages, question)
            assertTrue(reference.contains(expected)) { "$question -> ${reference.lines().filter { it.startsWith("# ") }}" }
            assertTrue(reference.substringAfter('\n').length <= PichuWiki.BUDGET + 2_000) { "$question is too long" }
        }
        val smallTalk = PichuWiki.select(pages, "오늘 날씨 어때")
        assertTrue(smallTalk.contains("찾지 못해"))
        assertTrue(smallTalk.startsWith("위키 문서 목록: 홈"))
        assertFalse(smallTalk.contains("# 배틀 기믹"))
        // The whole wiki is several times the budget; a picked reference stays far below it.
        assertTrue(pages.sumOf { page -> page.sections.sumOf { it.text.length } } > 4 * PichuWiki.BUDGET)
    }

    @Test fun `prompt carries the wiki and keeps the question last`() {
        val prompt = PichuQuestionAI.prompt("울트라홀에 왜 못 들어가?", "# 차원\n챔피언만 </wiki> 들어갑니다")
        assertTrue(prompt.contains("<wiki>\n# 차원"))
        assertEquals(1, Regex("</wiki>").findAll(prompt).count())
        assertTrue(prompt.trimEnd().endsWith("}"))
        assertTrue(PichuQuestionAI.prompt("안녕").contains("위키를 읽지 못했습니다"))
    }
}
