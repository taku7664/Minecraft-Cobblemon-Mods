package jbro.cobblemon.uikit.client

import jbro.cobblemon.uikit.CobblemonUiThemes
import jbro.cobblemon.uikit.UiBorder
import jbro.cobblemon.uikit.UiButtonSpec
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiControlSize
import jbro.cobblemon.uikit.UiDialogSpec
import jbro.cobblemon.uikit.UiOverlayPlacement
import jbro.cobblemon.uikit.UiOverlayTone
import jbro.cobblemon.uikit.UiRect
import jbro.cobblemon.uikit.UiSize
import jbro.cobblemon.uikit.UiThemeSnapshot
import jbro.cobblemon.uikit.UiToastQueue
import jbro.cobblemon.uikit.UiToastSpec
import jbro.cobblemon.uikit.UiTooltipSpec
import jbro.cobblemon.uikit.UiWidthPolicy
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import kotlin.math.max

object CobblemonUiTooltipRenderer {
    fun render(
        graphics: GuiGraphics,
        spec: UiTooltipSpec,
        anchor: UiRect,
        screenWidth: Int,
        screenHeight: Int,
        maxWidth: Int = 180
    ) {
        val theme = CobblemonUiThemes.registry.snapshot()
        val font = Minecraft.getInstance().font
        val bodyLines = spec.body?.let { font.split(it, maxWidth - 12) }.orEmpty()
        val contentWidth = max(
            font.width(spec.title),
            bodyLines.maxOfOrNull { font.width(it) } ?: 0
        )
        val width = (contentWidth + 12).coerceAtMost(maxWidth)
        val height = 10 + font.lineHeight + if (bodyLines.isEmpty()) 0 else 3 + bodyLines.size * font.lineHeight
        val bounds = UiOverlayPlacement.place(anchor, UiSize(width, height), UiSize(screenWidth, screenHeight))
        UiSurfaceRenderer.draw(
            graphics,
            bounds.x,
            bounds.y,
            bounds.width,
            bounds.height,
            theme.surfaces.panel.copy(border = UiBorder.Solid(toneColor(spec.tone)))
        )
        graphics.drawString(font, spec.title, bounds.x + 6, bounds.y + 5, theme.colors.textPrimary, false)
        bodyLines.forEachIndexed { index, line ->
            graphics.drawString(
                font,
                line,
                bounds.x + 6,
                bounds.y + 8 + font.lineHeight + index * font.lineHeight,
                theme.colors.textSecondary,
                false
            )
        }
    }
}

class CobblemonUiDialogScreen(
    private val parent: Screen?,
    private val spec: UiDialogSpec,
    private val confirm: () -> Unit,
    private val cancel: () -> Unit = {},
    private val themeOverride: UiThemeSnapshot? = null
) : Screen(spec.title) {
    private var panel = UiRect(0, 0, 0, 0)
    private var previousTheme: UiThemeSnapshot? = null

    override fun init() {
        themeOverride?.let { theme ->
            if (previousTheme == null) previousTheme = CobblemonUiThemes.registry.snapshot()
            CobblemonUiThemes.registry.install(theme)
        }
        panel = UiRect((width - 260) / 2, (height - 120) / 2, 260, 120)
        val hasCancel = spec.cancelLabel != null
        val buttonWidth = if (hasCancel) 104 else 120
        val confirmX = if (hasCancel) panel.x + panel.width / 2 + 4 else panel.x + (panel.width - buttonWidth) / 2
        addRenderableWidget(
            CobblemonUiButton.create(
                confirmX,
                panel.bottom - 34,
                buttonWidth,
                UiButtonSpec(
                    spec.confirmLabel,
                    variant = if (spec.tone == UiOverlayTone.DANGER) UiButtonVariant.DANGER else UiButtonVariant.PRIMARY,
                    size = UiControlSize.SMALL,
                    width = UiWidthPolicy.Fixed(buttonWidth)
                )
            ) {
                confirm()
                minecraft?.setScreen(parent)
            }
        )
        spec.cancelLabel?.let { label ->
            addRenderableWidget(
                CobblemonUiButton.create(
                    panel.x + panel.width / 2 - buttonWidth - 4,
                    panel.bottom - 34,
                    buttonWidth,
                    UiButtonSpec(
                        label,
                        variant = UiButtonVariant.SECONDARY,
                        size = UiControlSize.SMALL,
                        width = UiWidthPolicy.Fixed(buttonWidth)
                    )
                ) { closeAsCancel() }
            )
        }
    }

    override fun renderBackground(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) = Unit

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val theme = CobblemonUiThemes.registry.snapshot()
        parent?.render(graphics, -1, -1, partialTick)
        graphics.fill(0, 0, width, height, 0xA0000000.toInt())
        UiSurfaceRenderer.draw(
            graphics,
            panel.x,
            panel.y,
            panel.width,
            panel.height,
            theme.surfaces.shell.copy(border = UiBorder.Solid(toneColor(spec.tone), 2))
        )
        graphics.drawString(font, spec.title, panel.x + 12, panel.y + 11, theme.colors.textPrimary, false)
        font.split(spec.message, panel.width - 24).forEachIndexed { index, line ->
            graphics.drawString(font, line, panel.x + 12, panel.y + 30 + index * font.lineHeight, theme.colors.textSecondary, false)
        }
        super.render(graphics, mouseX, mouseY, partialTick)
    }

    override fun onClose() = closeAsCancel()

    override fun removed() {
        val original = previousTheme
        if (original != null && CobblemonUiThemes.registry.snapshot() === themeOverride) {
            CobblemonUiThemes.registry.install(original)
        }
        previousTheme = null
        super.removed()
    }

    private fun closeAsCancel() {
        cancel()
        minecraft?.setScreen(parent)
    }
}

class CobblemonUiToastLayer {
    val queue = UiToastQueue()

    fun show(spec: UiToastSpec) = queue.push(spec, Util.getMillis())

    fun render(graphics: GuiGraphics, screenWidth: Int, screenHeight: Int) {
        val active = queue.active(Util.getMillis()) ?: return
        val theme = CobblemonUiThemes.registry.snapshot()
        val font = Minecraft.getInstance().font
        val width = (font.width(active.message) + 20).coerceIn(100, 220)
        val height = 24
        val x = screenWidth - width - 8
        val y = screenHeight - height - 8
        UiSurfaceRenderer.draw(
            graphics,
            x,
            y,
            width,
            height,
            theme.surfaces.panel.copy(border = UiBorder.Solid(toneColor(active.spec.tone)))
        )
        graphics.drawString(font, active.message, x + 10, y + (height - font.lineHeight) / 2 + 1, theme.colors.textPrimary, false)
    }
}

private fun toneColor(tone: UiOverlayTone): Int {
    val colors = CobblemonUiThemes.registry.snapshot().colors
    return when (tone) {
        UiOverlayTone.NEUTRAL -> colors.border
        UiOverlayTone.INFO -> colors.accentPrimary
        UiOverlayTone.SUCCESS -> colors.accentGood
        UiOverlayTone.WARNING -> colors.accentCaution
        UiOverlayTone.DANGER -> colors.accentDanger
    }
}
