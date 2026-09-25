package jbro.cobblemon.uikit

object CobblemonUiDefaultTheme {
    val colors = UiColorPalette(
        backdrop = 0xD9080C16.toInt(),
        shell = 0xF2080E1D.toInt(),
        panel = 0xF2101A2D.toInt(),
        panelAlt = 0xF20C1525.toInt(),
        border = 0xFF274562.toInt(),
        borderBright = 0xFF69D6E8.toInt(),
        accentPrimary = 0xFF39E4E4.toInt(),
        accentSecondary = 0xFF9868FF.toInt(),
        accentCaution = 0xFFFFC84A.toInt(),
        accentDanger = 0xFFFF667A.toInt(),
        accentGood = 0xFF62E39B.toInt(),
        textPrimary = 0xFFEAF7FF.toInt(),
        textSecondary = 0xFFB9CAD8.toInt(),
        textDim = 0xFF71859A.toInt()
    )

    val typography = UiTypography(titleScale = 1.25f, bodyScale = 1f, supportingScale = 0.75f)
    val spacing = UiSpacing(xs = 2, small = 4, medium = 8, large = 12)

    val metrics: Map<UiControlSize, UiButtonMetrics> = mapOf(
        UiControlSize.SMALL to UiButtonMetrics(18, 26, 8, 8, 3, 0.75f, 0.65f),
        UiControlSize.MEDIUM to UiButtonMetrics(24, 34, 12, 10, 4, 1f, 0.75f),
        UiControlSize.LARGE to UiButtonMetrics(32, 42, 16, 12, 5, 1.15f, 0.8f)
    )

    val styles: Map<Pair<UiButtonVariant, UiWidgetState>, UiButtonStyle> = buildMap {
        addVariant(
            UiButtonVariant.PRIMARY,
            base = colors.accentPrimary,
            hover = 0xFF6AF4F4.toInt(),
            pressed = 0xFF1BB5BF.toInt(),
            selected = colors.accentSecondary,
            text = 0xFF071018.toInt()
        )
        addVariant(
            UiButtonVariant.SECONDARY,
            base = 0xFF203D55.toInt(),
            hover = 0xFF2B5874.toInt(),
            pressed = 0xFF162C40.toInt(),
            selected = colors.accentSecondary,
            text = colors.textPrimary
        )
        addVariant(
            UiButtonVariant.DANGER,
            base = 0xFF733443.toInt(),
            hover = colors.accentDanger,
            pressed = 0xFF51242F.toInt(),
            selected = colors.accentDanger,
            text = colors.textPrimary
        )
        addVariant(
            UiButtonVariant.GHOST,
            base = 0x00101A2D,
            hover = 0xFF182B3E.toInt(),
            pressed = 0xFF0C1525.toInt(),
            selected = 0xFF24364E.toInt(),
            text = colors.textSecondary
        )
        addVariant(
            UiButtonVariant.ICON,
            base = colors.panelAlt,
            hover = 0xFF1B3448.toInt(),
            pressed = 0xFF09121F.toInt(),
            selected = colors.accentSecondary,
            text = colors.textPrimary
        )
    }

    val snapshot = UiThemeSnapshot.create(
        id = "cobblemon_default",
        colors = colors,
        typography = typography,
        spacing = spacing,
        metrics = metrics,
        styles = styles
    )

    private fun MutableMap<Pair<UiButtonVariant, UiWidgetState>, UiButtonStyle>.addVariant(
        variant: UiButtonVariant,
        base: Int,
        hover: Int,
        pressed: Int,
        selected: Int,
        text: Int
    ) {
        put(variant to UiWidgetState.NORMAL, UiButtonStyle(base, colors.border, text, colors.textSecondary))
        put(variant to UiWidgetState.HOVER, UiButtonStyle(hover, colors.borderBright, text, colors.textPrimary))
        put(variant to UiWidgetState.FOCUS, UiButtonStyle(hover, colors.accentCaution, text, colors.textPrimary))
        put(variant to UiWidgetState.PRESSED, UiButtonStyle(pressed, colors.borderBright, text, colors.textSecondary))
        put(variant to UiWidgetState.DISABLED, UiButtonStyle(0xFF18212D.toInt(), 0xFF273543.toInt(), colors.textDim, colors.textDim))
        put(variant to UiWidgetState.SELECTED, UiButtonStyle(selected, colors.textPrimary, colors.textPrimary, colors.textSecondary))
    }
}
