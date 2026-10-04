package jbro.cobblemon.ui.extended.ui.shared

/** One length rule for every battle-health bar, independent of its current HP fill. */
object BattleHealthBarLayout {
    @JvmStatic
    fun shortWidth(fullWidth: Int): Int {
        require(fullWidth >= 0)
        return (fullWidth * 2 + 1) / 3
    }
}
