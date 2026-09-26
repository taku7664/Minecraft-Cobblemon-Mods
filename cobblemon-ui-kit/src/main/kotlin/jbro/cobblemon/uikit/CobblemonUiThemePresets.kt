package jbro.cobblemon.uikit

enum class UiThemePreset(val id: String) {
    LEAGUE_NEON("league_neon"),
    PIXEL_LEAGUE("pixel_league"),
    GALAR_STADIUM("galar_stadium"),
    PALDEA_PORTAL("paldea_portal"),
    HOENN_PIXEL("hoenn_pixel"),
    JOHTO_TOUCH("johto_touch"),
    UNOVA_PIXEL("unova_pixel");

    companion object {
        fun fromId(id: String?): UiThemePreset? = entries.firstOrNull { it.id == id }
    }
}

object CobblemonUiThemePresets {
    private val snapshots: Map<UiThemePreset, UiThemeSnapshot> = linkedMapOf(
        UiThemePreset.LEAGUE_NEON to CobblemonUiDefaultTheme.snapshot,
        UiThemePreset.PIXEL_LEAGUE to pixelLeague(),
        UiThemePreset.GALAR_STADIUM to galarStadium(),
        UiThemePreset.PALDEA_PORTAL to paldeaPortal(),
        UiThemePreset.HOENN_PIXEL to hoennPixel(),
        UiThemePreset.JOHTO_TOUCH to johtoTouch(),
        UiThemePreset.UNOVA_PIXEL to unovaPixel()
    )

    fun snapshot(preset: UiThemePreset): UiThemeSnapshot = snapshots.getValue(preset)

    fun install(preset: UiThemePreset) {
        CobblemonUiThemes.registry.install(snapshot(preset))
    }

    fun currentPreset(): UiThemePreset =
        UiThemePreset.fromId(CobblemonUiThemes.registry.snapshot().id) ?: UiThemePreset.LEAGUE_NEON

