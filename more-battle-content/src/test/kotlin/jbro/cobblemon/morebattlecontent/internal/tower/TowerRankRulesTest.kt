package jbro.cobblemon.morebattlecontent.internal.tower

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TowerRankRulesTest {
    @Test
    fun `defines stable eleven-rank order and tiers`() {
        assertEquals(
            listOf(
                TowerTier.BEGINNER,
                TowerTier.BEGINNER,
                TowerTier.BEGINNER,
                TowerTier.MONSTER_BALL,
                TowerTier.MONSTER_BALL,
                TowerTier.MONSTER_BALL,
                TowerTier.SUPER_BALL,
                TowerTier.SUPER_BALL,
                TowerTier.SUPER_BALL,
                TowerTier.HYPER_BALL,
                TowerTier.MASTER_BALL,
            ),
            TowerRank.entries.map { it.tier },
        )
        assertEquals((1L..11L).toList(), TowerRank.entries.map { it.leaderboardOrder })
        assertEquals("max", TowerRank.MAX.serializedId)
    }

    @Test
    fun `uses confirmed wins per rank and totals thirty-three before max`() {
        assertEquals(listOf(2, 2, 2), TowerRank.entries.take(3).map { it.winsRequired })
        assertEquals(listOf(3, 3, 3), TowerRank.entries.drop(3).take(3).map { it.winsRequired })
        assertEquals(listOf(4, 4, 4), TowerRank.entries.drop(6).take(3).map { it.winsRequired })
        assertEquals(6, TowerRank.RANK_10.winsRequired)
        assertNull(TowerRank.MAX.winsRequired)
        assertEquals(33, TowerRank.entries.sumOf { it.winsRequired ?: 0 })
    }

    @Test
    fun `links ranks without wrapping max`() {
        assertEquals(TowerRank.RANK_2, TowerRank.RANK_1.next)
        assertEquals(TowerRank.RANK_4, TowerRank.RANK_3.next)
        assertEquals(TowerRank.MAX, TowerRank.RANK_10.next)
        assertNull(TowerRank.MAX.next)
    }

    @Test
    fun `identifies only tier-changing rank boundaries`() {
        assertFalse(TowerRank.RANK_1.completionChangesTier)
        assertFalse(TowerRank.RANK_2.completionChangesTier)
        assertTrue(TowerRank.RANK_3.completionChangesTier)
        assertTrue(TowerRank.RANK_6.completionChangesTier)
        assertTrue(TowerRank.RANK_9.completionChangesTier)
        assertTrue(TowerRank.RANK_10.completionChangesTier)
        assertFalse(TowerRank.MAX.completionChangesTier)
    }
}
