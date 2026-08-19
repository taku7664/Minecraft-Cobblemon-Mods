package jbro.cobblemon.morebattlecontent.client

import java.util.UUID

internal enum class PvpInviteChatAction(val wireName: String) {
    JOIN("join"),
    DECLINE("decline"),
}

internal data class PvpInviteChatTarget(
    val action: PvpInviteChatAction,
    val roomId: UUID,
)

internal object PvpInviteChatActionMarker {
    private const val PREFIX = "mbc:pvp_invite:"

    fun encode(action: PvpInviteChatAction, roomId: UUID): String = "$PREFIX${action.wireName}:$roomId"

    fun decode(marker: String?): PvpInviteChatTarget? {
        if (marker == null || !marker.startsWith(PREFIX)) return null
        val parts = marker.removePrefix(PREFIX).split(':')
        if (parts.size != 2) return null
        val action = PvpInviteChatAction.entries.singleOrNull { it.wireName == parts[0] } ?: return null
        val roomId = runCatching { UUID.fromString(parts[1]) }.getOrNull() ?: return null
        return PvpInviteChatTarget(action, roomId)
    }
}

internal object PvpInviteChatClickHandler {
    fun handleInsertion(insertion: String?): Boolean {
        val target = PvpInviteChatActionMarker.decode(insertion) ?: return false
        val requestId = UUID.randomUUID()
        val intent = when (target.action) {
            PvpInviteChatAction.JOIN ->
                jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpRoomIntent.Join(requestId, target.roomId)
            PvpInviteChatAction.DECLINE ->
                jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpRoomIntent.DeclineInvite(requestId, target.roomId)
        }
        PvpPlayClientNetworking.send(
            jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpRoomIntentPayload(intent),
        )
        return true
    }
}