    private fun pixelLeague(): UiThemeSnapshot {
        val colors = UiColorPalette(
            backdrop = 0xE0121820.toInt(),
            shell = 0xFF31566D.toInt(),
            panel = 0xFFF1E9C9.toInt(),
            panelAlt = 0xFFBFD3D4.toInt(),
            border = 0xFF17212B.toInt(),
            borderBright = 0xFFFFE69A.toInt(),
            accentPrimary = 0xFF3F8FC4.toInt(),
            accentSecondary = 0xFFD75845.toInt(),
            accentCaution = 0xFFF3C84D.toInt(),
            accentDanger = 0xFFB6423A.toInt(),
            accentGood = 0xFF63A75A.toInt(),
            textPrimary = 0xFFFFF6D5.toInt(),
            textSecondary = 0xFFD7E9E7.toInt(),
            textDim = 0xFF71848B.toInt()
        )
        val shellFrame = UiBorder.PixelFrame(
            outerColor = colors.border,
            highlightColor = colors.borderBright,
            shadeColor = 0xFF8D7038.toInt(),
            shadowColor = 0xC8080B0F.toInt(),
            shadowOffset = 3
        )
        val cardFrame = UiBorder.PixelFrame(
            outerColor = colors.border,
            highlightColor = 0xFFFFFFFF.toInt(),
            shadeColor = 0xFF8C9A91.toInt(),
            shadowColor = 0xA8080B0F.toInt(),
            shadowOffset = 2
        )
        val selector = UiSelectionIndicator.Sprite(
            UiIcon("cobblemon_ui_kit", "textures/gui/pixel/selector.png")
        )
        val metrics = mapOf(
            UiControlSize.SMALL to UiButtonMetrics(20, 28, 10, 8, 3, 0.75f, 0.65f),
            UiControlSize.MEDIUM to UiButtonMetrics(26, 36, 12, 8, 4, 1f, 0.75f),
            UiControlSize.LARGE to UiButtonMetrics(36, 46, 14, 8, 4, 1.1f, 0.8f)
        )
        val styles = buildMap {
            addPixelVariant(
                UiButtonVariant.PRIMARY,
                normal = colors.accentPrimary,
                hover = 0xFF62AFE0.toInt(),
                pressed = 0xFF2B668E.toInt(),
                selected = 0xFF5577B9.toInt(),
                text = colors.textPrimary,
                frame = cardFrame,
                selector = selector,
                disabled = 0xFF65747C.toInt()
            )
            addPixelVariant(
                UiButtonVariant.SECONDARY,
                normal = 0xFFF5EFD7.toInt(),
                hover = 0xFFFFFFFF.toInt(),
                pressed = 0xFFD1C9AA.toInt(),
                selected = colors.accentCaution,
                text = colors.border,
                frame = cardFrame,
                selector = selector,
                disabled = 0xFFAAA991.toInt()
            )
            addPixelVariant(
                UiButtonVariant.DANGER,
                normal = colors.accentDanger,
                hover = 0xFFD75C4F.toInt(),
                pressed = 0xFF7E2A27.toInt(),
                selected = colors.accentSecondary,
                text = colors.textPrimary,
                frame = cardFrame,
                selector = selector,
                disabled = 0xFF775D58.toInt()
            )
            addPixelVariant(
                UiButtonVariant.ICON,
                normal = colors.accentCaution,
                hover = 0xFFFFE07A.toInt(),
                pressed = 0xFFB88C2C.toInt(),
                selected = colors.accentGood,
                text = colors.border,
                frame = cardFrame,
                selector = selector,
                disabled = 0xFF99916D.toInt()
            )
            addPixelGhost(colors, selector)
        }
        return UiThemeSnapshot.create(
            id = UiThemePreset.PIXEL_LEAGUE.id,
            colors = colors,
            typography = UiTypography(titleScale = 1f, bodyScale = 1f, supportingScale = 0.75f),
            spacing = UiSpacing(xs = 2, small = 4, medium = 6, large = 10),
            surfaces = UiSurfaceTokens(
                shell = UiSurfaceStyle(UiShape.Rectangle, UiFill.Solid(colors.shell), shellFrame),
                panel = UiSurfaceStyle(UiShape.Rectangle, UiFill.Solid(colors.panel), cardFrame),
                panelAlt = UiSurfaceStyle(UiShape.Rectangle, UiFill.Solid(colors.panelAlt), cardFrame)
            ),
            metrics = metrics,
            styles = styles,
            pixelDecorations = UiPixelDecorations(
                titleBar = 0xFF243D53.toInt(),
                titleBarShade = 0xFF152735.toInt(),
                ditherLight = 0xFF416B7E.toInt(),
                ditherDark = 0xFF2A4B60.toInt()
            )
        )
    }

