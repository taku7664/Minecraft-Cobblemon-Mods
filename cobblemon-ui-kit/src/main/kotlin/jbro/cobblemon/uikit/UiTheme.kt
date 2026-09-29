package jbro.cobblemon.uikit

import java.util.concurrent.atomic.AtomicReference

data class UiColorPalette(
    val backdrop: Int,
    val shell: Int,
    val panel: Int,
    val panelAlt: Int,
    val border: Int,
    val borderBright: Int,
    val accentPrimary: Int,
    val accentSecondary: Int,
    val accentCaution: Int,
    val accentDanger: Int,
    val accentGood: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val textDim: Int
)

data class UiTypography(
    val titleScale: Float,
    val bodyScale: Float,
    val supportingScale: Float
) {
    init {
        require(titleScale > 0f && bodyScale > 0f && supportingScale > 0f) {
            "Typography scales must be positive"
        }
    }
}

data class UiSpacing(val xs: Int, val small: Int, val medium: Int, val large: Int) {
    init {
        require(listOf(xs, small, medium, large).all { it >= 0 }) { "Spacing must not be negative" }
    }
}

data class UiButtonMetrics(
    val height: Int,
    val supportingHeight: Int,
    val horizontalPadding: Int,
    val iconSize: Int,
    val iconGap: Int,
    val titleScale: Float,
    val supportingScale: Float
) {
    init {
        require(height > 0 && supportingHeight >= height) { "Button heights are invalid" }
        require(horizontalPadding >= 0 && iconSize >= 0 && iconGap >= 0) { "Button metrics must not be negative" }
        require(titleScale > 0f && supportingScale > 0f) { "Button text scales must be positive" }
    }
}

data class UiSurfaceTokens(
    val shell: UiSurfaceStyle,
    val panel: UiSurfaceStyle,
    val panelAlt: UiSurfaceStyle,
    val shellText: Int? = null,
    val panelText: Int? = null,
    val panelAltText: Int? = null,
    val panelTitle: UiPanelTitleStyle = UiPanelTitleStyle.TEXT
)

/** How a titled panel shows its title. */
enum class UiPanelTitleStyle {
    /** Plain text at the panel's padding. */
    TEXT,

    /** A filled band across the top; a featured panel takes the caution colour, others the bright border. */
    BAND,

    /** Title text over a rule in those colours, as a DS menu window heads its list. */
    RULE
}

data class UiButtonStyle(
    val surface: UiSurfaceStyle,
    val text: Int,
    val supportingText: Int,
    val selectionIndicator: UiSelectionIndicator = UiSelectionIndicator.None,
    val pressedOffsetY: Int = 0,
    val textShadow: Boolean = false,
    /**
     * A one-pixel drop shadow in this colour under the text, like the pale shadow of DS menu text. It is drawn
     * instead of Minecraft's black shadow and does not depend on [textShadow].
     */
    val textShadowColor: Int? = null
)

sealed interface UiSelectionIndicator {
    data object None : UiSelectionIndicator
    data class Sprite(val icon: UiIcon) : UiSelectionIndicator

    /**
     * A [width]-pixel cursor frame in [color] around the whole widget, following its shape, over an unchanged
     * fill: the DS menu cursor, which marks a choice without repainting it.
     */
    data class Outline(val color: Int, val width: Int = 2) : UiSelectionIndicator {
        init {
            require(width > 0) { "Selection outline width must be positive" }
        }
    }
}

data class UiPixelDecorations(
    val titleBar: Int,
    val titleBarShade: Int,
    val ditherLight: Int,
    val ditherDark: Int
)

class UiThemeSnapshot private constructor(
    val id: String,
    val colors: UiColorPalette,
    val typography: UiTypography,
    val spacing: UiSpacing,
    val surfaces: UiSurfaceTokens,
    val pixelDecorations: UiPixelDecorations?,
    metrics: Map<UiControlSize, UiButtonMetrics>,
    styles: Map<Pair<UiButtonVariant, UiWidgetState>, UiButtonStyle>,
    listRowStyles: Map<UiWidgetState, UiButtonStyle>?
) {
    private val metricsBySize = metrics.toMap()
    private val stylesByState = styles.toMap()
    private val listRowsByState = listRowStyles?.toMap()

    fun metrics(size: UiControlSize): UiButtonMetrics = metricsBySize.getValue(size)

    fun style(variant: UiButtonVariant, state: UiWidgetState): UiButtonStyle =
        stylesByState.getValue(variant to state)

    /** A list row in [state]; themes without their own row styles draw rows as secondary buttons. */
    fun listRowStyle(state: UiWidgetState): UiButtonStyle =
        listRowsByState?.get(state) ?: style(UiButtonVariant.SECONDARY, state)

    companion object {
        fun create(
            id: String,
            colors: UiColorPalette,
            typography: UiTypography,
            spacing: UiSpacing,
            surfaces: UiSurfaceTokens,
            metrics: Map<UiControlSize, UiButtonMetrics>,
            styles: Map<Pair<UiButtonVariant, UiWidgetState>, UiButtonStyle>,
            pixelDecorations: UiPixelDecorations? = null,
            listRowStyles: Map<UiWidgetState, UiButtonStyle>? = null
        ): UiThemeSnapshot {
            require(THEME_ID.matches(id)) { "Invalid theme id: $id" }
            require(metrics.keys.containsAll(UiControlSize.entries)) { "Theme must define every control size" }
            val requiredStyles = UiButtonVariant.entries.flatMap { variant ->
                UiWidgetState.entries.map { state -> variant to state }
            }
            require(styles.keys.containsAll(requiredStyles)) { "Theme must define every button variant and state" }
            require(listRowStyles == null || listRowStyles.keys.containsAll(UiWidgetState.entries)) {
                "List row styles must cover every state"
            }
            return UiThemeSnapshot(id, colors, typography, spacing, surfaces, pixelDecorations, metrics, styles, listRowStyles)
        }

        private val THEME_ID = Regex("[a-z][a-z0-9_.-]*")
    }
}

class UiThemeRegistry(initial: UiThemeSnapshot) {
    private val current = AtomicReference(initial)

    fun snapshot(): UiThemeSnapshot = current.get()

    fun install(snapshot: UiThemeSnapshot) {
        current.set(snapshot)
    }
}

object CobblemonUiThemes {
    val registry = UiThemeRegistry(CobblemonUiDefaultTheme.snapshot)
}
