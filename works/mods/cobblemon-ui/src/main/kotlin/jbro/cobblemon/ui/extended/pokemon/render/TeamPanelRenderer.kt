package jbro.cobblemon.ui.extended.pokemon.render

import com.cobblemon.mod.common.client.render.drawScaledText
import jbro.cobblemon.ui.extended.PanelConfig
import jbro.cobblemon.ui.extended.TeamPanelLayout
import jbro.cobblemon.ui.extended.UIUtils
import jbro.cobblemon.ui.extended.ViewportClamp
import jbro.cobblemon.ui.extended.pokemon.tooltip.TooltipBoundsData
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component

/**
 * Renders team indicator panel backgrounds, corners, and help icons.
 */
object TeamPanelRenderer {

    // Background panel settings
    private const val PANEL_CORNER = 3
    private val PANEL_BG = color(15, 20, 25, 180)

    // Help icon settings
    private const val HELP_ICON_SIZE = 8
    private const val HELP_ICON_MARGIN = 2

    /**
     * Calculate panel dimensions based on team size and current orientation/scale.
     */
    fun calculatePanelDimensions(teamSize: Int, modelSize: Int, modelSpacing: Int): Pair<Int, Int> {
        val isVertical = PanelConfig.teamIndicatorOrientation == PanelConfig.TeamIndicatorOrientation.VERTICAL
        val layout = TeamPanelLayout.calculate(teamSize, modelSize, modelSpacing, isVertical)
        return Pair(layout.panelWidth, layout.panelHeight)
    }

    /**
     * Draw a background panel behind the team's Pokemon models.
     */
    fun drawTeamPanel(
        context: GuiGraphics,
        x: Int,
        y: Int,
        teamSize: Int,
        modelSize: Int,
        modelSpacing: Int,
        applyOpacity: (Int) -> Int
    ) {
        if (teamSize <= 0) return

        val (panelWidth, panelHeight) = calculatePanelDimensions(teamSize, modelSize, modelSpacing)

        val panelX = x - TeamPanelLayout.HORIZONTAL_PADDING
        val panelY = y - TeamPanelLayout.VERTICAL_PADDING

        val bg = applyOpacity(PANEL_BG)

        // Draw main background (cross pattern for rounded corners)
        context.fill(panelX + PANEL_CORNER, panelY, panelX + panelWidth - PANEL_CORNER, panelY + panelHeight, bg)
        context.fill(panelX, panelY + PANEL_CORNER, panelX + panelWidth, panelY + panelHeight - PANEL_CORNER, bg)

        // Fill corners with graduated rounding
        // Top-left
        context.fill(panelX + 2, panelY + 1, panelX + PANEL_CORNER, panelY + 2, bg)
        context.fill(panelX + 1, panelY + 2, panelX + PANEL_CORNER, panelY + PANEL_CORNER, bg)
        // Top-right
        context.fill(panelX + panelWidth - PANEL_CORNER, panelY + 1, panelX + panelWidth - 2, panelY + 2, bg)
        context.fill(panelX + panelWidth - PANEL_CORNER, panelY + 2, panelX + panelWidth - 1, panelY + PANEL_CORNER, bg)
        // Bottom-left
        context.fill(panelX + 2, panelY + panelHeight - 2, panelX + PANEL_CORNER, panelY + panelHeight - 1, bg)
        context.fill(panelX + 1, panelY + panelHeight - PANEL_CORNER, panelX + PANEL_CORNER, panelY + panelHeight - 2, bg)
        // Bottom-right
        context.fill(panelX + panelWidth - PANEL_CORNER, panelY + panelHeight - 2, panelX + panelWidth - 2, panelY + panelHeight - 1, bg)
        context.fill(panelX + panelWidth - PANEL_CORNER, panelY + panelHeight - PANEL_CORNER, panelX + panelWidth - 1, panelY + panelHeight - 2, bg)

    }

