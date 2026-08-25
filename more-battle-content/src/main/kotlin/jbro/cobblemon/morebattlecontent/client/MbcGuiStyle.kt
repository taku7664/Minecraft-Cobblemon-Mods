package jbro.cobblemon.morebattlecontent.client

import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.hub.BattleHubContent
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

internal object MbcGuiPalette {
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

internal enum class MbcButtonTone {
    PRIMARY,
    SECONDARY,
    DANGER,
    NEUTRAL,
}

internal abstract class MbcScreen(title: Component) : Screen(title) {
    final override fun renderBackground(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) = Unit
}

internal abstract class MbcTabbedContentScreen(
    title: Component,
    private val activeContent: BattleHubContent?,
) : MbcScreen(title) {
    protected fun frameLayout(): MbcContentFrameLayout = MbcContentFrameLayout.calculate(width, height)

    protected fun addContentFrameWidgets(frame: MbcContentFrameLayout = frameLayout()) {
        val tabs = frame.tabButtons(MbcContentTabContract.DISPLAY_ORDER.size)
        MbcContentTabContract.DISPLAY_ORDER.forEachIndexed { index, content ->
            val unavailable = content == BattleHubContent.BOSS_RAID
            addRenderableWidget(
                MbcStyledButton(
                    tabs[index],
                    Component.translatable("screen.${MoreBattleContent.MOD_ID}.hub.tab.${content.name.lowercase()}"),
                    if (unavailable) MbcButtonTone.NEUTRAL else MbcButtonTone.PRIMARY,
                    selected = content == activeContent,
                ) {
                    if (content != activeContent) {
                        MbcContentNavigation.open(content)
                    }
                }.also { it.active = !unavailable },
            )
        }
        addRenderableWidget(
            MbcStyledButton(frame.closeButton, Component.literal("×"), MbcButtonTone.DANGER) { onClose() },
        )
    }

    protected fun drawContentFrame(
        graphics: GuiGraphics,
        frame: MbcContentFrameLayout = frameLayout(),
        headerContentRight: Int = frame.closeButton.left,
    ) {
        MbcGuiSurface.drawBackdrop(graphics, width, height)
        MbcGuiSurface.drawShell(graphics, frame.shell)
        MbcGuiSurface.drawPanel(graphics, frame.header, MbcGuiPalette.ACCENT_SECONDARY, alternate = true)
        MbcGuiSurface.drawPanel(graphics, frame.tabs, MbcGuiPalette.BORDER_BRIGHT, alternate = true)
        val brandRight = headerContentRight - 6
        val brand = font.plainSubstrByWidth("Cobblemon: More Battle Content", (brandRight - frame.header.left - 8).coerceAtLeast(1))
        graphics.drawString(
            font,
            brand,
            frame.header.left + 6,
            frame.header.top + (frame.header.height - 8) / 2,
            MbcGuiPalette.ACCENT_PRIMARY,
            false,
        )
    }
}

internal object MbcGuiSurface {
    fun drawBackdrop(graphics: GuiGraphics, width: Int, height: Int) {
        graphics.fill(0, 0, width, height, MbcGuiPalette.BACKDROP)
        for (top in 0 until height step 14) {
            graphics.fill(0, top, width, top + 1, MbcGuiPalette.BACKDROP_LINE)
        }
    }

    fun drawShell(graphics: GuiGraphics, bounds: TowerPlayRect, backgroundAlpha: Int = 0xFF) {
        drawFrame(graphics, bounds, withAlpha(MbcGuiPalette.SHELL, backgroundAlpha), MbcGuiPalette.ACCENT_PRIMARY)
        graphics.fill(bounds.left + 2, bounds.top + 3, bounds.right - 2, bounds.top + 4, MbcGuiPalette.ACCENT_SECONDARY)
    }

    fun drawPanel(
        graphics: GuiGraphics,
        bounds: TowerPlayRect,
        accent: Int,
        alternate: Boolean = false,
        backgroundAlpha: Int = 0xFF,
    ) {
        drawFrame(
            graphics,
            bounds,
            withAlpha(if (alternate) MbcGuiPalette.PANEL_ALT else MbcGuiPalette.PANEL, backgroundAlpha),
            accent,
        )
    }

    fun drawBadge(graphics: GuiGraphics, bounds: TowerPlayRect, accent: Int) {
        drawFrame(graphics, bounds, MbcGuiPalette.BUTTON, accent)
    }

    fun drawButton(
        graphics: GuiGraphics,
        bounds: TowerPlayRect,
        active: Boolean,
        hovered: Boolean,
        selected: Boolean,
        accent: Int,
        backgroundAlpha: Int = 0xFF,
    ) {
        val background = when {
            !active -> MbcGuiPalette.BUTTON_DISABLED
            selected -> MbcGuiPalette.BUTTON_SELECTED
            hovered -> MbcGuiPalette.BUTTON_HOVER
            else -> MbcGuiPalette.BUTTON
        }
        val border = when {
            !active -> MbcGuiPalette.BORDER
            selected || hovered -> accent
            else -> MbcGuiPalette.BORDER_BRIGHT
        }
        drawFrame(graphics, bounds, withAlpha(background, backgroundAlpha), border)
        if (selected) {
            graphics.fill(bounds.left + 1, bounds.top + 1, bounds.left + 3, bounds.bottom - 1, accent)
        }
        if (active && hovered) {
            graphics.fill(bounds.left + 2, bounds.top + 2, bounds.right - 2, bounds.top + 3, accent)
        }
    }

