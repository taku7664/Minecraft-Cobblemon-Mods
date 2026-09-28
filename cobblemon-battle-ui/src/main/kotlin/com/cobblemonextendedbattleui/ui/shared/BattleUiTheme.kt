package jbro.cobblemon.battleui.extended.ui.shared

/** Yarn-side visual adapter; battle state and input remain owned by Cobblemon. */
object BattleUiTheme {
    const val TEXT = 0xFFEAF7FF.toInt()
    const val MUTED = 0xFFB9CAD8.toInt()
    const val DIM = 0xFF71859A.toInt()
    const val CYAN = 0xFF69D6E8.toInt()
    const val PURPLE = 0xFF9868FF.toInt()
    const val MALE = 0xFF64B6FF.toInt()
    const val FEMALE = 0xFFFF79B7.toInt()
    const val TRANSCRIPT_OPPONENT = 0xFFB0A0ED.toInt()
    const val FOCUS = 0xFFFFC84A.toInt()
    const val DANGER = 0xFFFF667A.toInt()
    const val GOOD = 0xFF62E39B.toInt()
    const val BORDER = 0xFF274562.toInt()
    const val PANEL = 0xFF101A2D.toInt()
    const val PANEL_ALT = 0xFF0C1525.toInt()
    const val TRACK = 0xFF101724.toInt()

    val shell = BattleSurface(0xF214263B.toInt(), 0xF2080E1D.toInt(), CYAN, 0, 6)
    val panel = BattleSurface(0xF2182941.toInt(), 0xF2101A2D.toInt(), BORDER, 0, 3)
    val row = BattleSurface(PANEL_ALT, PANEL_ALT, borderWidth = 0, cut = 0)
    val primary = BattleSurface(0xFF39E4E4.toInt(), 0xFF269BA3.toInt(), CYAN, 0, 4, 0b1010)
    val secondary = BattleSurface(0xFF203D55.toInt(), 0xFF203D55.toInt(), BORDER, 0, 0)
    val danger = BattleSurface(0xFFAB4559.toInt(), 0xFF692F40.toInt(), DANGER, 0, 4, 0b0101)
    val capture = BattleSurface(0xFF654A9D.toInt(), 0xFF34284E.toInt(), PURPLE, 0, 3)
    val transcriptSelf = BattleSurface(0xFF183347.toInt(), 0xFF142638.toInt(), 0xFF345C6C.toInt(), 0, 5, 0b1010)
    val transcriptOpponent = BattleSurface(0xFF28233B.toInt(), 0xFF1A2135.toInt(), 0xFF514969.toInt(), 0, 5, 0b0101)
}

/** Main opaque colors sampled from Cobblemon 1.8.1 battle_status_*.png textures. */
object BattleStatusPalette {
    @JvmStatic
    fun background(showdownName: String): Int = when (showdownName) {
        "brn" -> 0xFFE57034.toInt()
        "psn", "tox" -> 0xFFBA5CD8.toInt()
        "par" -> 0xFFDAA52F.toInt()
        "slp" -> 0xFF8D8AA4.toInt()
        "frz" -> 0xFF5AA4F5.toInt()
        else -> 0xFF71859A.toInt()
    }
}

/** Corner bits clockwise from top-left. Opacity multiplies fill alpha only. */
data class BattleSurface(
    val top: Int,
    val bottom: Int = top,
    val border: Int = 0,
    val borderWidth: Int = 0,
    val cut: Int = 3,
    val corners: Int = 15,
    val backgroundOpacity: Float = 1f,
    val cornerCuts: BattleCornerCuts? = null
) {
    init {
        require(cut >= 0 && borderWidth >= 0)
        require(corners in 0..15)
        require(backgroundOpacity in 0f..1f)
    }
}

/** Independent corner sizes for surfaces that need a directional silhouette. */
data class BattleCornerCuts(val topLeft: Int = 0, val topRight: Int = 0,
                            val bottomRight: Int = 0, val bottomLeft: Int = 0) {
    init {
        require(topLeft >= 0 && topRight >= 0 && bottomRight >= 0 && bottomLeft >= 0)
    }
}
