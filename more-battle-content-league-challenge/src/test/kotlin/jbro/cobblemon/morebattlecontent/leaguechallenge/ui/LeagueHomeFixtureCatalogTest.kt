package jbro.cobblemon.morebattlecontent.leaguechallenge.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LeagueHomeFixtureCatalogTest {
    @Test
    fun `catalog contains every planned rank boundary fixture`() {
        assertEquals(
            listOf("badges_0", "badges_2", "badges_3", "badges_5", "badges_8", "champion"),
            LeagueHomeFixtureCatalog.all.map(LeagueHomeFixture::id)
        )
    }

    @Test
    fun `badge states expose one current target until all badges are cleared`() {
        val threeBadges = LeagueHomeFixtureCatalog.require("badges_3")
        assertEquals(3, threeBadges.badges.count { it.state == LeagueBadgeState.CLEARED })
        assertEquals(1, threeBadges.badges.count { it.state == LeagueBadgeState.CURRENT })
        assertEquals(4, threeBadges.badges.count { it.state == LeagueBadgeState.LOCKED })
        assertTrue(threeBadges.challengeAvailable)

        val master = LeagueHomeFixtureCatalog.require("badges_8")
        assertEquals(8, master.badges.count { it.state == LeagueBadgeState.CLEARED })
        assertFalse(master.badges.any { it.state == LeagueBadgeState.CURRENT })
        assertTrue(master.challengeAvailable)

        val champion = LeagueHomeFixtureCatalog.require("champion")
        assertFalse(champion.challengeAvailable)
        assertTrue(champion.facilitiesUnlocked)
    }
}