    /**
     * Draw a small help icon ("?") in the corner of the panel.
     */
    fun drawHelpIcon(
        context: GuiGraphics,
        panelX: Int,
        panelY: Int,
        panelWidth: Int,
        panelHeight: Int,
        isLeftSide: Boolean,
        applyOpacity: (Int) -> Int
    ): TooltipBoundsData {
        val mc = Minecraft.getInstance()
        val mouseX = (mc.mouseHandler.xpos() * mc.window.guiScaledWidth / mc.window.width).toInt()
        val mouseY = (mc.mouseHandler.ypos() * mc.window.guiScaledHeight / mc.window.height).toInt()

        val iconX = if (isLeftSide) {
            panelX + panelWidth - HELP_ICON_SIZE - HELP_ICON_MARGIN
        } else {
            panelX + HELP_ICON_MARGIN
        }
        val iconY = panelY + panelHeight - HELP_ICON_SIZE - HELP_ICON_MARGIN

        val bounds = TooltipBoundsData(iconX, iconY, HELP_ICON_SIZE, HELP_ICON_SIZE)

        val isHovered = mouseX >= iconX && mouseX <= iconX + HELP_ICON_SIZE &&
            mouseY >= iconY && mouseY <= iconY + HELP_ICON_SIZE

        val bgColor = if (isHovered) color(70, 85, 105, 230) else color(45, 55, 70, 180)
        val textColor = if (isHovered) color(240, 245, 250, 255) else color(140, 155, 175, 220)

        val matrices = context.pose()
        matrices.pushPose()
        matrices.translate(0.0, 0.0, 250.0)

        // Draw circular background
        val bg = applyOpacity(bgColor)
        context.fill(iconX, iconY + 2, iconX + HELP_ICON_SIZE, iconY + 6, bg)
        context.fill(iconX + 1, iconY + 1, iconX + HELP_ICON_SIZE - 1, iconY + 2, bg)
        context.fill(iconX + 1, iconY + 6, iconX + HELP_ICON_SIZE - 1, iconY + 7, bg)
        context.fill(iconX + 2, iconY, iconX + HELP_ICON_SIZE - 2, iconY + 1, bg)
        context.fill(iconX + 2, iconY + 7, iconX + HELP_ICON_SIZE - 2, iconY + 8, bg)

        // Draw "?" text
        val helpText = "?"
        val textRenderer = mc.font
        val textScale = 0.7f
        val textWidth = textRenderer.width(helpText) * textScale
        val textHeight = textRenderer.lineHeight * textScale
        val textXPos = iconX + (HELP_ICON_SIZE / 2.0f) - (textWidth / 2.0f) + 0.5f
        val textYPos = iconY + (HELP_ICON_SIZE / 2.0f) - (textHeight / 2.0f) + 1.0f

        drawScaledText(
            context = context,
            text = Component.literal(helpText),
            x = textXPos,
            y = textYPos,
            colour = applyOpacity(textColor),
            scale = textScale,
            shadow = false
        )

        matrices.popPose()

        return bounds
    }

    /**
     * Render control hints below the panel when hovering the help icon.
     */
    fun renderControlHints(
        context: GuiGraphics,
        panelBounds: TooltipBoundsData,
        isLeftSide: Boolean,
        isCustomized: Boolean,
        repositioningEnabled: Boolean,
        applyOpacity: (Int) -> Int
    ) {
        val mc = Minecraft.getInstance()
        val textRenderer = mc.font
        val screenWidth = mc.window.guiScaledWidth
        val screenHeight = mc.window.guiScaledHeight

        val hints = buildList {
            add(Pair("Shift+Click", ": ${Component.translatable("cobblemon_ui.controls.flip").string}"))
            add(Pair("  \u2022  ", ""))
            if (repositioningEnabled) {
                add(Pair("Drag", ": ${Component.translatable("cobblemon_ui.controls.move").string}"))
                add(Pair("  \u2022  ", ""))
                add(Pair("Dbl-Click", ": ${Component.translatable("cobblemon_ui.controls.reset").string}"))
                add(Pair("  \u2022  ", ""))
            }
            add(Pair("Ctrl+Scroll", ": ${Component.translatable("cobblemon_ui.controls.scale").string}"))
            add(Pair("  \u2022  ", ""))
            add(Pair("Alt", ": ${Component.translatable("cobblemon_ui.controls.both").string}"))
        }

        val hintScale = 0.7f
        val hintText = hints.joinToString("") { it.first + it.second }
        val hintWidth = (textRenderer.width(hintText) * hintScale).toInt() + 8
        val hintHeight = (textRenderer.lineHeight * hintScale).toInt() + 4

        var hintX = panelBounds.x + (panelBounds.width / 2) - (hintWidth / 2)
        var hintY = panelBounds.y + panelBounds.height + 2

        hintX = ViewportClamp.clamp(hintX, 2, screenWidth, hintWidth, 2)
        if (hintY + hintHeight > screenHeight - 2) {
            hintY = panelBounds.y - hintHeight - 2
        }
        hintY = ViewportClamp.clamp(hintY, 2, screenHeight, hintHeight, 2)

        val bgColor = color(15, 20, 25, 200)
        val keyColor = if (isCustomized) color(180, 200, 140, 255) else color(140, 160, 180, 255)
        val textColor = color(100, 110, 120, 255)
        val separatorColor = color(70, 80, 90, 255)

        val matrices = context.pose()
        matrices.pushPose()
        matrices.translate(0.0, 0.0, 400.0)

        context.fill(hintX, hintY, hintX + hintWidth, hintY + hintHeight, applyOpacity(bgColor))

        var textX = (hintX + 4).toFloat()
        val textY = (hintY + 2).toFloat()

        for ((key, action) in hints) {
            val clr = when {
                key.contains("\u2022") -> separatorColor
                action.isEmpty() -> separatorColor
                else -> keyColor
            }
            drawScaledText(
                context = context,
                text = Component.literal(key),
                x = textX,
                y = textY,
                colour = applyOpacity(clr),
                scale = hintScale,
                shadow = false
            )
            textX += textRenderer.width(key) * hintScale

            if (action.isNotEmpty()) {
                drawScaledText(
                    context = context,
                    text = Component.literal(action),
                    x = textX,
                    y = textY,
                    colour = applyOpacity(textColor),
                    scale = hintScale,
                    shadow = false
                )
                textX += textRenderer.width(action) * hintScale
            }
        }

        matrices.popPose()
    }

    // ─── Internal Helpers ───────────────────────────────────────────────────

    private fun color(r: Int, g: Int, b: Int, a: Int = 255): Int = UIUtils.color(r, g, b, a)
}
