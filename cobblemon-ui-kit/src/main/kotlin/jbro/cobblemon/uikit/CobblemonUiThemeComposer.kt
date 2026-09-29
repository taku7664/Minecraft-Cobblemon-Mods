package jbro.cobblemon.uikit

import java.util.concurrent.ConcurrentHashMap

/**
 * The colours of a theme, apart from its shapes. A [UiThemeStyle] decides how they are drawn, so one palette can
 * be a pixel-framed theme or a DS menu window theme. Card title bands take [featureBand] and [infoBand], [trim]
 * lights the shell frame and header rules, [cursor] marks the DS selection and [textShadow] is the pale shadow
 * under DS text on light surfaces.
 */
data class UiThemePalette(
    val backdrop: Int,
    val shell: Int,
    val titleBar: Int,
    val titleBarShade: Int,
    val panel: Int,
    val panelAlt: Int,
    val panelText: Int,
    val border: Int,
    val frameHighlight: Int,
    val frameShade: Int,
    val trim: Int,
    val infoBand: Int,
    val featureBand: Int,
    val primary: Int,
    val primaryHover: Int,
    val primaryPressed: Int,
    val primaryText: Int,
    val secondary: Int,
    val secondaryHover: Int,
    val secondaryPressed: Int,
    val secondaryText: Int,
    val selected: Int,
    val selectedText: Int,
    val danger: Int,
    val dangerHover: Int,
    val dangerPressed: Int,
    val good: Int,
    val disabled: Int,
    val text: Int,
    val textSecondary: Int,
    val textDim: Int,
    val cursor: Int,
    val textShadow: Int,
    val panelTextDim: Int
)

/** How a palette is drawn. */
enum class UiThemeStyle(val id: String) {
    /** Pixel League's square pixel frames, filled selection and sprite cursor. */
    PIXEL_FRAME("pixel_frame"),

    /**
     * The Generation IV DS menu: rounded windows with an outline and a coloured band, unframed list lines, an
     * outline cursor that marks a choice without repainting it, titles over a rule and pale text shadows.
     */
    DS_WINDOW("ds_window")
}

