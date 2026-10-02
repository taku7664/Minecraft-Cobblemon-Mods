package jbro.cobblemon.policy.chat

import jbro.cobblemon.mcc.league.ui.LeagueRank

/** The icon each League rank shows in chat, as a glyph of the `jbro_policy:rank_icons` font. */
enum class RankIcon(val glyph: String) {
    POKE_BALL("\uE000"),
    GREAT_BALL("\uE001"),
    ULTRA_BALL("\uE002"),
    MASTER_BALL("\uE003"),
    /** The hard League's Champions. */
    CHERISH_BALL("\uE004"),
    /** Champions: the Nether Star, drawn by whatever resource pack the player runs. */
    NETHER_STAR("\uE005");

    companion object {
        fun of(rank: LeagueRank): RankIcon = when (rank) {
            LeagueRank.POKE_BALL -> POKE_BALL
            LeagueRank.GREAT_BALL -> GREAT_BALL
            LeagueRank.ULTRA_BALL -> ULTRA_BALL
            LeagueRank.MASTER_BALL -> MASTER_BALL
            LeagueRank.CHAMPION -> NETHER_STAR
        }
    }
}
