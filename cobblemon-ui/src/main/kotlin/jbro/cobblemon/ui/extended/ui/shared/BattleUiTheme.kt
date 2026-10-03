package jbro.cobblemon.ui.extended.ui.shared

/** How command and move tiles anchored to the screen edge are shaped. */
enum class BattleControlShape {
    /** Rounded on the free end only, flush with the screen edge. */
    ROUNDED_START,

    /** A pill, both ends half-circles, standing a little off the screen edge. */
    PILL
}

/** Where a command tile shows the color of its action. */
enum class BattleCommandAccent {
    /** A short pill inside the tile's free end. */
    BAR,

    /** A colored disc at the tile's far end, as Sword and Shield mark each command. */
    END_CAP
}

/** What a move tile is filled with. */
enum class BattleMoveFill {
    /** The theme's panel, with a type-colored pill. */
    PANEL,

    /** The move's type color, muted, as Sword and Shield draw moves. */
    TYPE
}

/** The top HUD card's inner end. */
enum class BattleHudShape {
    /** Rounded, with a band of the side's color along the curve. */
    ROUNDED_ACCENT,

    /** A slanted cut, the bottom reaching further in, on a plain card. */
    SLANT
}

/** How battle narration is drawn. */
enum class BattleDialogueStyle {
    /** The shared Cobblemon UI window, as the MCC hub's cards. */
    SHARED_WINDOW,

    /** A dark translucent band with slanted ends and white text. */
    DARK_BAND
}

/** The pattern the battle entry transition covers the screen with. */
enum class BattleEntryPattern {
    /** Rounded cells in a checker that bloom from the center. */
    CELLS,

    /** Slanted bands that widen from the center outward. */
    STRIPES
}

/**
 * Every color, surface and shape choice the battle screens draw with. The screens lay out the same information in
 * every theme; a theme only changes how it looks.
 */
data class BattleUiPalette(
    val id: String,
    /** Light surfaces with dark text; colors made for dark surfaces are deepened through [BattleUiTheme.readable]. */
    val light: Boolean,
    val text: Int,
    val muted: Int,
    val dim: Int,
    val ally: Int,
    val opponent: Int,
    val male: Int,
    val female: Int,
    val transcriptOpponentText: Int,
    val focus: Int,
    val danger: Int,
    val good: Int,
    val border: Int,
    val panelColor: Int,
    val panelAltColor: Int,
    val track: Int,
    val xp: Int,
    val modalScrim: Int,
    val statusInk: Int,
    val chip: Int,
    val dropShadow: Int,
    val windowShadow: Int,
    val koVeil: Int,
    val popupRim: Int,
    // Commands: each action's color, and the label colors.
    val fightAccent: Int,
    val switchAccent: Int,
    val bagAccent: Int,
    val runAccent: Int,
    val primaryText: Int,
    val commandText: Int,
    /** When set, focus turns a control this color and its text [focusText]; otherwise focus lightens it. */
    val focusFill: Int?,
    val focusText: Int,
    /** The menu cursor's color; null takes the focused control's accent. */
    val cursor: Int?,
    // The chosen row in the switch and target lists.
    val select: Int,
    val selectBottom: Int,
    val selectInk: Int,
    // Switch, target and information panels.
    val headerTop: Int,
    val headerBottom: Int,
    val detailHeaderTop: Int,
    val detailHeaderBottom: Int,
    val opponentHeaderTop: Int,
    val opponentHeaderBottom: Int,
    val opponentRowTop: Int,
    val opponentRowBottom: Int,
    val cardTop: Int,
    val cardBottom: Int,
    val activeCardTop: Int,
    val activeCardBottom: Int,
    val detailTop: Int,
    val detailBottom: Int,
    val portraitTop: Int,
    val portraitBottom: Int,
    val movesBand: Int,
    val zebra: Int,
    val abilityBox: Int,
    val allyGlass: Int,
    val enemyGlass: Int,
    val opponentEffectRow: Int,
    // Top HUD cards.
    val hudTop: Int,
    val hudFocusTop: Int,
    val hudBottom: Int,
    // Surfaces.
    val shellSurface: BattleSurface,
    val panelSurface: BattleSurface,
    val modalBackdrop: BattleSurface,
    val row: BattleSurface,
    val primary: BattleSurface,
    val secondary: BattleSurface,
    val dangerSurface: BattleSurface,
    val capture: BattleSurface,
    val transcriptSelf: BattleSurface,
    val transcriptOpponent: BattleSurface,
    // Shapes.
    val controlShape: BattleControlShape,
    val commandAccent: BattleCommandAccent,
    val moveFill: BattleMoveFill,
    val hudShape: BattleHudShape,
    val dialogue: BattleDialogueStyle,
    val entryPattern: BattleEntryPattern,
    /** The entry pattern's dark tone, which the battle's colour is mixed into. */
    val entryBase: Int,
)