    private fun galarStadium(): UiThemeSnapshot {
        val colors = UiColorPalette(
            backdrop = 0xD9072630.toInt(),
            shell = 0xF20B2630.toInt(),
            panel = 0xF21C333A.toInt(),
            panelAlt = 0xE912242A.toInt(),
            border = 0xFF3D6870.toInt(),
            borderBright = 0xFF72F1E2.toInt(),
            accentPrimary = 0xFF19C9B5.toInt(),
            accentSecondary = 0xFFF04A91.toInt(),
            accentCaution = 0xFFFFD447.toInt(),
            accentDanger = 0xFFEC4D70.toInt(),
            accentGood = 0xFF62D19B.toInt(),
            textPrimary = 0xFFF6FFFD.toInt(),
            textSecondary = 0xFFC9DFDF.toInt(),
            textDim = 0xFF779398.toInt()
        )
        return createTheme(
            UiThemePreset.GALAR_STADIUM,
            colors,
            UiSurfaceTokens(
                shell = surface(chamfer(7, UiCorner.TOP_RIGHT, UiCorner.BOTTOM_LEFT), 0xF2164650.toInt(), colors.shell, colors.borderBright, 2, true),
                panel = surface(chamfer(4, UiCorner.TOP_RIGHT, UiCorner.BOTTOM_LEFT), 0xF228454B.toInt(), colors.panel, colors.border, 1, true),
                panelAlt = surface(UiShape.Rectangle, colors.panelAlt, colors.panelAlt, colors.border, 0, false, 0.92f)
            ),
            buttonRecipes(
                primary = button(chamfer(5, UiCorner.TOP_RIGHT, UiCorner.BOTTOM_LEFT), colors.accentPrimary, 0xFF4DE3D2.toInt(), 0xFF0D8F86.toInt(), colors.accentCaution, 0xFF061B20.toInt(), true),
                secondary = button(chamfer(2, UiCorner.TOP_RIGHT), 0xFFF0F6F4.toInt(), 0xFFFFFFFF.toInt(), 0xFFC4D3D1.toInt(), colors.accentPrimary, 0xFF102A30.toInt()),
                danger = button(chamfer(5, UiCorner.TOP_LEFT, UiCorner.BOTTOM_RIGHT), colors.accentSecondary, 0xFFFF77AB.toInt(), 0xFFAE2D66.toInt(), colors.accentDanger, colors.textPrimary, true),
                ghost = button(UiShape.Rectangle, colors.panel, 0xFF2A4A50.toInt(), 0xFF13272D.toInt(), 0xFF355A60.toInt(), colors.textSecondary, border = false, opacity = 0f),
                icon = button(chamfer(3), 0xFF21434A.toInt(), 0xFF2F6067.toInt(), 0xFF142E34.toInt(), colors.accentCaution, colors.textPrimary, true)
            )
        )
    }

    private fun paldeaPortal(): UiThemeSnapshot {
        val colors = UiColorPalette(
            backdrop = 0xD9034E87.toInt(),
            shell = 0xF20B78B8.toInt(),
            panel = 0xF20B315A.toInt(),
            panelAlt = 0xE907274B.toInt(),
            border = 0xFF2F8CC0.toInt(),
            borderBright = 0xFFFFE03B.toInt(),
            accentPrimary = 0xFFFFD522.toInt(),
            accentSecondary = 0xFF20B8EB.toInt(),
            accentCaution = 0xFFFFE54D.toInt(),
            accentDanger = 0xFFFF6759.toInt(),
            accentGood = 0xFF54D994.toInt(),
            textPrimary = 0xFFFFFFFF.toInt(),
            textSecondary = 0xFFD5EDFF.toInt(),
            textDim = 0xFF7FA9C9.toInt()
        )
        return createTheme(
            UiThemePreset.PALDEA_PORTAL,
            colors,
            UiSurfaceTokens(
                shell = surface(chamfer(5, UiCorner.TOP_LEFT, UiCorner.BOTTOM_RIGHT), 0xF21AA5D9.toInt(), colors.shell, colors.borderBright, 2, true),
                panel = surface(UiShape.Rectangle, 0xF20E3D69.toInt(), colors.panel, colors.border, 1, true),
                panelAlt = surface(UiShape.Rectangle, colors.panelAlt, colors.panelAlt, colors.border, 0, false, 0.9f)
            ),
            buttonRecipes(
                primary = button(chamfer(4, UiCorner.TOP_LEFT, UiCorner.BOTTOM_LEFT), colors.accentPrimary, 0xFFFFE75A.toInt(), 0xFFD6A900.toInt(), 0xFFFFF08A.toInt(), 0xFF13233B.toInt()),
                secondary = button(UiShape.Rectangle, 0xFF103A67.toInt(), 0xFF185184.toInt(), 0xFF0A294D.toInt(), colors.accentSecondary, colors.textPrimary),
                danger = button(chamfer(4, UiCorner.TOP_LEFT, UiCorner.BOTTOM_LEFT), colors.accentDanger, 0xFFFF8579.toInt(), 0xFFC8423B.toInt(), colors.accentCaution, colors.textPrimary),
                ghost = button(UiShape.Rectangle, colors.panel, 0xFF185184.toInt(), 0xFF0A294D.toInt(), 0xFF1B5B8C.toInt(), colors.textSecondary, border = false, opacity = 0f),
                icon = button(chamfer(4), 0xFF0E3158.toInt(), 0xFF185184.toInt(), 0xFF071E39.toInt(), colors.accentPrimary, colors.textPrimary)
            )
        )
    }

