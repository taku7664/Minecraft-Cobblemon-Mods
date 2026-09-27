package jbro.cobblemon.battleui.extended.ui.shared

/** Yarn-side visual adapter; battle state and input remain owned by Cobblemon. */
object BattleUiTheme {
    const val TEXT = 0xFFEAF7FF.toInt()
    const val MUTED = 0xFFB9CAD8.toInt()
    const val DIM = 0xFF71859A.toInt()
    const val CYAN = 0xFF69D6E8.toInt()
    const val PURPLE = 0xFF9868FF.toInt()
    const val FOCUS = 0xFFFFC84A.toInt()
    const val DANGER = 0xFFFF667A.toInt()
    const val GOOD = 0xFF62E39B.toInt()
    const val BORDER = 0xFF274562.toInt()
    const val PANEL = 0xFF101A2D.toInt()
    const val PANEL_ALT = 0xFF0C1525.toInt()
    const val TRACK = 0xFF101724.toInt()

    val shell = BattleSurface(0xF214263B.toInt(), 0xF2080E1D.toInt(), CYAN, 2, 6)
    val panel = BattleSurface(0xF2182941.toInt(), 0xF2101A2D.toInt(), BORDER, 1, 3)
    val row = BattleSurface(PANEL_ALT, PANEL_ALT, borderWidth = 0, cut = 0)
    val primary = BattleSurface(0xFF39E4E4.toInt(), 0xFF269BA3.toInt(), CYAN, 1, 4, 0b1010)
    val secondary = BattleSurface(0xFF203D55.toInt(), 0xFF203D55.toInt(), BORDER, 1, 0)
    val danger = BattleSurface(0xFFAB4559.toInt(), 0xFF692F40.toInt(), DANGER, 1, 4, 0b0101)
    val capture = BattleSurface(0xFF654A9D.toInt(), 0xFF34284E.toInt(), PURPLE, 1, 3)
}

/** Corner bits clockwise from top-left. Opacity multiplies fill alpha only. */
data class BattleSurface(
    val top: Int,
    val bottom: Int = top,
    val border: Int = 0,
    val borderWidth: Int = 1,
    val cut: Int = 3,
    val corners: Int = 15,
    val backgroundOpacity: Float = 1f
) {
    init {
        require(cut >= 0 && borderWidth >= 0)
        require(corners in 0..15)
        require(backgroundOpacity in 0f..1f)
    }
}
