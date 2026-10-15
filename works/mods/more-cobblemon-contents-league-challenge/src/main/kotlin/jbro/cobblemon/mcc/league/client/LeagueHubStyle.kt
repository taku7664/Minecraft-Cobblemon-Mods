package jbro.cobblemon.mcc.league.client

import jbro.cobblemon.mcc.client.hub.MccHubKit
import jbro.cobblemon.uikit.*
import jbro.cobblemon.uikit.client.CobblemonUiPanel
import jbro.cobblemon.uikit.client.CobblemonUiThemedButton
import jbro.cobblemon.uikit.client.UiSurfaceRenderer
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component

/** The reference's square Tower cards, scoped to League content; never installs a global/rail theme. */
internal object LeagueHubStyle {
    val theme: UiThemeSnapshot = CobblemonUiThemeComposer.compose(UiThemeStyle.PIXEL_FRAME, UiPalettePreset.TOWER_LOBBY)

    fun body(r: UiRect) = UiRect(r.x + 5, r.y + 19, r.width - 10, (r.height - 21).coerceAtLeast(1))

    fun card(g: GuiGraphics, r: UiRect, title: Component, featured: Boolean = false) {
        UiSurfaceRenderer.draw(g, r.x, r.y, r.width, r.height, theme.surfaces.panelAlt)
        val inset = CobblemonUiPanel.frameInset(theme.surfaces.panelAlt)
        val band = if (featured) theme.colors.accentCaution else theme.colors.borderBright
        g.fill(r.x + inset, r.y + inset, r.right - inset, r.y + inset + 15, band)
        g.drawString(Minecraft.getInstance().font, MccHubKit.fitted(title, r.width - 12),
            r.x + 6, r.y + inset + 3, CobblemonUiPanel.onColor(theme, band), false)
    }
}

internal class LeagueHubButton(r: UiRect, private val action: MccHubKit.Action, private val selected: Boolean = false) :
    AbstractButton(r.x, r.y, r.width, r.height, action.label) {
    private var pressedUntil = 0L
    init {
        active = action.enabled
        action.tooltip?.let { tooltip = Tooltip.create(it) }
    }
    override fun onPress() {
        if (!active) return
        pressedUntil = Util.getMillis() + 150
        action.press()
    }
    override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)
    override fun renderWidget(g: GuiGraphics, mx: Int, my: Int, delta: Float) {
        val state = when {
            !active -> UiWidgetState.DISABLED
            Util.getMillis() < pressedUntil -> UiWidgetState.PRESSED
            selected -> UiWidgetState.SELECTED
            isFocused -> UiWidgetState.FOCUS
            isHovered -> UiWidgetState.HOVER
            else -> UiWidgetState.NORMAL
        }
        CobblemonUiThemedButton.draw(g, LeagueHubStyle.theme, UiRect(x, y, width, height), message, action.variant, state)
    }
}