    private fun hoennPixel(): UiThemeSnapshot {
        val colors = UiColorPalette(
            backdrop = 0xDC364528.toInt(),
            shell = 0xF2546C36.toInt(),
            panel = 0xF234617B.toInt(),
            panelAlt = 0xF227465E.toInt(),
            border = 0xFF1B2934.toInt(),
            borderBright = 0xFFF5E5A3.toInt(),
            accentPrimary = 0xFF4D9CD0.toInt(),
            accentSecondary = 0xFF7957A6.toInt(),
            accentCaution = 0xFFF2C047.toInt(),
            accentDanger = 0xFFC35C32.toInt(),
            accentGood = 0xFF72B86B.toInt(),
            textPrimary = 0xFFF8F4DC.toInt(),
            textSecondary = 0xFFE4EDF1.toInt(),
            textDim = 0xFF97A6A8.toInt()
        )
        return createTheme(
            UiThemePreset.HOENN_PIXEL,
            colors,
            UiSurfaceTokens(
                shell = surface(UiShape.Rectangle, colors.shell, colors.shell, colors.border, 2, false),
                panel = surface(UiShape.Rectangle, colors.panel, colors.panel, colors.borderBright, 2, false),
                panelAlt = surface(UiShape.Rectangle, colors.panelAlt, colors.panelAlt, colors.border, 1, false)
            ),
            buttonRecipes(
                primary = button(UiShape.Rectangle, colors.accentPrimary, 0xFF69B8E5.toInt(), 0xFF3378A7.toInt(), colors.accentCaution, colors.textPrimary, borderWidth = 2),
                secondary = button(UiShape.Rectangle, 0xFFF3F0E6.toInt(), 0xFFFFFFFF.toInt(), 0xFFD5D0C3.toInt(), colors.accentPrimary, 0xFF293039.toInt(), borderWidth = 2),
                danger = button(UiShape.Rectangle, colors.accentDanger, 0xFFDE7950.toInt(), 0xFF8E3F27.toInt(), 0xFFD55B35.toInt(), colors.textPrimary, borderWidth = 2),
                ghost = button(UiShape.Rectangle, colors.panelAlt, 0xFF355D76.toInt(), 0xFF1E394D.toInt(), colors.accentSecondary, colors.textSecondary, border = false, opacity = 0f),
                icon = button(chamfer(3), colors.accentSecondary, 0xFF9675BE.toInt(), 0xFF573C7E.toInt(), colors.accentCaution, colors.textPrimary, borderWidth = 2)
            )
        )
    }

