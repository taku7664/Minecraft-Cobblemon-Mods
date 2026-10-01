package jbro.cobblemon.policy.chat

import jbro.cobblemon.mcc.league.ui.LeagueRank

/** The ball each League rank shows in chat, as a glyph of the `jbro_policy:rank_icons` font. */
enum class RankIcon(val glyph: String) {
    POKE_BALL("\uE000"),
    GREAT_BALL("\uE001"),
    ULTRA_BALL("\uE002"),
    MASTER_BALL("\uE003"),
    /** The hard League's Champions. */
    CHERISH_BALL("\uE004"),
    /** Champions: the Beast Ball, the "Ultra Ball" of Ultra Beasts. */
    BEAST_BALL("\uE005");

    companion object {
        fun of(rank: LeagueRank): RankIcon = when (rank) {
            LeagueRank.POKE_BALL -> POKE_BALL
            LeagueRank.GREAT_BALL -> GREAT_BALL
            LeagueRank.ULTRA_BALL -> ULTRA_BALL
            LeagueRank.MASTER_BALL -> MASTER_BALL
            LeagueRank.CHAMPION -> BEAST_BALL
        }
    }
}