/** The battle themes and the one in use. */
object BattleUiThemes {
    /** The navy, cyan and violet look after Pokémon Champions, rounded. */
    val CHAMPIONS: BattleUiPalette = run {
        val panel = BattleSurface(0xF2182941.toInt(), 0xF2101A2D.toInt(), 0xFF274562.toInt(), 0, 3)
        BattleUiPalette(
            id = "champions", light = false,
            text = 0xFFEAF7FF.toInt(), muted = 0xFFB9CAD8.toInt(), dim = 0xFF71859A.toInt(),
            ally = 0xFF69D6E8.toInt(), opponent = 0xFF9868FF.toInt(),
            male = 0xFF64B6FF.toInt(), female = 0xFFFF79B7.toInt(), transcriptOpponentText = 0xFFB0A0ED.toInt(),
            focus = 0xFFFFC84A.toInt(), danger = 0xFFFF667A.toInt(), good = 0xFF62E39B.toInt(),
            border = 0xFF274562.toInt(), panelColor = 0xFF101A2D.toInt(), panelAltColor = 0xFF0C1525.toInt(),
            track = 0xFF101724.toInt(), xp = 0xFF62BFEF.toInt(), modalScrim = 0x8C000000.toInt(),
            statusInk = 0xFF182337.toInt(), chip = 0xFF1B2C42.toInt(), dropShadow = 0x3A000000,
            windowShadow = 0x55000000, koVeil = 0xA0101624.toInt(), popupRim = 0x5569D6E8,
            fightAccent = 0xFF69D6E8.toInt(), switchAccent = 0xFF69D6E8.toInt(), bagAccent = 0xFF9868FF.toInt(),
            runAccent = 0xFFFF667A.toInt(), primaryText = 0xFF071018.toInt(), commandText = 0xFFEAF7FF.toInt(),
            focusFill = null, focusText = 0xFFEAF7FF.toInt(), cursor = null,
            select = 0xFFCCEE67.toInt(), selectBottom = 0xFFA8CA45.toInt(), selectInk = 0xFF081C2B.toInt(),
            headerTop = 0xF81A2941.toInt(), headerBottom = 0xF8111D30.toInt(),
            detailHeaderTop = 0xF8233A53.toInt(), detailHeaderBottom = 0xF814283E.toInt(),
            opponentHeaderTop = 0xF84D3048.toInt(), opponentHeaderBottom = 0xF8282036.toInt(),
            opponentRowTop = 0xF54A3048.toInt(), opponentRowBottom = 0xF52D263E.toInt(),
            cardTop = 0xF9233851.toInt(), cardBottom = 0xF9182B42.toInt(),
            activeCardTop = 0xFF284D60.toInt(), activeCardBottom = 0xFF203B50.toInt(),
            detailTop = 0xF421354C.toInt(), detailBottom = 0xF0111D32.toInt(),
            portraitTop = 0xFF29435D.toInt(), portraitBottom = 0xFF13283F.toInt(),
            movesBand = 0xFF174058.toInt(), zebra = 0x7234516A, abilityBox = 0x6C0B1727,
            allyGlass = 0xF21B3D4E.toInt(), enemyGlass = 0xF239244F.toInt(), opponentEffectRow = 0xF02C1C35.toInt(),
            hudTop = 0xF2284054.toInt(), hudFocusTop = 0xF23B536A.toInt(), hudBottom = 0xF20C192B.toInt(),
            shellSurface = BattleSurface(0xF214263B.toInt(), 0xF2080E1D.toInt(), 0xFF69D6E8.toInt(), 0, 8),
            panelSurface = panel,
            modalBackdrop = panel.copy(top = 0xB32A4664.toInt(), bottom = 0xC4193049.toInt(),
                borderWidth = 0, cornerCuts = BattleCornerCuts(8, 8, 8, 8)),
            row = BattleSurface(0xFF0C1525.toInt(), 0xFF0C1525.toInt(), borderWidth = 0, cut = 0),
            primary = BattleSurface(0xFF39E4E4.toInt(), 0xFF269BA3.toInt(), 0xFF69D6E8.toInt(), 0, 4, 0b1010),
            secondary = BattleSurface(0xFF203D55.toInt(), 0xFF203D55.toInt(), 0xFF274562.toInt(), 0, 0),
            dangerSurface = BattleSurface(0xFFAB4559.toInt(), 0xFF692F40.toInt(), 0xFFFF667A.toInt(), 0, 4, 0b0101),
            capture = BattleSurface(0xFF654A9D.toInt(), 0xFF34284E.toInt(), 0xFF9868FF.toInt(), 0, 3),
            transcriptSelf = BattleSurface(0xFF183347.toInt(), 0xFF142638.toInt(), 0xFF345C6C.toInt(), 0, 5, 0b1010),
            transcriptOpponent = BattleSurface(0xFF28233B.toInt(), 0xFF1A2135.toInt(), 0xFF514969.toInt(), 0, 5, 0b0101),
            controlShape = BattleControlShape.ROUNDED_START, commandAccent = BattleCommandAccent.BAR,
            moveFill = BattleMoveFill.PANEL, hudShape = BattleHudShape.ROUNDED_ACCENT,
            dialogue = BattleDialogueStyle.SHARED_WINDOW,
            entryPattern = BattleEntryPattern.CELLS, entryBase = 0xFF0A1322.toInt(),
        )
    }

