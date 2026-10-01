package jbro.cobblemon.policy.support

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DiscordNewsTest {
    @Test
    fun `Legend and shiny catches are news, everyday ones are not`() {
        assertEquals("✨ Ash님이 전설의 포켓몬 뮤츠을(를) 잡았습니다!", DiscordNews.catchNews("Ash", "뮤츠", legend = true, shiny = false))
        assertEquals("🌟 Ash님이 이로치 잉어킹을(를) 잡았습니다!", DiscordNews.catchNews("Ash", "잉어킹", legend = false, shiny = true))
        assertEquals("🌟 Ash님이 이로치 전설의 포켓몬 뮤츠을(를) 잡았습니다!", DiscordNews.catchNews("Ash", "뮤츠", legend = true, shiny = true))
        assertNull(DiscordNews.catchNews("Ash", "구구", legend = false, shiny = false))
    }

    @Test
    fun `each kind of content news has its emoji`() {
        assertEquals("🏆", DiscordNews.emoji("more_cobblemon_contents_league_challenge:champion"))
        assertEquals("🔥", DiscordNews.emoji("more_cobblemon_contents:win_streak"))
        assertEquals("🏭", DiscordNews.emoji("more_cobblemon_contents:highest_floor"))
        assertEquals("📣", DiscordNews.emoji("other:thing"))
    }

    @Test
    fun `translations fill their arguments in order or by position`() {
        assertEquals("Ash님이 7층에", KoreanText.format("%s님이 %s층에", arrayOf<Any?>("Ash", 7)))
        assertEquals("7층 · Ash · 100%", KoreanText.format("%2\$s층 · %1\$s · 100%%", arrayOf<Any?>("Ash", 7)))
        assertEquals("Ash님", KoreanText.format("%1\$s님%3\$s", arrayOf<Any?>("Ash")))
    }
}
