package jbro.cobblemon.mcc.internal.tower

internal enum class TowerStreakStage(
    val serializedId: String,
    val firstWin: Int,
    val bpPerWin: Int,
) {
    INTRODUCTORY("introductory", 1, 2),
    PRACTICAL("practical", 6, 3),
    ADVANCED("advanced", 11, 4),
    PRO("pro", 21, 5),
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
