package jbro.cobblemon.battleui.extended.ui.champions

/** Reserves the final fixed-height panel row for an explicit overflow summary. */
object EffectListLayout {
    private const val MAX_ROWS = 8

    data class Window(val visibleEffectCount: Int, val hiddenEffectCount: Int)

    fun window(totalEffectCount: Int): Window {
        require(totalEffectCount >= 0) { "Effect count cannot be negative" }
        if (totalEffectCount <= MAX_ROWS) return Window(totalEffectCount, 0)

        val visibleEffectCount = MAX_ROWS - 1
        return Window(visibleEffectCount, totalEffectCount - visibleEffectCount)
    }
}
