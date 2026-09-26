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

    val surfaces = UiSurfaceTokens(
        shell = UiSurfaceStyle(
            shape = UiShape.Chamfer(6),
            fill = UiFill.VerticalGradient(0xF214263B.toInt(), colors.shell),
            border = UiBorder.Solid(colors.borderBright, width = 2),
            backgroundOpacity = 0.94f
        ),
        panel = UiSurfaceStyle(
            shape = UiShape.Chamfer(3),
            fill = UiFill.VerticalGradient(0xF2182941.toInt(), colors.panel),
            border = UiBorder.Solid(colors.border)
        ),
        panelAlt = UiSurfaceStyle(
            shape = UiShape.Rectangle,
            fill = UiFill.Solid(colors.panelAlt),
            border = UiBorder.None,
            backgroundOpacity = 0.88f
        )
    )

    val metrics: Map<UiControlSize, UiButtonMetrics> = mapOf(
        UiControlSize.SMALL to UiButtonMetrics(18, 26, 8, 8, 3, 0.75f, 0.65f),
        UiControlSize.MEDIUM to UiButtonMetrics(24, 34, 12, 10, 4, 1f, 0.75f),
        UiControlSize.LARGE to UiButtonMetrics(32, 42, 16, 12, 5, 1.15f, 0.8f)
    )

    val styles: Map<Pair<UiButtonVariant, UiWidgetState>, UiButtonStyle> = buildMap {
        addVariant(
            variant = UiButtonVariant.PRIMARY,
            shape = UiShape.Chamfer(4, setOf(UiCorner.TOP_RIGHT, UiCorner.BOTTOM_LEFT)),
            base = colors.accentPrimary,
            hover = 0xFF6AF4F4.toInt(),
            pressed = 0xFF1BB5BF.toInt(),
            selected = colors.accentSecondary,
            text = 0xFF071018.toInt(),
            gradient = true
        )
        addVariant(
            variant = UiButtonVariant.SECONDARY,
            shape = UiShape.Rectangle,
            base = 0xFF203D55.toInt(),
            hover = 0xFF2B5874.toInt(),
            pressed = 0xFF162C40.toInt(),
            selected = colors.accentSecondary,
            text = colors.textPrimary
        )
        addVariant(
            variant = UiButtonVariant.DANGER,
            shape = UiShape.Chamfer(4, setOf(UiCorner.TOP_LEFT, UiCorner.BOTTOM_RIGHT)),
            base = 0xFF733443.toInt(),
            hover = colors.accentDanger,
            pressed = 0xFF51242F.toInt(),
            selected = colors.accentDanger,
            text = colors.textPrimary,
            gradient = true
        )
        addVariant(
            variant = UiButtonVariant.GHOST,
            shape = UiShape.Rectangle,
            base = colors.panel,
            hover = 0xFF182B3E.toInt(),
            pressed = 0xFF0C1525.toInt(),
            selected = 0xFF24364E.toInt(),
            text = colors.textSecondary,
            bordered = false,
            normalOpacity = 0f
        )
        addVariant(
            variant = UiButtonVariant.ICON,
            shape = UiShape.Chamfer(3),
            base = colors.panelAlt,
            hover = 0xFF1B3448.toInt(),
            pressed = 0xFF09121F.toInt(),
            selected = colors.accentSecondary,
            text = colors.textPrimary,
            gradient = true
        )
    }

    val snapshot = UiThemeSnapshot.create(
        id = "league_neon",
        colors = colors,
        typography = typography,
        spacing = spacing,
        surfaces = surfaces,
        metrics = metrics,
        styles = styles
    )

    private fun MutableMap<Pair<UiButtonVariant, UiWidgetState>, UiButtonStyle>.addVariant(
        variant: UiButtonVariant,
        shape: UiShape,
        base: Int,
        hover: Int,
        pressed: Int,
        selected: Int,
        text: Int,
        gradient: Boolean = false,
        bordered: Boolean = true,
        normalOpacity: Float = 1f
    ) {
        val hoverOpacity = if (bordered) 1f else 0.82f
        val focusOpacity = if (bordered) 1f else 0.9f
        val pressedOpacity = if (bordered) 1f else 0.94f
        val disabledOpacity = if (bordered) 0.7f else 0.45f
        put(variant to UiWidgetState.NORMAL, style(shape, base, colors.border, text, gradient, bordered, normalOpacity))
        put(variant to UiWidgetState.HOVER, style(shape, hover, colors.borderBright, text, gradient, bordered, hoverOpacity))
        put(variant to UiWidgetState.FOCUS, style(shape, hover, colors.accentCaution, text, gradient, bordered, focusOpacity))
        put(variant to UiWidgetState.PRESSED, style(shape, pressed, colors.borderBright, text, gradient, bordered, pressedOpacity))
        put(
            variant to UiWidgetState.DISABLED,
            style(shape, 0xFF18212D.toInt(), 0xFF273543.toInt(), colors.textDim, false, bordered, disabledOpacity)
        )
        put(
            variant to UiWidgetState.SELECTED,
            style(shape, selected, colors.textPrimary, colors.textPrimary, gradient, bordered, 1f)
        )
    }

    private fun style(
        shape: UiShape,
        background: Int,
        borderColor: Int,
        text: Int,
        gradient: Boolean,
        bordered: Boolean,
        opacity: Float
    ): UiButtonStyle = UiButtonStyle(
        surface = UiSurfaceStyle(
            shape = shape,
            fill = if (gradient) {
                UiFill.VerticalGradient(background, shade(background, 0.68f))
            } else {
                UiFill.Solid(background)
            },
            border = if (bordered) UiBorder.Solid(borderColor) else UiBorder.None,
            backgroundOpacity = opacity
        ),
        text = text,
        supportingText = if (text == colors.textDim) colors.textDim else colors.textSecondary
    )

    private fun shade(color: Int, factor: Float): Int {
        val alpha = color ushr 24 and 0xFF
        val red = ((color ushr 16 and 0xFF) * factor).toInt().coerceIn(0, 255)
        val green = ((color ushr 8 and 0xFF) * factor).toInt().coerceIn(0, 255)
        val blue = ((color and 0xFF) * factor).toInt().coerceIn(0, 255)
        return alpha shl 24 or (red shl 16) or (green shl 8) or blue
    }
}