    private fun johtoTouch(): UiThemeSnapshot {
        val colors = UiColorPalette(
            backdrop = 0xD92A3E39.toInt(),
            shell = 0xF238A46F.toInt(),
            panel = 0xF22C7259.toInt(),
            panelAlt = 0xECF0F1E9.toInt(),
            border = 0xFF24483E.toInt(),
            borderBright = 0xFFF8FBF5.toInt(),
            accentPrimary = 0xFF47B982.toInt(),
            accentSecondary = 0xFFE59A42.toInt(),
            accentCaution = 0xFFF4C34C.toInt(),
            accentDanger = 0xFFE15C62.toInt(),
            accentGood = 0xFF72C27D.toInt(),
            textPrimary = 0xFFFFFFFF.toInt(),
            textSecondary = 0xFFE1F2E9.toInt(),
            textDim = 0xFF91B0A3.toInt()
        )
        return createTheme(
            UiThemePreset.JOHTO_TOUCH,
            colors,
            UiSurfaceTokens(
                shell = surface(chamfer(5), 0xF24AB883.toInt(), colors.shell, colors.borderBright, 2, true),
                panel = surface(chamfer(3), 0xF2378467.toInt(), colors.panel, colors.border, 1, true),
                panelAlt = surface(UiShape.Rectangle, 0xF2285F4C.toInt(), 0xF2234D40.toInt(), colors.border, 1, false)
            ),
            buttonRecipes(
                primary = button(chamfer(3), colors.accentPrimary, 0xFF6DCE9E.toInt(), 0xFF2D8D60.toInt(), colors.accentCaution, colors.textPrimary),
                secondary = button(UiShape.Rectangle, 0xFFF1F3EC.toInt(), 0xFFFFFFFF.toInt(), 0xFFD6DAD1.toInt(), colors.accentSecondary, 0xFF34423D.toInt()),
                danger = button(chamfer(3), colors.accentDanger, 0xFFF17E82.toInt(), 0xFFAE3D45.toInt(), colors.accentSecondary, colors.textPrimary),
                ghost = button(UiShape.Rectangle, colors.panel, 0xFF409572.toInt(), 0xFF235744.toInt(), 0xFF4F9A79.toInt(), colors.textSecondary, border = false, opacity = 0f),
                icon = button(chamfer(4), colors.accentSecondary, 0xFFF0B263.toInt(), 0xFFB46D26.toInt(), colors.accentPrimary, 0xFF382718.toInt())
            )
        )
    }

    private fun unovaPixel(): UiThemeSnapshot {
        val colors = UiColorPalette(
            backdrop = 0xE005090C.toInt(),
            shell = 0xF20D1115.toInt(),
            panel = 0xF2171D22.toInt(),
            panelAlt = 0xF210151A.toInt(),
            border = 0xFF48545D.toInt(),
            borderBright = 0xFF71DAE0.toInt(),
            accentPrimary = 0xFF4FC5CD.toInt(),
            accentSecondary = 0xFFE4585F.toInt(),
            accentCaution = 0xFFD7E365.toInt(),
            accentDanger = 0xFFDF4F57.toInt(),
            accentGood = 0xFF62C97A.toInt(),
            textPrimary = 0xFFF4F7F8.toInt(),
            textSecondary = 0xFFC5CED3.toInt(),
            textDim = 0xFF6C777E.toInt()
        )
        return createTheme(
            UiThemePreset.UNOVA_PIXEL,
            colors,
            UiSurfaceTokens(
                shell = surface(UiShape.Rectangle, colors.shell, colors.shell, colors.borderBright, 1, false),
                panel = surface(UiShape.Rectangle, colors.panel, colors.panel, colors.border, 1, false),
                panelAlt = surface(UiShape.Rectangle, colors.panelAlt, colors.panelAlt, colors.border, 1, false)
            ),
            buttonRecipes(
                primary = button(UiShape.Rectangle, 0xFF17262B.toInt(), 0xFF203A40.toInt(), 0xFF0B171B.toInt(), colors.accentPrimary, colors.textPrimary),
                secondary = button(UiShape.Rectangle, 0xFF1C2227.toInt(), 0xFF292F35.toInt(), 0xFF11161A.toInt(), 0xFF324B51.toInt(), colors.textPrimary),
                danger = button(UiShape.Rectangle, 0xFF342026.toInt(), 0xFF542B32.toInt(), 0xFF23151A.toInt(), colors.accentSecondary, colors.textPrimary),
                ghost = button(UiShape.Rectangle, colors.panelAlt, 0xFF262D32.toInt(), 0xFF0C1013.toInt(), 0xFF2B3C40.toInt(), colors.textSecondary, border = false, opacity = 0f),
                icon = button(UiShape.Rectangle, 0xFF18292E.toInt(), 0xFF23434A.toInt(), 0xFF0C171A.toInt(), colors.accentCaution, colors.textPrimary)
            )
        )
    }

