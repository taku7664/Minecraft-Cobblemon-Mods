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

data class UiButtonStyle(val background: Int, val border: Int, val text: Int, val supportingText: Int)

class UiThemeSnapshot private constructor(
    val id: String,
    val colors: UiColorPalette,
    val typography: UiTypography,
    val spacing: UiSpacing,
    metrics: Map<UiControlSize, UiButtonMetrics>,
    styles: Map<Pair<UiButtonVariant, UiWidgetState>, UiButtonStyle>
) {
    private val metricsBySize = metrics.toMap()
    private val stylesByState = styles.toMap()

    fun metrics(size: UiControlSize): UiButtonMetrics = metricsBySize.getValue(size)

    fun style(variant: UiButtonVariant, state: UiWidgetState): UiButtonStyle =
        stylesByState.getValue(variant to state)

    companion object {
        fun create(
            id: String,
            colors: UiColorPalette,
            typography: UiTypography,
            spacing: UiSpacing,
            metrics: Map<UiControlSize, UiButtonMetrics>,
            styles: Map<Pair<UiButtonVariant, UiWidgetState>, UiButtonStyle>
        ): UiThemeSnapshot {
            require(THEME_ID.matches(id)) { "Invalid theme id: $id" }
            require(metrics.keys.containsAll(UiControlSize.entries)) { "Theme must define every control size" }
            val requiredStyles = UiButtonVariant.entries.flatMap { variant ->
                UiWidgetState.entries.map { state -> variant to state }
            }
            require(styles.keys.containsAll(requiredStyles)) { "Theme must define every button variant and state" }
            return UiThemeSnapshot(id, colors, typography, spacing, metrics, styles)
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
