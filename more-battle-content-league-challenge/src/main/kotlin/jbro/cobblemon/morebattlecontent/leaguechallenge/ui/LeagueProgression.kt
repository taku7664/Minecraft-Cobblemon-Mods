package jbro.cobblemon.morebattlecontent.leaguechallenge.ui

enum class LeagueRank(val translationKey: String) {
    POKE_BALL("rank.poke_ball"),
    GREAT_BALL("rank.great_ball"),
    ULTRA_BALL("rank.ultra_ball"),
    MASTER_BALL("rank.master_ball"),
    CHAMPION("rank.champion");

    companion object {
        fun fromProgress(badgeCount: Int, championDefeated: Boolean): LeagueRank {
            require(badgeCount in 0..8) { "Badge count must be between 0 and 8" }
            require(!championDefeated || badgeCount == 8) { "Champion progress requires all eight badges" }
            return when {
                championDefeated -> CHAMPION
                badgeCount == 8 -> MASTER_BALL
                badgeCount >= 5 -> ULTRA_BALL
                badgeCount >= 3 -> GREAT_BALL
                else -> POKE_BALL
            }
        }
    }
}

enum class LeagueBadgeState { CLEARED, CURRENT, LOCKED }

enum class LeagueNextChallenge { GYM, POKEMON_LEAGUE, COMPLETE }

data class LeagueBadgeFixture(val index: Int, val state: LeagueBadgeState)

data class LeagueHomeFixture(
    val id: String,
    val badgeCount: Int,
    val championDefeated: Boolean,
    val badges: List<LeagueBadgeFixture>,
    val levelCap: Int? = null
) {
    val rank: LeagueRank = LeagueRank.fromProgress(badgeCount, championDefeated)
    val facilitiesUnlocked: Boolean = championDefeated
    val challengeAvailable: Boolean = !championDefeated
    val nextChallenge: LeagueNextChallenge = when {
        championDefeated -> LeagueNextChallenge.COMPLETE
        badgeCount == 8 -> LeagueNextChallenge.POKEMON_LEAGUE
        else -> LeagueNextChallenge.GYM
    }
}

object LeagueHomeFixtureCatalog {
    val all: List<LeagueHomeFixture> = listOf(0, 2, 3, 5, 8).map(::fixture) + fixture(8, champion = true)

    fun require(id: String): LeagueHomeFixture =
        requireNotNull(all.firstOrNull { it.id == id }) { "Unknown League home fixture: $id" }

    private fun fixture(badgeCount: Int, champion: Boolean = false): LeagueHomeFixture = LeagueHomeFixture(
        id = if (champion) "champion" else "badges_$badgeCount",
        badgeCount = badgeCount,
        championDefeated = champion,
        badges = (1..8).map { index ->
            LeagueBadgeFixture(
                index = index,
                state = when {
                    index <= badgeCount -> LeagueBadgeState.CLEARED
                    index == badgeCount + 1 -> LeagueBadgeState.CURRENT
                    else -> LeagueBadgeState.LOCKED
                }
            )
        }
    )
}
