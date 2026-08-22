package jbro.cobblemon.morebattlecontent.internal.tower

internal enum class TowerStreakStage(
    val serializedId: String,
    val firstWin: Int,
    val bpPerWin: Int,
) {
    INTRODUCTORY("introductory", 1, 1),
    PRACTICAL("practical", 6, 2),
    ADVANCED("advanced", 11, 3),
    PRO("pro", 21, 4),
    ;

    companion object {
        fun forWin(winNumber: Int): TowerStreakStage {
            require(winNumber > 0) { "Win number must be positive" }
            return entries.last { winNumber >= it.firstWin }
        }

        fun forNextBattle(currentWinStreak: Int): TowerStreakStage =
            forWin(Math.addExact(currentWinStreak, 1))
    }
}
