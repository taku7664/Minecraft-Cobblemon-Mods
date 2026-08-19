package jbro.cobblemon.morebattlecontent.internal.tower

internal enum class TowerTier {
    BEGINNER,
    MONSTER_BALL,
    SUPER_BALL,
    HYPER_BALL,
    MASTER_BALL,
}

internal enum class TowerRank(
    val serializedId: String,
    val tier: TowerTier,
    val winsRequired: Int?,
    val leaderboardOrder: Long,
) {
    RANK_1("1", TowerTier.BEGINNER, 2, 1),
    RANK_2("2", TowerTier.BEGINNER, 2, 2),
    RANK_3("3", TowerTier.BEGINNER, 2, 3),
    RANK_4("4", TowerTier.MONSTER_BALL, 3, 4),
    RANK_5("5", TowerTier.MONSTER_BALL, 3, 5),
    RANK_6("6", TowerTier.MONSTER_BALL, 3, 6),
    RANK_7("7", TowerTier.SUPER_BALL, 4, 7),
    RANK_8("8", TowerTier.SUPER_BALL, 4, 8),
    RANK_9("9", TowerTier.SUPER_BALL, 4, 9),
    RANK_10("10", TowerTier.HYPER_BALL, 6, 10),
    MAX("max", TowerTier.MASTER_BALL, null, 11),
    ;

    val next: TowerRank?
        get() = entries.getOrNull(ordinal + 1)

    val completionChangesTier: Boolean
        get() = next?.tier?.let { it != tier } ?: false
}
