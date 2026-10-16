package jbro.cobblemon.mcc.client

import java.util.UUID
import jbro.cobblemon.mcc.client.hub.MccHubScreen
import jbro.cobblemon.uikit.CobblemonUiSharedTheme
import jbro.cobblemon.uikit.UiDialogSpec
import jbro.cobblemon.uikit.UiOverlayTone
import jbro.cobblemon.uikit.client.CobblemonUiDialogScreen
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * An invite asks in a popup in the hub's content look, with the host's name and Join and Decline. It opens only when
 * nothing else is on screen or the hub is: an invite must not cut into a battle, a chest or a line being typed, and the
 * chat line with the same two actions stays for then. Escape closes it without answering, so the chat line still can.
 */
internal object PvpInvitePopup {
    private const val KEY = "screen.more_cobblemon_contents.pvp.room.invite.popup."

    fun show(roomId: UUID, hostName: String) {
        val client = Minecraft.getInstance()
        val parent = client.screen
        if (parent != null && parent !is MccHubScreen) return
        val dialog = CobblemonUiDialogScreen(parent,
            UiDialogSpec(
                Component.translatable(KEY + "title"),
                Component.translatable(KEY + "body", hostName),
                Component.translatable(KEY + "join"),
                Component.translatable(KEY + "decline"),
                UiOverlayTone.NEUTRAL,
            ),
            confirm = { answer(PvpInviteChatAction.JOIN, roomId) },
            cancel = { answer(PvpInviteChatAction.DECLINE, roomId) },
            themeOverride = CobblemonUiSharedTheme.contentSnapshot())
        dialog.dismiss = {}
        client.setScreen(dialog)
    }

    private fun answer(action: PvpInviteChatAction, roomId: UUID) {
        PvpInviteChatClickHandler.handleInsertion(PvpInviteChatActionMarker.encode(action, roomId))
    }
}