/** The built-in palettes, taken from the Generation IV Battle Tower and Battle Factory. */
enum class UiPalettePreset(val id: String, val palette: UiThemePalette) {
    TOWER_LOBBY(
        "tower_lobby",
        UiThemePalette(
            backdrop = 0xE0141C26.toInt(),
            shell = 0xFF5A6E84.toInt(),
            titleBar = 0xFF34435A.toInt(),
            titleBarShade = 0xFF222C3C.toInt(),
            panel = 0xFFEEF1F2.toInt(),
            panelAlt = 0xFFD6DADC.toInt(),
            panelText = 0xFF283440.toInt(),
            border = 0xFF283440.toInt(),
            frameHighlight = 0xFFFFFFFF.toInt(),
            frameShade = 0xFF9AA6B0.toInt(),
            trim = 0xFFC04038.toInt(),
            infoBand = 0xFF8098B0.toInt(),
            featureBand = 0xFFC04038.toInt(),
            primary = 0xFF2C9CCC.toInt(),
            primaryHover = 0xFF44C4DC.toInt(),
            primaryPressed = 0xFF1E7AA6.toInt(),
            primaryText = 0xFFFFFFFF.toInt(),
            secondary = 0xFFF6F7F7.toInt(),
            secondaryHover = 0xFFFFFFFF.toInt(),
            secondaryPressed = 0xFFC8CED2.toInt(),
            secondaryText = 0xFF283440.toInt(),
            selected = 0xFFECBC2C.toInt(),
            selectedText = 0xFF3A2C08.toInt(),
            danger = 0xFFC04038.toInt(),
            dangerHover = 0xFFE05040.toInt(),
            dangerPressed = 0xFF883840.toInt(),
            good = 0xFF4FA860.toInt(),
            disabled = 0xFFA8B0B6.toInt(),
            text = 0xFFF2F5F8.toInt(),
            textSecondary = 0xFFD6DEE6.toInt(),
            textDim = 0xFFA9B8C8.toInt(),
            cursor = 0xFFF07820.toInt(),
            textShadow = 0xFFC4CCD2.toInt(),
            panelTextDim = 0xFF5A6878.toInt()
        )
    ),
    TOWER_ELEVATOR(
        "tower_elevator",
        UiThemePalette(
            backdrop = 0xE00C121A.toInt(),
            shell = 0xFF2B3A4E.toInt(),
            titleBar = 0xFF1D2838.toInt(),
            titleBarShade = 0xFF121A26.toInt(),
            panel = 0xFFE4F1F5.toInt(),
            panelAlt = 0xFFC6E0E8.toInt(),
            panelText = 0xFF16202C.toInt(),
            border = 0xFF16202C.toInt(),
            frameHighlight = 0xFFFFFFFF.toInt(),
            frameShade = 0xFF84A8B8.toInt(),
            trim = 0xFFC04038.toInt(),
            infoBand = 0xFF84DCEC.toInt(),
            featureBand = 0xFF2C9CCC.toInt(),
            primary = 0xFF2C9CCC.toInt(),
            primaryHover = 0xFF44C4DC.toInt(),
            primaryPressed = 0xFF1E7AA6.toInt(),
            primaryText = 0xFFFFFFFF.toInt(),
            secondary = 0xFFF2F8FA.toInt(),
            secondaryHover = 0xFFFFFFFF.toInt(),
            secondaryPressed = 0xFFBFD4DC.toInt(),
            secondaryText = 0xFF16202C.toInt(),
            selected = 0xFF44C4DC.toInt(),
            selectedText = 0xFF0B2A33.toInt(),
            danger = 0xFFC04038.toInt(),
            dangerHover = 0xFFE05040.toInt(),
            dangerPressed = 0xFF883840.toInt(),
            good = 0xFF4FA860.toInt(),
            disabled = 0xFF8A9CA8.toInt(),
            text = 0xFFEAF6FA.toInt(),
            textSecondary = 0xFFC4DCE6.toInt(),
            textDim = 0xFF8FA6B8.toInt(),
            cursor = 0xFFC04038.toInt(),
            textShadow = 0xFFB4D0DA.toInt(),
            panelTextDim = 0xFF4A6070.toInt()
        )
    ),
    TOWER_SUNBURST(
        "tower_sunburst",
        UiThemePalette(
            backdrop = 0xE01A1E24.toInt(),
            shell = 0xFF586878.toInt(),
            titleBar = 0xFF3F4D5C.toInt(),
            titleBarShade = 0xFF2C3642.toInt(),
            panel = 0xFFF3EEDC.toInt(),
            panelAlt = 0xFFDEDBC4.toInt(),
            panelText = 0xFF2A3038.toInt(),
            border = 0xFF2A3038.toInt(),
            frameHighlight = 0xFFFFFFF4.toInt(),
            frameShade = 0xFFB0A884.toInt(),
            trim = 0xFFE8B828.toInt(),
            infoBand = 0xFFC07038.toInt(),
            featureBand = 0xFFE05040.toInt(),
            primary = 0xFFE05040.toInt(),
            primaryHover = 0xFFF06050.toInt(),
            primaryPressed = 0xFFA83228.toInt(),
            primaryText = 0xFFFFFFFF.toInt(),
            secondary = 0xFFFAF7EC.toInt(),
            secondaryHover = 0xFFFFFFFF.toInt(),
            secondaryPressed = 0xFFD8D2B8.toInt(),
            secondaryText = 0xFF2A3038.toInt(),
            selected = 0xFFE8D020.toInt(),
            selectedText = 0xFF3A3000.toInt(),
            danger = 0xFFA83228.toInt(),
            dangerHover = 0xFFC8443A.toInt(),
            dangerPressed = 0xFF7A2420.toInt(),
            good = 0xFF5A9A48.toInt(),
            disabled = 0xFFB4B09C.toInt(),
            text = 0xFFF4F2EA.toInt(),
            textSecondary = 0xFFDCDCD0.toInt(),
            textDim = 0xFFB0BCC4.toInt(),
            cursor = 0xFFE05040.toInt(),
            textShadow = 0xFFD8D0B4.toInt(),
            panelTextDim = 0xFF6A6450.toInt()
        )
    ),
    FACTORY_SHOWROOM(
        "factory_showroom",
        UiThemePalette(
            backdrop = 0xE0101828.toInt(),
            shell = 0xFF34507A.toInt(),
            titleBar = 0xFF242444.toInt(),
            titleBarShade = 0xFF181830.toInt(),
            panel = 0xFFEEF7FA.toInt(),
            panelAlt = 0xFFC4E4EC.toInt(),
            panelText = 0xFF1F2A44.toInt(),
            border = 0xFF1F2A44.toInt(),
            frameHighlight = 0xFFFFFFFF.toInt(),
            frameShade = 0xFF84A4AC.toInt(),
            trim = 0xFF7CBCE4.toInt(),
            infoBand = 0xFFA4D4D4.toInt(),
            featureBand = 0xFF7CBCE4.toInt(),
            primary = 0xFF2F7FC4.toInt(),
            primaryHover = 0xFF4C9CE0.toInt(),
            primaryPressed = 0xFF20609C.toInt(),
            primaryText = 0xFFFFFFFF.toInt(),
            secondary = 0xFFF6FBFD.toInt(),
            secondaryHover = 0xFFFFFFFF.toInt(),
            secondaryPressed = 0xFFC4DCE4.toInt(),
            secondaryText = 0xFF1F2A44.toInt(),
            selected = 0xFF7CBCE4.toInt(),
            selectedText = 0xFF10223A.toInt(),
            danger = 0xFFC0504A.toInt(),
            dangerHover = 0xFFD86860.toInt(),
            dangerPressed = 0xFF8C3A36.toInt(),
            good = 0xFF4FA878.toInt(),
            disabled = 0xFF9CB0BC.toInt(),
            text = 0xFFEEF6FB.toInt(),
            textSecondary = 0xFFC8DCEA.toInt(),
            textDim = 0xFF9FB6CC.toInt(),
            cursor = 0xFFF07820.toInt(),
            textShadow = 0xFFC4D8E4.toInt(),
            panelTextDim = 0xFF50607A.toInt()
        )
    ),
    FACTORY_NIGHT(
        "factory_night",
        UiThemePalette(
            backdrop = 0xE0080A14.toInt(),
            shell = 0xFF1E2640.toInt(),
            titleBar = 0xFF151A30.toInt(),
            titleBarShade = 0xFF0C1020.toInt(),
            panel = 0xFF2C3A5E.toInt(),
            panelAlt = 0xFF24304F.toInt(),
            panelText = 0xFFE4EEF8.toInt(),
            border = 0xFF0C1020.toInt(),
            frameHighlight = 0xFF4A5E8C.toInt(),
            frameShade = 0xFF18203A.toInt(),
            trim = 0xFF6CB4DC.toInt(),
            infoBand = 0xFF3A4C78.toInt(),
            featureBand = 0xFF6CB4DC.toInt(),
            primary = 0xFF3C9CE0.toInt(),
            primaryHover = 0xFF5CB4F0.toInt(),
            primaryPressed = 0xFF2A74AC.toInt(),
            primaryText = 0xFFFFFFFF.toInt(),
            secondary = 0xFF3A4C78.toInt(),
            secondaryHover = 0xFF4A5E8C.toInt(),
            secondaryPressed = 0xFF2A3860.toInt(),
            secondaryText = 0xFFEEF6FB.toInt(),
            selected = 0xFF6CB4DC.toInt(),
            selectedText = 0xFF0C1A2C.toInt(),
            danger = 0xFFC8504A.toInt(),
            dangerHover = 0xFFE06A62.toInt(),
            dangerPressed = 0xFF8C3632.toInt(),
            good = 0xFF5CC08C.toInt(),
            disabled = 0xFF2A3452.toInt(),
            text = 0xFFEEF6FB.toInt(),
            textSecondary = 0xFFB8C8E0.toInt(),
            textDim = 0xFF8FA0C0.toInt(),
            cursor = 0xFFF0A040.toInt(),
            textShadow = 0xFF141C34.toInt(),
            panelTextDim = 0xFF9FB0CC.toInt()
        )
    ),
    FACTORY_TERMINAL(
        "factory_terminal",
        UiThemePalette(
            backdrop = 0xE0121A1E.toInt(),
            shell = 0xFF6A7A86.toInt(),
            titleBar = 0xFF2E3A44.toInt(),
            titleBarShade = 0xFF1E2830.toInt(),
            panel = 0xFFEEF2F3.toInt(),
            panelAlt = 0xFFD4DDE0.toInt(),
            panelText = 0xFF25303A.toInt(),
            border = 0xFF25303A.toInt(),
            frameHighlight = 0xFFFFFFFF.toInt(),
            frameShade = 0xFF98A6AE.toInt(),
            trim = 0xFF58C080.toInt(),
            infoBand = 0xFFA4D4D4.toInt(),
            featureBand = 0xFF58C080.toInt(),
            primary = 0xFF2F9A68.toInt(),
            primaryHover = 0xFF44B47E.toInt(),
            primaryPressed = 0xFF20744E.toInt(),
            primaryText = 0xFFFFFFFF.toInt(),
            secondary = 0xFFF7F9FA.toInt(),
            secondaryHover = 0xFFFFFFFF.toInt(),
            secondaryPressed = 0xFFCCD6DA.toInt(),
            secondaryText = 0xFF25303A.toInt(),
            selected = 0xFF7FD6A0.toInt(),
            selectedText = 0xFF0E3320.toInt(),
            danger = 0xFFC0504A.toInt(),
            dangerHover = 0xFFD86860.toInt(),
            dangerPressed = 0xFF8C3A36.toInt(),
            good = 0xFF58C080.toInt(),
            disabled = 0xFFA8B4BA.toInt(),
            text = 0xFFF2F6F7.toInt(),
            textSecondary = 0xFFD2DCE0.toInt(),
            textDim = 0xFFB6C2CA.toInt(),
            cursor = 0xFFE05040.toInt(),
            textShadow = 0xFFC8D2D6.toInt(),
            panelTextDim = 0xFF5A6A74.toInt()
        )
    );