    private fun createTheme(
        preset: UiThemePreset,
        colors: UiColorPalette,
        surfaces: UiSurfaceTokens,
        recipes: Map<UiButtonVariant, ButtonRecipe>
    ): UiThemeSnapshot = UiThemeSnapshot.create(
        id = preset.id,
        colors = colors,
        typography = CobblemonUiDefaultTheme.typography,
        spacing = CobblemonUiDefaultTheme.spacing,
        surfaces = surfaces,
        metrics = CobblemonUiDefaultTheme.metrics,
        styles = buildMap {
            recipes.forEach { (variant, recipe) -> addVariant(variant, recipe, colors) }
        }
    )

    private fun MutableMap<Pair<UiButtonVariant, UiWidgetState>, UiButtonStyle>.addPixelVariant(
        variant: UiButtonVariant,
        normal: Int,
        hover: Int,
        pressed: Int,
        selected: Int,
        text: Int,
        frame: UiBorder.PixelFrame,
        selector: UiSelectionIndicator,
        disabled: Int
    ) {
        fun pixelStyle(
            fill: Int,
            textColor: Int = text,
            indicator: UiSelectionIndicator = UiSelectionIndicator.None,
            pressedOffsetY: Int = 0
        ) = UiButtonStyle(
            surface = UiSurfaceStyle(
                shape = UiShape.Rectangle,
                fill = UiFill.Solid(fill),
                border = frame
            ),
            text = textColor,
            supportingText = textColor,
            selectionIndicator = indicator,
            pressedOffsetY = pressedOffsetY
        )

        put(variant to UiWidgetState.NORMAL, pixelStyle(normal))
        put(variant to UiWidgetState.HOVER, pixelStyle(hover))
        put(variant to UiWidgetState.FOCUS, pixelStyle(hover, indicator = selector))
        put(variant to UiWidgetState.PRESSED, pixelStyle(pressed, pressedOffsetY = 1))
        put(variant to UiWidgetState.DISABLED, pixelStyle(disabled, 0xFF4E5659.toInt()))
        put(variant to UiWidgetState.SELECTED, pixelStyle(selected, indicator = selector))
    }

    private fun MutableMap<Pair<UiButtonVariant, UiWidgetState>, UiButtonStyle>.addPixelGhost(
        colors: UiColorPalette,
        selector: UiSelectionIndicator
    ) {
        fun ghost(
            fill: Int,
            opacity: Float,
            indicator: UiSelectionIndicator = UiSelectionIndicator.None,
            pressedOffsetY: Int = 0
        ) = UiButtonStyle(
            surface = UiSurfaceStyle(
                shape = UiShape.Rectangle,
                fill = UiFill.Solid(fill),
                border = UiBorder.None,
                backgroundOpacity = opacity
            ),
            text = if (opacity < 0.5f) colors.textSecondary else colors.textPrimary,
            supportingText = colors.textSecondary,
            selectionIndicator = indicator,
            pressedOffsetY = pressedOffsetY
        )

        put(UiButtonVariant.GHOST to UiWidgetState.NORMAL, ghost(colors.shell, 0f))
        put(UiButtonVariant.GHOST to UiWidgetState.HOVER, ghost(colors.panelAlt, 0.75f))
        put(UiButtonVariant.GHOST to UiWidgetState.FOCUS, ghost(colors.panelAlt, 0.85f, selector))
        put(UiButtonVariant.GHOST to UiWidgetState.PRESSED, ghost(colors.border, 0.7f, pressedOffsetY = 1))
        put(UiButtonVariant.GHOST to UiWidgetState.DISABLED, ghost(colors.shell, 0f))
        put(UiButtonVariant.GHOST to UiWidgetState.SELECTED, ghost(colors.accentPrimary, 1f, selector))
    }

    private fun buttonRecipes(
        primary: ButtonRecipe,
        secondary: ButtonRecipe,
        danger: ButtonRecipe,
        ghost: ButtonRecipe,
        icon: ButtonRecipe
    ): Map<UiButtonVariant, ButtonRecipe> = mapOf(
        UiButtonVariant.PRIMARY to primary,
        UiButtonVariant.SECONDARY to secondary,
        UiButtonVariant.DANGER to danger,
        UiButtonVariant.GHOST to ghost,
        UiButtonVariant.ICON to icon
    )

