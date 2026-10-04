package jbro.cobblemon.mcc.league.ui

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
