package jbro.cobblemon.morebattlecontent.client

import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpRoomClientView
import jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpRoomIntent
import jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpSelectionClosedPayload
import jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpLoungeSpectatorStatePayload
import jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpSelectionIntentPayload
import jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpSelectionRejectedPayload
import jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpSelectionStatePayload
import jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpRoomIntentPayload
import jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpRoomInvitePayload
import jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpRoomListStatePayload
import jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpRoomRejectedPayload
import jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpRoomStatePayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component

internal object PvpPlayClientNetworking {
    fun register() {
        ClientPlayNetworking.registerGlobalReceiver(PvpLoungeSpectatorStatePayload.TYPE) { payload, context ->
            context.client().execute { PvpLoungeSpectatorControls.setActive(payload.active) }
        }
        ClientPlayNetworking.registerGlobalReceiver(PvpRoomInvitePayload.TYPE) { payload, context ->
            context.client().execute {
                val joinMarker = PvpInviteChatActionMarker.encode(PvpInviteChatAction.JOIN, payload.roomId)
                val declineMarker = PvpInviteChatActionMarker.encode(PvpInviteChatAction.DECLINE, payload.roomId)
                val message = Component.translatable(
                    "screen.cobblemon_more_battle_content.pvp.room.invited",
                    payload.hostName,
                ).append(" ").append(
                    Component.translatable("screen.cobblemon_more_battle_content.pvp.room.invite.join")
                        .withStyle { style ->
                            style.withColor(ChatFormatting.GREEN)
                                .withUnderlined(true)
                                .withInsertion(joinMarker)
                        },
                ).append(" ").append(
                    Component.translatable("screen.cobblemon_more_battle_content.pvp.room.invite.decline")
                        .withStyle { style ->
                            style.withColor(ChatFormatting.RED)
                                .withUnderlined(true)
                                .withInsertion(declineMarker)
                        },
                )
                context.client().player?.displayClientMessage(message, false)
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(PvpRoomListStatePayload.TYPE) { payload, context ->
            context.client().execute {
                val previous = context.client().screen
                if (previous is PvpRoomScreen || PvpRoomClientState.lastRoom?.roomId?.let { roomId ->
                        payload.rooms.none { it.roomId == roomId }
                    } == true
                ) {
                    PvpRoomClientState.lastRoom = null
                }
                PvpRoomClientState.lastRooms = payload.rooms
                context.client().setScreen(PvpRoomListScreen(payload.rooms))
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(PvpRoomStatePayload.TYPE) { payload, context ->
            context.client().execute {
                PvpRoomClientState.lastRoom = payload.room
                when (val current = context.client().screen) {
                    is PvpRoomScreen -> current.applyState(payload.room)
                    is PvpRoomListScreen -> if (PvpRoomNavigationContract.shouldOpen(payload.requestId, PvpRoomClientState.pendingOpenRequests, payload.reopen)) {
                        context.client().setScreen(PvpRoomScreen(payload.room, current))
                    }
                    else -> if (PvpRoomNavigationContract.shouldOpen(payload.requestId, PvpRoomClientState.pendingOpenRequests, payload.reopen)) {
                        context.client().setScreen(PvpRoomScreen(payload.room, PvpRoomListScreen(PvpRoomClientState.lastRooms)))
                    }
                }
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(PvpRoomRejectedPayload.TYPE) { payload, context ->
            context.client().execute {
                PvpRoomClientState.pendingOpenRequests.remove(payload.requestId)
                when (val current = context.client().screen) {
                    is PvpRoomListScreen -> current.applyRejected(payload.messageKey)
                    is PvpRoomScreen -> current.applyRejected(payload.messageKey)
                    else -> context.client().player?.displayClientMessage(Component.translatable(payload.messageKey), false)
                }
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(PvpSelectionStatePayload.TYPE) { payload, context ->
            context.client().execute {
                val current = context.client().screen
                if (payload.requestId == null) {
                    context.client().setScreen(PvpSelectionScreen(payload.state))
                } else if (current is PvpSelectionScreen) {
                    current.applyAccepted(payload.requestId, payload.state)
                }
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(PvpSelectionRejectedPayload.TYPE) { payload, context ->
            context.client().execute {
                (context.client().screen as? PvpSelectionScreen)?.applyRejected(
                    payload.requestId,
                    payload.matchId,
                    payload.messageKey,
                )
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(PvpSelectionClosedPayload.TYPE) { payload, context ->
            context.client().execute {
                val current = context.client().screen as? PvpSelectionScreen
                if (current?.matchId == payload.matchId) context.client().setScreen(null)
                context.client().player?.displayClientMessage(Component.translatable(payload.messageKey), false)
            }
        }
    }

    fun send(payload: PvpSelectionIntentPayload) = ClientPlayNetworking.send(payload)

    fun send(payload: PvpRoomIntentPayload) {
        if (payload.intent is PvpRoomIntent.Create || payload.intent is PvpRoomIntent.Join) {
            PvpRoomClientState.pendingOpenRequests += payload.intent.requestId
        }
        ClientPlayNetworking.send(payload)
    }

    fun openRoom(parent: PvpRoomListScreen, roomId: UUID) {
        val cached = PvpRoomClientState.lastRoom
        if (cached?.roomId == roomId) {
            net.minecraft.client.Minecraft.getInstance().setScreen(PvpRoomScreen(cached, parent))
        } else {
            send(PvpRoomIntentPayload(PvpRoomIntent.Join(UUID.randomUUID(), roomId)))
        }
    }
}

internal object PvpRoomClientState {
    var lastRooms = emptyList<jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpRoomSummaryView>()
    var lastRoom: PvpRoomClientView? = null
    val pendingOpenRequests = HashSet<UUID>()
}