    fun drawProgressSegment(graphics: GuiGraphics, bounds: TowerPlayRect, filled: Boolean) {
        drawFrame(
            graphics,
            bounds,
            if (filled) MbcGuiPalette.BUTTON_SELECTED else MbcGuiPalette.BUTTON_DISABLED,
            if (filled) MbcGuiPalette.ACCENT_PRIMARY else MbcGuiPalette.BORDER,
        )
    }

    private fun drawFrame(graphics: GuiGraphics, bounds: TowerPlayRect, fill: Int, border: Int) {
        // Keep the interior free of an opaque backing layer. Otherwise a translucent fill blends
        // against the border color instead of the game world and only looks like a different solid color.
        if (bounds.width <= 0 || bounds.height <= 0) return
        graphics.fill(bounds.left, bounds.top, bounds.right, bounds.top + 1, border)
        if (bounds.height > 1) {
            graphics.fill(bounds.left, bounds.bottom - 1, bounds.right, bounds.bottom, border)
        }
        if (bounds.height > 2) {
            graphics.fill(bounds.left, bounds.top + 1, bounds.left + 1, bounds.bottom - 1, border)
            if (bounds.width > 1) {
                graphics.fill(bounds.right - 1, bounds.top + 1, bounds.right, bounds.bottom - 1, border)
            }
            if (bounds.width > 2) {
                graphics.fill(bounds.left + 1, bounds.top + 1, bounds.right - 1, bounds.bottom - 1, fill)
            }
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        require(alpha in 0x00..0xFF) { "alpha must be between 0 and 255" }
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }
}

internal class MbcStyledButton(
    bounds: TowerPlayRect,
    message: Component,
    private val tone: MbcButtonTone = MbcButtonTone.NEUTRAL,
    private val selected: Boolean = false,
    private val press: () -> Unit,
) : AbstractButton(bounds.left, bounds.top, bounds.width, bounds.height, message) {
    private var backgroundAlpha = 0xFF

    override fun onPress() = press()

    fun withBackgroundAlpha(alpha: Int): MbcStyledButton = apply {
        require(alpha in 0x00..0xFF) { "alpha must be between 0 and 255" }
        backgroundAlpha = alpha
    }

    override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        MbcGuiSurface.drawButton(
            graphics,
            TowerPlayRect(x, y, width, height),
            active,
            isHoveredOrFocused,
            selected,
            accentColor(),
            backgroundAlpha,
        )
        val font = Minecraft.getInstance().font
        val clipped = font.plainSubstrByWidth(message.string, (width - 8).coerceAtLeast(1))
        val textColor = if (active) {
            if (selected) accentColor() else MbcGuiPalette.TEXT_PRIMARY
        } else {
            MbcGuiPalette.TEXT_DIM
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
        MbcButtonTone.PRIMARY -> MbcGuiPalette.ACCENT_PRIMARY
        MbcButtonTone.SECONDARY -> MbcGuiPalette.ACCENT_SECONDARY
        MbcButtonTone.DANGER -> MbcGuiPalette.ACCENT_DANGER
        MbcButtonTone.NEUTRAL -> MbcGuiPalette.BORDER_BRIGHT
    }
}

internal class MbcConfirmScreen(
    private val parent: Screen,
    title: Component,
    private val body: Component,
    private val confirm: () -> Unit,
) : MbcScreen(title) {
    override fun init() {
        val panel = panelBounds()
        val buttons = splitButtons(TowerPlayRect(panel.left + 8, panel.bottom - 28, panel.width - 16, 20))
        addRenderableWidget(
            MbcStyledButton(buttons.first, Component.translatable("gui.yes"), MbcButtonTone.DANGER) {
                minecraft?.setScreen(parent)
                confirm()
            },
        )
        addRenderableWidget(
            MbcStyledButton(buttons.second, Component.translatable("gui.no")) {
                minecraft?.setScreen(parent)
            },
        )
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        MbcGuiSurface.drawBackdrop(graphics, width, height)
        val panel = panelBounds()
        MbcGuiSurface.drawShell(graphics, panel)
        graphics.drawCenteredString(font, title, width / 2, panel.top + 12, MbcGuiPalette.ACCENT_DANGER)
        font.split(body, panel.width - 24).take(4).forEachIndexed { index, line ->
            graphics.drawCenteredString(
                font,
                line,
                width / 2,
                panel.top + 34 + index * 10,
                MbcGuiPalette.TEXT_SECONDARY,
            )
        }
        super.render(graphics, mouseX, mouseY, partialTick)
    }

    override fun onClose() {
        minecraft?.setScreen(parent)
    }

    private fun panelBounds(): TowerPlayRect {
        val panelWidth = (width - 24).coerceAtMost(300)
        val panelHeight = 116
        return TowerPlayRect((width - panelWidth) / 2, (height - panelHeight) / 2, panelWidth, panelHeight)
    }

    private fun splitButtons(bounds: TowerPlayRect): Pair<TowerPlayRect, TowerPlayRect> {
        val firstWidth = (bounds.width - 6) / 2
        return TowerPlayRect(bounds.left, bounds.top, firstWidth, bounds.height) to
            TowerPlayRect(bounds.left + firstWidth + 6, bounds.top, bounds.width - firstWidth - 6, bounds.height)
    }
}
