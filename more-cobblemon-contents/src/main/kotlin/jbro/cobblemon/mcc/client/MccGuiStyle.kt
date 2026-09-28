package jbro.cobblemon.mcc.client

import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.uikit.UiRect
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

object MccGuiPalette {
    val BACKDROP = 0xFF080C16u.toInt()
    val BACKDROP_LINE = 0x182FE4E4u.toInt()
    val SHELL = 0xFF080E1Du.toInt()
    val HEADER = 0xFF0C1528u.toInt()
    val PANEL = 0xFF101A2Du.toInt()
    val PANEL_ALT = 0xFF0C1525u.toInt()
    val BORDER = 0xFF274562u.toInt()
    val BORDER_BRIGHT = 0xFF3F7896u.toInt()
    val BUTTON = 0xFF18263Du.toInt()
    val BUTTON_HOVER = 0xFF263E5Du.toInt()
    val BUTTON_SELECTED = 0xFF164A52u.toInt()
    val BUTTON_DISABLED = 0xFF101724u.toInt()
    val ACCENT_PRIMARY = 0xFF39E4E4u.toInt()
    val ACCENT_SECONDARY = 0xFF9868FFu.toInt()
    val ACCENT_BP = 0xFFFFC84Au.toInt()
    val ACCENT_DANGER = 0xFFFF667Au.toInt()
    val ACCENT_GOOD = 0xFF62E39Bu.toInt()
    val TEXT_PRIMARY = 0xFFEAF7FFu.toInt()
    val TEXT_SECONDARY = 0xFFB9CAD8u.toInt()
    val TEXT_DIM = 0xFF71859Au.toInt()
}

enum class MccButtonTone {
    PRIMARY,
    SECONDARY,
    DANGER,
    NEUTRAL,
}

object MccGuiSurface {
    fun drawBackdrop(graphics: GuiGraphics, width: Int, height: Int) {
        graphics.fill(0, 0, width, height, MccGuiPalette.BACKDROP)
        for (top in 0 until height step 14) {
            graphics.fill(0, top, width, top + 1, MccGuiPalette.BACKDROP_LINE)
        }
    }

    fun drawShell(graphics: GuiGraphics, bounds: UiRect, backgroundAlpha: Int = 0xFF) {
        drawFrame(graphics, bounds, withAlpha(MccGuiPalette.SHELL, backgroundAlpha), MccGuiPalette.ACCENT_PRIMARY)
        graphics.fill(bounds.x + 2, bounds.y + 3, bounds.right - 2, bounds.y + 4, MccGuiPalette.ACCENT_SECONDARY)
    }

    fun drawPanel(
        graphics: GuiGraphics,
        bounds: UiRect,
        accent: Int,
        alternate: Boolean = false,
        backgroundAlpha: Int = 0xFF,
    ) {
        drawFrame(
            graphics,
            bounds,
            withAlpha(if (alternate) MccGuiPalette.PANEL_ALT else MccGuiPalette.PANEL, backgroundAlpha),
            accent,
        )
    }

    fun drawBadge(graphics: GuiGraphics, bounds: UiRect, accent: Int) {
        drawFrame(graphics, bounds, MccGuiPalette.BUTTON, accent)
    }

    fun drawButton(
        graphics: GuiGraphics,
        bounds: UiRect,
        active: Boolean,
        hovered: Boolean,
        selected: Boolean,
        accent: Int,
        backgroundAlpha: Int = 0xFF,
    ) {
        val background = when {
            !active -> MccGuiPalette.BUTTON_DISABLED
            selected -> MccGuiPalette.BUTTON_SELECTED
            hovered -> MccGuiPalette.BUTTON_HOVER
            else -> MccGuiPalette.BUTTON
        }
        val border = when {
            !active -> MccGuiPalette.BORDER
            selected || hovered -> accent
            else -> MccGuiPalette.BORDER_BRIGHT
        }
        drawFrame(graphics, bounds, withAlpha(background, backgroundAlpha), border)
        if (selected) {
            graphics.fill(bounds.x + 1, bounds.y + 1, bounds.x + 3, bounds.bottom - 1, accent)
        }
        if (active && hovered) {
            graphics.fill(bounds.x + 2, bounds.y + 2, bounds.right - 2, bounds.y + 3, accent)
        }
    }

    fun drawProgressSegment(graphics: GuiGraphics, bounds: UiRect, filled: Boolean) {
        drawFrame(
            graphics,
            bounds,
            if (filled) MccGuiPalette.BUTTON_SELECTED else MccGuiPalette.BUTTON_DISABLED,
            if (filled) MccGuiPalette.ACCENT_PRIMARY else MccGuiPalette.BORDER,
        )
    }

    private fun drawFrame(graphics: GuiGraphics, bounds: UiRect, fill: Int, border: Int) {
        // Keep the interior free of an opaque backing layer. Otherwise a translucent fill blends
        // against the border color instead of the game world and only looks like a different solid color.
        if (bounds.width <= 0 || bounds.height <= 0) return
        graphics.fill(bounds.x, bounds.y, bounds.right, bounds.y + 1, border)
        if (bounds.height > 1) {
            graphics.fill(bounds.x, bounds.bottom - 1, bounds.right, bounds.bottom, border)
        }
        if (bounds.height > 2) {
            graphics.fill(bounds.x, bounds.y + 1, bounds.x + 1, bounds.bottom - 1, border)
            if (bounds.width > 1) {
                graphics.fill(bounds.right - 1, bounds.y + 1, bounds.right, bounds.bottom - 1, border)
            }
            if (bounds.width > 2) {
                graphics.fill(bounds.x + 1, bounds.y + 1, bounds.right - 1, bounds.bottom - 1, fill)
            }
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        require(alpha in 0x00..0xFF) { "alpha must be between 0 and 255" }
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }
}

class MccStyledButton(
    bounds: UiRect,
    message: Component,
    private val tone: MccButtonTone = MccButtonTone.NEUTRAL,
    private val selected: Boolean = false,
    private val press: () -> Unit,
) : AbstractButton(bounds.x, bounds.y, bounds.width, bounds.height, message) {
    private var backgroundAlpha = 0xFF

    override fun onPress() = press()

    fun withBackgroundAlpha(alpha: Int): MccStyledButton = apply {
        require(alpha in 0x00..0xFF) { "alpha must be between 0 and 255" }
        backgroundAlpha = alpha
    }

    override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        MccGuiSurface.drawButton(
            graphics,
            UiRect(x, y, width, height),
            active,
            isHoveredOrFocused,
            selected,
            accentColor(),
            backgroundAlpha,
        )
        val font = Minecraft.getInstance().font
        val clipped = font.plainSubstrByWidth(message.string, (width - 8).coerceAtLeast(1))
        val textColor = if (active) {
            if (selected) accentColor() else MccGuiPalette.TEXT_PRIMARY
        } else {
            MccGuiPalette.TEXT_DIM
        }
        graphics.drawCenteredString(
            font,
            Component.literal(clipped),
            x + width / 2,
            y + (height - 8) / 2,
            textColor,
        )
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)

    private fun accentColor(): Int = when (tone) {
        MccButtonTone.PRIMARY -> MccGuiPalette.ACCENT_PRIMARY
        MccButtonTone.SECONDARY -> MccGuiPalette.ACCENT_SECONDARY
        MccButtonTone.DANGER -> MccGuiPalette.ACCENT_DANGER
        MccButtonTone.NEUTRAL -> MccGuiPalette.BORDER_BRIGHT
    }
}