    /**
     * Pokémon Sword and Shield's battle look: white pills with dark labels that turn black when chosen, each
     * command marked by its color at the far end, moves on muted type colors, plain white HUD cards with a slanted
     * inner end, and narration on a dark band.
     */
    val GALAR: BattleUiPalette = run {
        val ink = 0xFF1F1F1F.toInt()
        val pill = BattleSurface(0xF8FFFFFF.toInt(), 0xF8F3F3F3.toInt(), borderWidth = 0, cut = 12)
        val panel = BattleSurface(0xFFFFFFFF.toInt(), 0xFFF5F5F5.toInt(), 0xFFD6D6D6.toInt(), 0, 4)
        BattleUiPalette(
            id = "galar", light = true,
            text = ink, muted = 0xFF5C5C5C.toInt(), dim = 0xFF8E8E8E.toInt(),
            ally = 0xFF0A8FD8.toInt(), opponent = 0xFFD8306A.toInt(),
            male = 0xFF2F80ED.toInt(), female = 0xFFEB4C82.toInt(), transcriptOpponentText = 0xFFC2185B.toInt(),
            focus = 0xFFE09B00.toInt(), danger = 0xFFE53935.toInt(), good = 0xFF2DBE4E.toInt(),
            border = 0xFFD6D6D6.toInt(), panelColor = 0xFFF2F2F2.toInt(), panelAltColor = 0xFFFFFFFF.toInt(),
            track = 0xFFD2D2D2.toInt(), xp = 0xFF2F80ED.toInt(), modalScrim = 0x73000000,
            statusInk = 0xFFFFFFFF.toInt(), chip = 0xFFE7E7E7.toInt(), dropShadow = 0x2E000000,
            windowShadow = 0x3A000000, koVeil = 0xB0F2F2F2.toInt(), popupRim = 0x40000000,
            fightAccent = 0xFFE53935.toInt(), switchAccent = 0xFF43A047.toInt(), bagAccent = 0xFFF29E4C.toInt(),
            runAccent = 0xFF8E5BD6.toInt(), primaryText = ink, commandText = ink,
            focusFill = ink, focusText = 0xFFFFFFFF.toInt(), cursor = ink,
            select = ink, selectBottom = 0xFF2E2E2E.toInt(), selectInk = 0xFFFFFFFF.toInt(),
            headerTop = 0xFFEDEDED.toInt(), headerBottom = 0xFFE2E2E2.toInt(),
            detailHeaderTop = 0xFFEDEDED.toInt(), detailHeaderBottom = 0xFFE2E2E2.toInt(),
            opponentHeaderTop = 0xFFFBE3EC.toInt(), opponentHeaderBottom = 0xFFF4D2DE.toInt(),
            opponentRowTop = 0xFFFFF1F6.toInt(), opponentRowBottom = 0xFFF9E3EB.toInt(),
            cardTop = 0xFFFFFFFF.toInt(), cardBottom = 0xFFF4F4F4.toInt(),
            activeCardTop = 0xFFE4F2FC.toInt(), activeCardBottom = 0xFFD5EAF8.toInt(),
            detailTop = 0xFFFFFFFF.toInt(), detailBottom = 0xFFF3F3F3.toInt(),
            portraitTop = 0xFFF0F0F0.toInt(), portraitBottom = 0xFFE3E3E3.toInt(),
            movesBand = 0xFFE9E9E9.toInt(), zebra = 0x12000000, abilityBox = 0x0E000000,
            allyGlass = 0xFFE6F3FB.toInt(), enemyGlass = 0xFFFCE9F0.toInt(), opponentEffectRow = 0xFFFDEEF3.toInt(),
            hudTop = 0xF5FFFFFF.toInt(), hudFocusTop = 0xF5FFF3D1.toInt(), hudBottom = 0xF5F1F1F1.toInt(),
            shellSurface = BattleSurface(0xF5F8F8F8.toInt(), 0xF5ECECEC.toInt(), 0xFFD6D6D6.toInt(), 0, 6),
            panelSurface = panel,
            modalBackdrop = panel.copy(top = 0xD9F4F4F4.toInt(), bottom = 0xD9E8E8E8.toInt(),
                borderWidth = 0, cornerCuts = BattleCornerCuts(8, 8, 8, 8)),
            row = BattleSurface(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), borderWidth = 0, cut = 0),
            primary = pill, secondary = pill, dangerSurface = pill, capture = pill,
            transcriptSelf = BattleSurface(0xFFE4F2FC.toInt(), 0xFFD8ECF9.toInt(), borderWidth = 0, cut = 5),
            transcriptOpponent = BattleSurface(0xFFFCE6EE.toInt(), 0xFFF7DAE5.toInt(), borderWidth = 0, cut = 5),
            controlShape = BattleControlShape.PILL, commandAccent = BattleCommandAccent.END_CAP,
            moveFill = BattleMoveFill.TYPE, hudShape = BattleHudShape.SLANT,
            dialogue = BattleDialogueStyle.DARK_BAND,
            entryPattern = BattleEntryPattern.STRIPES, entryBase = 0xFF1F1F1F.toInt(),
        )
    }

    val all: List<BattleUiPalette> = listOf(CHAMPIONS, GALAR)

    @Volatile
    var current: BattleUiPalette = CHAMPIONS
        private set

    /** Selects a theme by its id; false, keeping the current one, if no theme has it. */
    fun select(id: String): Boolean {
        current = all.firstOrNull { it.id == id } ?: return false
        return true
    }
}

