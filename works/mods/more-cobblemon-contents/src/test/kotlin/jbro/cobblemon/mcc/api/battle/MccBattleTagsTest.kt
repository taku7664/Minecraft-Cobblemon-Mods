package jbro.cobblemon.mcc.api.battle

import java.util.UUID
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MccBattleTagsTest {
    private val player = UUID.randomUUID()
    private val rival = UUID.randomUUID()
    private val npc = UUID.randomUUID()
    private val gym = MccBattleTag(ManagedBattleContentIds.LEAGUE_CHALLENGE, "gym", "more_cobblemon_contents_league_challenge:roark")

    @Test
    fun `tags hold only lowercase ids, so clients can match them as keys`() {
        assertThrows(IllegalArgumentException::class.java) { MccBattleTag("tower") }
        assertThrows(IllegalArgumentException::class.java) { MccBattleTag(ManagedBattleContentIds.PVP, "Single") }
        assertThrows(IllegalArgumentException::class.java) { MccBattleTag(ManagedBattleContentIds.PVP, opponentId = "a b") }
        assertTrue(MccBattleTag.isValidPart("wild_trainer_ace"))
        assertFalse(MccBattleTag.isValidPart(""))
    }

    @Test
    fun `a battle created inside the block takes its tag, one outside takes none`() {
        val window = MccBattleTagWindow()
        assertNull(window.claim(setOf(player, npc)))
        val claimed = window.during(setOf(player), gym) { window.claim(setOf(player, npc)) }
        assertEquals(gym, claimed)
        assertNull(window.claim(setOf(player, npc)))
    }

    @Test
    fun `the tag goes only to a battle every named player fights in`() {
        val window = MccBattleTagWindow()
        val pvp = MccBattleTag(ManagedBattleContentIds.PVP, "double")
        window.during(setOf(player, rival), pvp) {
            assertNull(window.claim(setOf(player, npc)))
            assertEquals(pvp, window.claim(setOf(player, rival)))
        }
    }

    @Test
    fun `the innermost block wins and the outer one comes back after it`() {
        val window = MccBattleTagWindow()
        val inner = MccBattleTag(ManagedBattleContentIds.LEAGUE_CHALLENGE, "champion")
        window.during(setOf(player), gym) {
            window.during(setOf(player), inner) { assertEquals(inner, window.claim(setOf(player, npc))) }
            assertEquals(gym, window.claim(setOf(player, npc)))
        }
    }

    @Test
    fun `a failing start still closes the block`() {
        val window = MccBattleTagWindow()
        assertThrows(IllegalStateException::class.java) { window.during(setOf(player), gym) { error("refused") } }
        assertNull(window.claim(setOf(player, npc)))
    }

    @Test
    fun `managed battles without a tag still tell their content, and ending forgets the tag`() {
        val battle = UUID.randomUUID()
        assertEquals(MccBattleTag(ManagedBattleContentIds.BATTLE_TOWER),
            MccBattleTags.claim(battle, setOf(player, npc), ManagedBattleContentIds.BATTLE_TOWER))
        assertEquals(MccBattleTag(ManagedBattleContentIds.BATTLE_TOWER), MccBattleTags.of(battle))
        assertEquals(MccBattleTag(ManagedBattleContentIds.BATTLE_TOWER), MccBattleTags.release(battle))
        assertNull(MccBattleTags.of(battle))
        assertNull(MccBattleTags.claim(UUID.randomUUID(), setOf(player, npc), fallback = null))
    }
}
