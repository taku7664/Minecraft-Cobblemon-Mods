package jbro.cobblemon.mcc.client

import com.mojang.blaze3d.platform.InputConstants
import java.util.WeakHashMap
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.client.hub.MccHubKit
import jbro.cobblemon.mcc.client.hub.MccHubTheme
import jbro.cobblemon.mcc.client.hub.MccHubThemedButton
import jbro.cobblemon.mcc.internal.pvp.PvpRoomPhase
import jbro.cobblemon.mcc.internal.pvp.network.PvpRoomClientView
import jbro.cobblemon.uikit.UiBorder
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiFill
import jbro.cobblemon.uikit.UiRect
import jbro.cobblemon.uikit.UiShape
import jbro.cobblemon.uikit.UiSurfaceStyle
import jbro.cobblemon.uikit.UiThemeSnapshot
import jbro.cobblemon.uikit.client.UiSurfaceRenderer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.DeltaTracker
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

/**
 * The PvP room at a glance while the room screen is closed, in the hub's look ([MccHubTheme]): the window, its title
 * bar with the red trim, the hub's buttons and a card for each side. The world shows through the window at half
 * opacity; the bar, the buttons and the cards are more solid so the text stays readable over it.
 */
internal object PvpRoomHudOverlay {
    private const val WINDOW_OPACITY = 0.5f
    private const val BAR_OPACITY = 0.9f
    private const val CONTROL_OPACITY = 0.9f
    private const val CARD_OPACITY = 0.85f
    private const val KEY_PREFIX = "key.${MoreCobblemonContents.MOD_ID}.pvp.room_hud"
    private const val CATEGORY = "key.categories.${MoreCobblemonContents.MOD_ID}"
    private var expanded = true
    private val installedButtons = WeakHashMap<Screen, List<MccHubThemedButton>>()
    private val openKey = KeyBindingHelper.registerKeyBinding(
        KeyMapping("$KEY_PREFIX.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, CATEGORY),
    )
    private val toggleKey = KeyBindingHelper.registerKeyBinding(
        KeyMapping("$KEY_PREFIX.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, CATEGORY),
    )

    fun register() {
        HudRenderCallback.EVENT.register(::render)
        ClientTickEvents.END_CLIENT_TICK.register(::handleKeys)
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ -> installChatControls(screen) }
        MccClientSessionReset.onReset("PvP room HUD") {
            PvpRoomClientState.lastRoom = null
            PvpRoomClientState.lastRooms = emptyList()
            PvpRoomClientState.pendingOpenRequests.clear()
            clearInstalledButtons()
        }
    }

    fun refreshControls() {
        Minecraft.getInstance().screen?.let(::installChatControls)
    }

    private fun render(graphics: GuiGraphics, deltaTracker: DeltaTracker) {
        val client = Minecraft.getInstance()
        val room = PvpRoomClientState.lastRoom ?: return
        val screen = client.screen
        if (screen != null && screen !is ChatScreen) return
        val layout = PvpRoomHudLayout.calculate(graphics.guiWidth(), graphics.guiHeight(), expanded, room.spectators.size)
        drawRoom(graphics, client, room, layout, screen is ChatScreen)
    }

    private fun drawRoom(
        graphics: GuiGraphics,
        client: Minecraft,
        room: PvpRoomClientView,
        layout: PvpRoomHudLayout,
        interactive: Boolean,
    ) {
        val theme = MccHubTheme.snapshot()
        val font = client.font
        val panel = layout.panel
        UiSurfaceRenderer.draw(graphics, panel.x, panel.y, panel.width, panel.height,
            theme.surfaces.shell.copy(backgroundOpacity = WINDOW_OPACITY))
        drawTitleBar(graphics, theme, layout)
        val title = MccHubKit.fitted(Component.translatable(key("hud.title")), (layout.title.width - 2).coerceAtLeast(1))
        graphics.drawString(font, title, layout.title.x, layout.title.y + (layout.title.height - font.lineHeight) / 2 + 1,
            theme.colors.textPrimary, false)
        // Over the chat the buttons are widgets of the chat screen and draw themselves.
        if (!interactive) {
            MccHubThemedButton.draw(graphics, theme, layout.openButton, openLabel(), UiButtonVariant.PRIMARY, opacity = CONTROL_OPACITY)
            MccHubThemedButton.draw(graphics, theme, layout.toggleButton, toggleButtonLabel(layout), UiButtonVariant.SECONDARY,
                opacity = CONTROL_OPACITY)
        }
        val phaseRow = layout.phaseRow ?: return

        val phase = Component.translatable(key("phase.${room.phase.name.lowercase()}"))
        graphics.drawCenteredString(font, phase, phaseRow.x + phaseRow.width / 2, phaseRow.y + 1, phaseColor(theme, room.phase))
        drawSide(graphics, theme, layout.leftSide, Component.translatable(key("side.left_short")), room.leftPlayer?.name, featured = true)
        drawSide(graphics, theme, layout.rightSide, Component.translatable(key("side.right_short")), room.rightPlayer?.name, featured = false)
        layout.spectatorHeading?.let { heading ->
            graphics.drawString(font, Component.translatable(key("hud.spectators"), room.spectators.size), heading.x, heading.y + 1,
                theme.colors.textSecondary, true)
        }
        layout.spectatorRows.forEachIndexed { index, row ->
            val name = font.plainSubstrByWidth(room.spectators[index].name, (row.width - 9).coerceAtLeast(1))
            graphics.drawString(font, "• $name", row.x + 2, row.y + 1, theme.colors.textPrimary, true)
        }
        if (layout.hiddenSpectatorCount > 0) {
            val lastBottom = layout.spectatorRows.lastOrNull()?.bottom ?: layout.spectatorHeading?.bottom ?: layout.panel.bottom
            graphics.drawString(font, Component.translatable(key("hud.more_spectators"), layout.hiddenSpectatorCount),
                layout.panel.x + 7, lastBottom + 1, theme.colors.textSecondary, true)
        }
    }

    /** The hub header's navy bar inside the window's frame, with its red trim under it once the room shows below. */
    private fun drawTitleBar(graphics: GuiGraphics, theme: UiThemeSnapshot, layout: PvpRoomHudLayout) {
        val bar = layout.titleBar
        val color = theme.pixelDecorations?.titleBar ?: theme.colors.panel
        UiSurfaceRenderer.draw(graphics, bar.x, bar.y, bar.width, bar.height,
            UiSurfaceStyle(UiShape.RoundedRectangle(3), UiFill.Solid(color), UiBorder.None, BAR_OPACITY))
        if (layout.phaseRow != null) graphics.fill(bar.x, bar.bottom - 2, bar.right, bar.bottom, theme.colors.accentSecondary)
    }

    /** A side as the hub's side card: its label over a rule (red for the left, grey-blue for the right), the name under it. */
    private fun drawSide(graphics: GuiGraphics, theme: UiThemeSnapshot, bounds: UiRect, label: Component, name: String?, featured: Boolean) {
        val font = Minecraft.getInstance().font
        UiSurfaceRenderer.draw(graphics, bounds.x, bounds.y, bounds.width, bounds.height,
            theme.surfaces.panelAlt.copy(backgroundOpacity = CARD_OPACITY))
        val text = MccHubKit.panelText(theme)
        graphics.drawString(font, MccHubKit.fitted(label, (bounds.width - 10).coerceAtLeast(1)), bounds.x + 5, bounds.y + 4, text, false)
        val rule = if (featured) theme.colors.accentCaution else theme.colors.borderBright
        graphics.fill(bounds.x + 4, bounds.y + 13, bounds.right - 4, bounds.y + 15, rule)
        val display = name ?: Component.translatable(key("hud.empty")).string
        val color = if (name == null) theme.colors.textDim else text
        graphics.drawString(font, font.plainSubstrByWidth(display, (bounds.width - 10).coerceAtLeast(1)), bounds.x + 5, bounds.y + 17, color, false)
    }

    private fun handleKeys(client: Minecraft) {
        while (toggleKey.consumeClick()) {
            if (PvpRoomClientState.lastRoom != null) {
                expanded = !expanded
                refreshControls()
            }
        }
        while (openKey.consumeClick()) openRoom(client)
    }

    private fun installChatControls(screen: Screen) {
        installedButtons.remove(screen)?.let { previous -> Screens.getButtons(screen).removeAll(previous.toSet()) }
        if (screen !is ChatScreen) return
        val room = PvpRoomClientState.lastRoom ?: return
        val layout = PvpRoomHudLayout.calculate(screen.width, screen.height, expanded, room.spectators.size)
        val open = MccHubThemedButton(layout.openButton, openLabel(), UiButtonVariant.PRIMARY, CONTROL_OPACITY) {
            openRoom(Minecraft.getInstance())
        }
        val toggle = MccHubThemedButton(layout.toggleButton, toggleButtonLabel(layout), UiButtonVariant.SECONDARY, CONTROL_OPACITY) {
            expanded = !expanded
            installChatControls(screen)
        }
        Screens.getButtons(screen).add(open)
        Screens.getButtons(screen).add(toggle)
        installedButtons[screen] = listOf(open, toggle)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun openRoom(client: Minecraft) = PvpHubClient.showLastRoom()

    private fun clearInstalledButtons() {
        installedButtons.forEach { (screen, buttons) -> Screens.getButtons(screen).removeAll(buttons.toSet()) }
        installedButtons.clear()
    }

    private fun openLabel(): Component = Component.translatable(key("hud.open_short"), openKey.translatedKeyMessage)

    private fun toggleButtonLabel(layout: PvpRoomHudLayout): Component =
        Component.translatable(key("hud.toggle"), layout.toggleLabel, toggleKey.translatedKeyMessage)

    private fun phaseColor(theme: UiThemeSnapshot, phase: PvpRoomPhase): Int = when (phase) {
        PvpRoomPhase.LOBBY -> theme.colors.accentGood
        PvpRoomPhase.TEAM_PREVIEW -> theme.colors.accentPrimary
        PvpRoomPhase.ACTIVE -> theme.colors.accentDanger
        PvpRoomPhase.CLOSED -> theme.colors.textSecondary
    }

    private fun key(path: String) = "screen.${MoreCobblemonContents.MOD_ID}.pvp.room.$path"
}