/**
 * The battle screens' colors and surfaces, read from the theme in use ([BattleUiThemes.current]) each time, so
 * switching themes restyles every screen at once.
 */
object BattleUiTheme {
    private val p: BattleUiPalette get() = BattleUiThemes.current

    val TEXT: Int get() = p.text
    val MUTED: Int get() = p.muted
    val DIM: Int get() = p.dim
    /** The player's side color. */
    val CYAN: Int get() = p.ally
    /** The opponent's side color. */
    val PURPLE: Int get() = p.opponent
    val MALE: Int get() = p.male
    val FEMALE: Int get() = p.female
    val TRANSCRIPT_OPPONENT: Int get() = p.transcriptOpponentText
    val FOCUS: Int get() = p.focus
    val DANGER: Int get() = p.danger
    val GOOD: Int get() = p.good
    val BORDER: Int get() = p.border
    val PANEL: Int get() = p.panelColor
    val PANEL_ALT: Int get() = p.panelAltColor
    val TRACK: Int get() = p.track
    val MODAL_SCRIM: Int get() = p.modalScrim

    val shell: BattleSurface get() = p.shellSurface
    val panel: BattleSurface get() = p.panelSurface
    val modalBackdrop: BattleSurface get() = p.modalBackdrop
    val row: BattleSurface get() = p.row
    val primary: BattleSurface get() = p.primary
    val secondary: BattleSurface get() = p.secondary
    val danger: BattleSurface get() = p.dangerSurface
    val capture: BattleSurface get() = p.capture
    val transcriptSelf: BattleSurface get() = p.transcriptSelf
    val transcriptOpponent: BattleSurface get() = p.transcriptOpponent

    val palette: BattleUiPalette get() = p

    /** A color made for dark surfaces, deepened enough to read on a light theme's surfaces. */
    @JvmStatic
    fun readable(color: Int): Int =
        if (p.light) (BattleSurfaceRenderer.interpolate(color, 0xFF000000.toInt(), .38f) and 0xFFFFFF) or (color and 0xFF000000.toInt())
        else color
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

/**
 * Corner bits clockwise from top-left. Opacity multiplies fill alpha only. [rounded] draws each corner as a quarter
 * circle of its size instead of a 45° cut; both are anti-aliased.
 */
data class BattleSurface(
    val top: Int,
    val bottom: Int = top,
    val border: Int = 0,
    val borderWidth: Int = 0,
    val cut: Int = 3,
    val corners: Int = 15,
    val backgroundOpacity: Float = 1f,
    val cornerCuts: BattleCornerCuts? = null,
    val rounded: Boolean = true
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