    companion object {
        fun fromId(id: String?): UiPalettePreset? = entries.firstOrNull { it.id == id }
    }
}

/** Builds a theme from a style and a palette; each pairing is built once. */
object CobblemonUiThemeComposer {
    private val cache = ConcurrentHashMap<String, UiThemeSnapshot>()

    /** The theme id for [style] with [palette] when none is given: `style.palette`. */
    fun id(style: UiThemeStyle, palette: UiPalettePreset): String = "${style.id}.${palette.id}"

    fun compose(style: UiThemeStyle, palette: UiPalettePreset, id: String = id(style, palette)): UiThemeSnapshot {
        cache[id]?.let { return it }
        // Not computeIfAbsent: building a pixel frame theme can start the presets object, which composes its own
        // tower and factory presets here, and a nested computeIfAbsent on the same map fails or hangs.
        val built = when (style) {
            UiThemeStyle.PIXEL_FRAME -> CobblemonUiThemePresets.framedPixel(id, palette.palette)
            UiThemeStyle.DS_WINDOW -> dsWindow(id, palette.palette)
        }
        return cache.putIfAbsent(id, built) ?: built
    }

    private fun dsWindow(id: String, c: UiThemePalette): UiThemeSnapshot {
        val window = UiShape.RoundedRectangle(4)
        val control = UiShape.RoundedRectangle(3)
        val frame = UiBorder.WindowFrame(c.border, c.infoBand, 2, c.frameShade)
        val focus = UiSelectionIndicator.Outline(c.cursor, 1)
        val cursor = UiSelectionIndicator.Outline(c.cursor, 2)

        fun control(fill: Int?, text: Int, shadow: Int?, supporting: Int = text, bordered: Boolean = true,
            opacity: Float = 1f, indicator: UiSelectionIndicator = UiSelectionIndicator.None, pressed: Int = 0) =
            UiButtonStyle(
                surface = UiSurfaceStyle(control, fill?.let(UiFill::Solid) ?: UiFill.None,
                    if (bordered) UiBorder.Solid(c.border) else UiBorder.None, opacity),
                text = text,
                supportingText = supporting,
                selectionIndicator = indicator,
                pressedOffsetY = pressed,
                textShadowColor = shadow
            )

        val styles = buildMap {
            fun variant(variant: UiButtonVariant, normal: Int, hover: Int, pressed: Int, text: Int, shadow: Int?) {
                put(variant to UiWidgetState.NORMAL, control(normal, text, shadow))
                put(variant to UiWidgetState.HOVER, control(hover, text, shadow))
                put(variant to UiWidgetState.FOCUS, control(hover, text, shadow, indicator = focus))
                put(variant to UiWidgetState.PRESSED, control(pressed, text, shadow, pressed = 1))
                // Disabled controls keep a readable label: the panel's dim text, not the chrome's.
                put(variant to UiWidgetState.DISABLED, control(c.disabled, c.panelTextDim, null))
                put(variant to UiWidgetState.SELECTED, control(normal, text, shadow, indicator = cursor))
            }
            variant(UiButtonVariant.PRIMARY, c.primary, c.primaryHover, c.primaryPressed, c.primaryText, c.primaryPressed)
            variant(UiButtonVariant.SECONDARY, c.secondary, c.secondaryHover, c.secondaryPressed, c.secondaryText, c.textShadow)
            variant(UiButtonVariant.DANGER, c.danger, c.dangerHover, c.dangerPressed, 0xFFFFFFFF.toInt(), c.dangerPressed)
            variant(UiButtonVariant.ICON, c.secondary, c.secondaryHover, c.secondaryPressed, c.secondaryText, c.textShadow)
            // Ghost controls sit on the dark chrome, such as the header's close button.
            put(UiButtonVariant.GHOST to UiWidgetState.NORMAL, control(null, c.textSecondary, null, bordered = false))
            put(UiButtonVariant.GHOST to UiWidgetState.HOVER, control(c.titleBarShade, c.text, null, bordered = false))
            put(UiButtonVariant.GHOST to UiWidgetState.FOCUS, control(c.titleBarShade, c.text, null, bordered = false, indicator = focus))
            put(UiButtonVariant.GHOST to UiWidgetState.PRESSED, control(c.border, c.text, null, bordered = false, pressed = 1))
            put(UiButtonVariant.GHOST to UiWidgetState.DISABLED, control(null, c.textDim, null, bordered = false))
            put(UiButtonVariant.GHOST to UiWidgetState.SELECTED, control(null, c.text, null, bordered = false, indicator = cursor))
        }

        // List lines are unframed text on the window, lit on hover and marked by the cursor when chosen.
        val rows = mapOf(
            UiWidgetState.NORMAL to control(null, c.panelText, c.textShadow, c.panelTextDim, bordered = false),
            UiWidgetState.HOVER to control(c.panelAlt, c.panelText, c.textShadow, c.panelTextDim, bordered = false),
            UiWidgetState.FOCUS to control(c.panelAlt, c.panelText, c.textShadow, c.panelTextDim, bordered = false, indicator = focus),
            UiWidgetState.PRESSED to control(c.panelAlt, c.panelText, c.textShadow, c.panelTextDim, bordered = false, pressed = 1),
            UiWidgetState.DISABLED to control(null, c.textDim, null, c.textDim, bordered = false),
            UiWidgetState.SELECTED to control(null, c.panelText, c.textShadow, c.panelTextDim, bordered = false, indicator = cursor)
        )

        return UiThemeSnapshot.create(
            id = id,
            // Dim text lands on the light windows, so it takes the panel's dim colour rather than the chrome's.
            colors = c.colors().copy(textDim = c.panelTextDim),
            typography = UiTypography(titleScale = 1f, bodyScale = 1f, supportingScale = 0.75f),
            spacing = UiSpacing(xs = 2, small = 4, medium = 6, large = 10),
            surfaces = UiSurfaceTokens(
                shell = UiSurfaceStyle(UiShape.RoundedRectangle(5), UiFill.Solid(c.shell), UiBorder.WindowFrame(c.border, c.trim, 1)),
                panel = UiSurfaceStyle(window, UiFill.Solid(c.panel), frame),
                panelAlt = UiSurfaceStyle(window, UiFill.Solid(c.panelAlt), frame),
                panelText = c.panelText,
                panelAltText = c.panelText,
                panelTitle = UiPanelTitleStyle.RULE
            ),
            metrics = UiPixelMetrics.sizes,
            styles = styles,
            pixelDecorations = UiPixelDecorations(c.titleBar, c.titleBarShade, c.shell, c.titleBarShade),
            listRowStyles = rows
        )
    }
}

