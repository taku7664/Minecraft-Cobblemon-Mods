package jbro.cobblemon.mcc.client

import com.mojang.blaze3d.platform.InputConstants
import java.util.WeakHashMap
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.pvp.PvpRoomPhase
import jbro.cobblemon.mcc.internal.pvp.network.PvpRoomClientView
import jbro.cobblemon.uikit.UiRect
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

internal object PvpRoomHudOverlay {
    private const val BACKGROUND_ALPHA_50_PERCENT = 0x80
    private const val KEY_PREFIX = "key.${MoreCobblemonContents.MOD_ID}.pvp.room_hud"
    private const val CATEGORY = "key.categories.${MoreCobblemonContents.MOD_ID}"
    private var expanded = true
    private val installedButtons = WeakHashMap<Screen, List<MccStyledButton>>()
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
        MccGuiSurface.drawShell(graphics, layout.panel, BACKGROUND_ALPHA_50_PERCENT)
        MccGuiSurface.drawButton(
            graphics,
            layout.openButton,
            true,
            false,
            false,
            MccGuiPalette.ACCENT_PRIMARY,
            BACKGROUND_ALPHA_50_PERCENT,
        )
        MccGuiSurface.drawButton(
            graphics,
            layout.toggleButton,
            true,
            false,
            false,
            MccGuiPalette.ACCENT_SECONDARY,
            BACKGROUND_ALPHA_50_PERCENT,
        )
        val title = client.font.plainSubstrByWidth(
            Component.translatable(key("hud.title")).string,
            (layout.title.width - 2).coerceAtLeast(1),
        )
        graphics.drawString(client.font, title, layout.title.x, layout.title.y + 5, MccGuiPalette.ACCENT_PRIMARY, false)
        if (!interactive) {
            graphics.drawCenteredString(client.font, openLabel(), layout.openButton.x + layout.openButton.width / 2, layout.openButton.y + 5, MccGuiPalette.TEXT_PRIMARY)
            graphics.drawCenteredString(client.font, toggleButtonLabel(layout), layout.toggleButton.x + layout.toggleButton.width / 2, layout.toggleButton.y + 5, MccGuiPalette.TEXT_PRIMARY)
        }
        if (layout.phaseRow == null) return

        val phase = Component.translatable(key("phase.${room.phase.name.lowercase()}"))
        graphics.drawCenteredString(client.font, phase, layout.phaseRow.x + layout.phaseRow.width / 2, layout.phaseRow.y + 1, phaseColor(room.phase))
        drawSide(graphics, client, layout.leftSide, Component.translatable(key("side.left_short")), room.leftPlayer?.name)
        drawSide(graphics, client, layout.rightSide, Component.translatable(key("side.right_short")), room.rightPlayer?.name)
        layout.spectatorHeading?.let { heading ->
            graphics.drawString(client.font, Component.translatable(key("hud.spectators"), room.spectators.size), heading.x, heading.y + 1, MccGuiPalette.TEXT_SECONDARY, false)
        }
        layout.spectatorRows.forEachIndexed { index, row ->
            val name = client.font.plainSubstrByWidth(room.spectators[index].name, (row.width - 9).coerceAtLeast(1))
            graphics.drawString(client.font, "• $name", row.x + 2, row.y + 1, MccGuiPalette.TEXT_PRIMARY, false)
        }
        if (layout.hiddenSpectatorCount > 0) {
            val lastBottom = layout.spectatorRows.lastOrNull()?.bottom ?: layout.spectatorHeading?.bottom ?: layout.panel.bottom
            graphics.drawString(
                client.font,
                Component.translatable(key("hud.more_spectators"), layout.hiddenSpectatorCount),
                layout.panel.x + 7,
                lastBottom + 1,
                MccGuiPalette.TEXT_DIM,
                false,
            )
        }
    }

    private fun drawSide(graphics: GuiGraphics, client: Minecraft, bounds: UiRect, label: Component, name: String?) {
        MccGuiSurface.drawPanel(
            graphics,
            bounds,
            MccGuiPalette.BORDER_BRIGHT,
            alternate = true,
            backgroundAlpha = BACKGROUND_ALPHA_50_PERCENT,
        )
        graphics.drawString(client.font, label, bounds.x + 4, bounds.y + 3, MccGuiPalette.TEXT_DIM, false)
        val display = name ?: Component.translatable(key("hud.empty")).string
        val clipped = client.font.plainSubstrByWidth(display, (bounds.width - 8).coerceAtLeast(1))
        graphics.drawString(client.font, clipped, bounds.x + 4, bounds.y + 12, MccGuiPalette.TEXT_PRIMARY, false)
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
        val open = MccStyledButton(layout.openButton, openLabel(), MccButtonTone.PRIMARY) {
            openRoom(Minecraft.getInstance())
        }.withBackgroundAlpha(BACKGROUND_ALPHA_50_PERCENT)
        val toggle = MccStyledButton(layout.toggleButton, toggleButtonLabel(layout), MccButtonTone.SECONDARY) {
            expanded = !expanded
            installChatControls(screen)
        }.withBackgroundAlpha(BACKGROUND_ALPHA_50_PERCENT)
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

    private fun phaseColor(phase: PvpRoomPhase): Int = when (phase) {
        PvpRoomPhase.LOBBY -> MccGuiPalette.ACCENT_GOOD
        PvpRoomPhase.TEAM_PREVIEW -> MccGuiPalette.ACCENT_BP
        PvpRoomPhase.ACTIVE -> MccGuiPalette.ACCENT_DANGER
        PvpRoomPhase.CLOSED -> MccGuiPalette.TEXT_DIM
    }

    private fun key(path: String) = "screen.${MoreCobblemonContents.MOD_ID}.pvp.room.$path"
}
