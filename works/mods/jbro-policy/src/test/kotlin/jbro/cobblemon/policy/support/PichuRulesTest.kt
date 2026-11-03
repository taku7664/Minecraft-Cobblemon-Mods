package jbro.cobblemon.policy.support

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PichuRulesTest {
    private val inquiry = Inquiry("빡주", "Park_JH", UUID.randomUUID(), "광장 이동이 안 돼요", Inquiries.Via.COMMAND, 1_793_000_000_000L, "0a1b2c3d")
    private fun prompt() = InquiryReviewer.prompt(inquiry, InquiryLogWindow.Window("", 0, false), 30, 5)

    @AfterEach fun back(@TempDir dir: Path) {
        val file = dir.resolve("after.json")
        Files.writeString(file, "{}")
        PichuRules.use(file) { _, _ -> }
    }

    @Test
    fun `a missing file gets the defaults and an edit applies at the next use`(@TempDir dir: Path) {
        val file = dir.resolve("jbro-policy-pichu.json")
        PichuRules.use(file) { _, _ -> }
        assertTrue("approvalCommands" in Files.readString(file))
        assertEquals("모호한 부분이 있어서 관리자에게 넘겼어요.", InquiryReviewer.HANDED_OVER)
        Files.writeString(file, """{"texts":{"handedOver":"운영자가 볼게요."},"inquiry":{"commands":["/새명령 <플레이어>: 새 기능"],"prompt":["지침 {accountName}","  {commands}"]}}""")
        assertEquals("운영자가 볼게요.", InquiryReviewer.HANDED_OVER)
        assertEquals("✅ 자료로 확인했어요", InquiryVerdict.Verdict.MATCH.label)
        assertTrue(prompt().startsWith("지침 Park_JH\n  - /새명령 <플레이어>: 새 기능\n"))
    }

    @Test
    fun `a broken file keeps the last good rules and warns once`(@TempDir dir: Path) {
        val file = dir.resolve("rules.json")
        val warnings = mutableListOf<String>()
        Files.writeString(file, """{"texts":{"handled":"끝났어요."}}""")
        PichuRules.use(file) { message, _ -> warnings += message }
        assertEquals("끝났어요.", InquiryReviewer.HANDLED)
        Files.writeString(file, """{"texts":""")
        assertEquals("끝났어요.", InquiryReviewer.HANDLED)
        assertEquals("끝났어요.", InquiryReviewer.HANDLED)
        assertEquals(1, warnings.size)
    }

    @Test
    fun `the approval list decides what goes on the button`(@TempDir dir: Path) {
        val file = dir.resolve("rules.json")
        PichuRules.use(file) { _, _ -> }
        val lookup = listOf(InquiryVerdict.SuggestedCommand("/legends zeraora Park_JH", ""))
        assertTrue(InquiryProposal.executable(lookup, "Park_JH").isEmpty())
        Files.writeString(file, """{"inquiry":{"approvalCommands":["legends"]}}""")
        assertEquals(lookup, InquiryProposal.executable(lookup, "Park_JH"))
    }

    @Test
    fun `a value's own braces are not filled again`() {
        val rules = PichuRules.current()
        assertEquals("문의 {id} 추가 설명: {text} 그대로", rules.text("texts.followUpPosted", "id" to "{id}", "text" to "{text} 그대로"))
    }

    @Test
    fun `the tones and their shares come from the rules`(@TempDir dir: Path) {
        val file = dir.resolve("rules.json")
        Files.writeString(file, """{"question":{"tones":[{"minShare":0,"tone":"다정"},{"minShare":0.9,"tone":"긁기"}]}}""")
        PichuRules.use(file) { _, _ -> }
        assertEquals(0, PichuQuestionAI.tier(QuestionStats.Tally(1, 1)))
        assertEquals(1, PichuQuestionAI.tier(QuestionStats.Tally(1, 0)))
        val tone = PichuQuestionAI.tone(QuestionStats.Tally(1, 1))
        assertTrue("긁기" !in tone.substringBefore("[쓸데없음]이면") && "다정" in tone)
        assertFalse("대놓고 긁으세요" in tone)
    }

    @Test
    fun `bot wording and patterns follow the file and a broken pattern keeps the default`(@TempDir dir: Path) {
        val file = dir.resolve("rules.json")
        val warnings = mutableListOf<String>()
        PichuRules.use(file) { message, _ -> warnings += message }
        assertEquals("지금 2명 접속 중이에요.\n가, 나", DiscordText.say("onlineSome", "count" to 2, "names" to "가, 나"))
        assertTrue(InquiryAutoResolve.tentative("광장 이동이 안 돼요"))
        Files.writeString(file, """{"discord":{"onlineSome":"{count}명 있어요"},"patterns":{"failureReport":"먹통"}}""")
        assertEquals("2명 있어요", DiscordText.say("onlineSome", "count" to 2, "names" to "가, 나"))
        assertFalse(InquiryAutoResolve.tentative("광장 이동이 안 돼요"))
        assertTrue(InquiryAutoResolve.tentative("광장이 먹통이에요"))
        Files.writeString(file, """{"patterns":{"failureReport":"(안 돼"}}""")
        assertTrue(InquiryAutoResolve.tentative("광장 이동이 안 돼요"))
        assertEquals(1, warnings.size)
    }

    @Test
    fun `reply visibility can be set for any command at the next use`(@TempDir dir: Path) {
        val file = dir.resolve("replies.json")
        val visibility = DiscordReplyVisibility(file) { _, _ -> }
        assertEquals(false, visibility.isPrivate("피츄", false))
        Files.writeString(file, """{"onlinePlayersPrivate":true,"commands":{"피츄":true,"접속자":false}}""")
        assertEquals(true, visibility.isPrivate("피츄", false))
        assertEquals(false, visibility.isPrivate("접속자", true))
        assertEquals(true, visibility.isPrivate("verify", true))
    }
}