    private fun MutableMap<Pair<UiButtonVariant, UiWidgetState>, UiButtonStyle>.addVariant(
        variant: UiButtonVariant,
        recipe: ButtonRecipe,
        colors: UiColorPalette
    ) {
        put(variant to UiWidgetState.NORMAL, style(recipe, recipe.normal, colors.border, recipe.text, recipe.opacity))
        put(variant to UiWidgetState.HOVER, style(recipe, recipe.hover, colors.borderBright, recipe.text, if (recipe.border) 1f else 0.82f))
        put(variant to UiWidgetState.FOCUS, style(recipe, recipe.hover, colors.accentCaution, recipe.text, if (recipe.border) 1f else 0.9f))
        put(variant to UiWidgetState.PRESSED, style(recipe, recipe.pressed, colors.borderBright, recipe.text, if (recipe.border) 1f else 0.94f))
        put(variant to UiWidgetState.DISABLED, style(recipe, colors.panelAlt, colors.border, colors.textDim, if (recipe.border) 0.68f else 0.42f, false))
        put(variant to UiWidgetState.SELECTED, style(recipe, recipe.selected, colors.textPrimary, recipe.selectedText ?: recipe.text, 1f))
    }

    private fun style(
        recipe: ButtonRecipe,
        background: Int,
        borderColor: Int,
        text: Int,
        opacity: Float,
        gradient: Boolean = recipe.gradient
    ): UiButtonStyle = UiButtonStyle(
        surface = UiSurfaceStyle(
            shape = recipe.shape,
            fill = if (gradient) UiFill.VerticalGradient(background, shade(background, 0.72f)) else UiFill.Solid(background),
            border = if (recipe.border) UiBorder.Solid(borderColor, recipe.borderWidth) else UiBorder.None,
            backgroundOpacity = opacity
        ),
        text = text,
        supportingText = text
    )

    private fun surface(
        shape: UiShape,
        top: Int,
        bottom: Int,
        borderColor: Int,
        borderWidth: Int,
        gradient: Boolean,
        opacity: Float = 1f
    ): UiSurfaceStyle = UiSurfaceStyle(
        shape = shape,
        fill = if (gradient) UiFill.VerticalGradient(top, bottom) else UiFill.Solid(top),
        border = if (borderWidth > 0) UiBorder.Solid(borderColor, borderWidth) else UiBorder.None,
        backgroundOpacity = opacity
    )

    private fun button(
        shape: UiShape,
        normal: Int,
        hover: Int,
        pressed: Int,
        selected: Int,
        text: Int,
        gradient: Boolean = false,
        border: Boolean = true,
        opacity: Float = 1f,
        borderWidth: Int = 1,
        selectedText: Int? = null
    ): ButtonRecipe = ButtonRecipe(
        shape,
        normal,
        hover,
        pressed,
        selected,
        text,
        selectedText,
        gradient,
        border,
        opacity,
        borderWidth
    )

    private fun chamfer(cut: Int, vararg corners: UiCorner): UiShape.Chamfer =
        UiShape.Chamfer(cut, if (corners.isEmpty()) UiCorner.entries.toSet() else corners.toSet())

    private fun shade(color: Int, factor: Float): Int {
        val alpha = color ushr 24 and 0xFF
        val red = ((color ushr 16 and 0xFF) * factor).toInt().coerceIn(0, 255)
        val green = ((color ushr 8 and 0xFF) * factor).toInt().coerceIn(0, 255)
        val blue = ((color and 0xFF) * factor).toInt().coerceIn(0, 255)
        return alpha shl 24 or (red shl 16) or (green shl 8) or blue
    }

    private data class ButtonRecipe(
        val shape: UiShape,
        val normal: Int,
        val hover: Int,
        val pressed: Int,
        val selected: Int,
        val text: Int,
        val selectedText: Int?,
        val gradient: Boolean,
        val border: Boolean,
        val opacity: Float,
        val borderWidth: Int
    )
}
