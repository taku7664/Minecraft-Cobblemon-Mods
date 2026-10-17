package jbro.cobblemon.mcc.client

import java.util.UUID
import jbro.cobblemon.mcc.client.hub.MccHubScreen
import jbro.cobblemon.mcc.internal.pvp.PvpRoomSettings
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

    fun show(roomId: UUID, hostName: String, settings: PvpRoomSettings) {
        val client = Minecraft.getInstance()
        val parent = client.screen
        if (parent != null && parent !is MccHubScreen) return
        val dialog = CobblemonUiDialogScreen(parent,
            UiDialogSpec(
                Component.translatable(KEY + "title"),
                Component.translatable(KEY + "body", hostName).append("\n").append(rules(settings)),
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

    /** The room's rules in one line: format, the gimmicks allowed, and the duplicate rules in force. */
    fun rules(settings: PvpRoomSettings): Component {
        val line = Component.translatable(ROOM + "format.${settings.format.recordId}")
        val mechanics = settings.immutableEnabledMechanics
        line.append(" · ").append(if (mechanics.isEmpty()) Component.translatable(KEY + "no_mechanics") else
            mechanics.map { Component.translatable(ROOM + "mechanic.${it.id}") }
                .reduce { joined, next -> joined.copy().append(", ").append(next) })
        val clauses = listOfNotNull(
            Component.translatable(ROOM + "clause.species").takeIf { settings.speciesClause },
            Component.translatable(ROOM + "clause.item").takeIf { settings.itemClause },
        )
        if (clauses.isNotEmpty()) {
            line.append(" · ").append(Component.translatable(ROOM + "group.clauses")).append(" ")
                .append(clauses.reduce { joined, next -> joined.copy().append(", ").append(next) })
        }
        return line
    }

    private const val ROOM = "screen.more_cobblemon_contents.pvp.room."

    private fun answer(action: PvpInviteChatAction, roomId: UUID) {
        PvpInviteChatClickHandler.handleInsertion(PvpInviteChatActionMarker.encode(action, roomId))
    }
}