/**
 * Pixel League's control sizes, shared by every pixel and DS window theme. Pixel glyphs, Hangul above all, break
 * apart below full size, so the small control only loses height: twenty pixels with full-size text, like a vanilla
 * button. Kept apart from the presets object so building a theme never has to start it.
 */
internal object UiPixelMetrics {
    val sizes: Map<UiControlSize, UiButtonMetrics> = mapOf(
        UiControlSize.SMALL to UiButtonMetrics(20, 28, 10, 8, 3, 1f, 0.65f),
        UiControlSize.MEDIUM to UiButtonMetrics(26, 36, 12, 8, 4, 1f, 0.75f),
        UiControlSize.LARGE to UiButtonMetrics(36, 46, 14, 8, 4, 1.1f, 0.8f)
    )
}

/** The semantic colours every theme exposes, from a palette. */
internal fun UiThemePalette.colors(): UiColorPalette = UiColorPalette(
    backdrop = backdrop,
    shell = shell,
    panel = panel,
    panelAlt = panelAlt,
    border = border,
    borderBright = infoBand,
    accentPrimary = primary,
    accentSecondary = trim,
    accentCaution = featureBand,
    accentDanger = danger,
    accentGood = good,
    textPrimary = text,
    textSecondary = textSecondary,
    textDim = textDim
)
