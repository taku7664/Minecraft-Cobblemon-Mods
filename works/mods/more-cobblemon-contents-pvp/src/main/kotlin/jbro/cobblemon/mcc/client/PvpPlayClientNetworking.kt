package jbro.cobblemon.mcc.client

import java.util.UUID
import jbro.cobblemon.mcc.internal.pvp.network.PvpRoomClientView
import jbro.cobblemon.mcc.internal.pvp.network.PvpRoomIntent
import jbro.cobblemon.mcc.internal.pvp.network.PvpSelectionClosedPayload
import jbro.cobblemon.mcc.internal.pvp.network.PvpLoungeSpectatorStatePayload
import jbro.cobblemon.mcc.internal.pvp.network.PvpLoungeExitPayload
import jbro.cobblemon.mcc.internal.pvp.network.PvpLoungeExitResultPayload
import jbro.cobblemon.mcc.internal.pvp.network.PvpSelectionIntentPayload
import jbro.cobblemon.mcc.internal.pvp.network.PvpSelectionRejectedPayload
import jbro.cobblemon.mcc.internal.pvp.network.PvpSelectionStatePayload
import jbro.cobblemon.mcc.internal.pvp.network.PvpRoomIntentPayload
import jbro.cobblemon.mcc.internal.pvp.network.PvpRoomInvitePayload
import jbro.cobblemon.mcc.internal.pvp.network.PvpRoomListStatePayload
import jbro.cobblemon.mcc.internal.pvp.network.PvpRoomRejectedPayload
import jbro.cobblemon.mcc.internal.pvp.network.PvpRoomStatePayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component

internal object PvpPlayClientNetworking {
    private val loungeExitRequest = PendingClientRequest()

    fun register() {
        MccClientSessionReset.onReset("PvP room client state") {
            PvpRoomClientState.clear()
            PvpHubClient.clear()
        }
        ClientPlayNetworking.registerGlobalReceiver(PvpLoungeSpectatorStatePayload.TYPE) { payload, context ->
            context.client().execute {
                val closeForConfirmedExit = !payload.active && loungeExitRequest.complete(accepted = true)
                if (payload.active) {
                    PvpRoomClientState.pendingOpenRequests.clear()
                    loungeExitRequest.reset()
                }
                PvpLoungeSpectatorControls.setActive(payload.active)
                if (closeForConfirmedExit) context.client().setScreen(null)
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(PvpLoungeExitResultPayload.TYPE) { payload, context ->
            context.client().execute {
                val closeScreen = applyPvpLoungeExitResult(
                    request = loungeExitRequest,
                    accepted = payload.accepted,
                    deactivateControls = { PvpLoungeSpectatorControls.setActive(false) },
                    clearExitPending = { PvpLoungeSpectatorControls.setExitPending(false) },
                )
                // The server took this spectator out of the room as well; nothing may reopen it from cache.
                if (payload.accepted) {
                    PvpHubClient.leftRoom()
                    PvpRoomHudOverlay.refreshControls()
                }
                if (closeScreen) {
                    context.client().setScreen(null)
                } else if (!payload.accepted) {
                    payload.messageKey?.let { messageKey ->
                        context.client().player?.displayClientMessage(Component.translatable(messageKey), false)
                    }
                }
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(PvpRoomInvitePayload.TYPE) { payload, context ->
            context.client().execute {
                val joinMarker = PvpInviteChatActionMarker.encode(PvpInviteChatAction.JOIN, payload.roomId)
                val declineMarker = PvpInviteChatActionMarker.encode(PvpInviteChatAction.DECLINE, payload.roomId)
                val message = Component.translatable(
                    "screen.more_cobblemon_contents.pvp.room.invited",
                    payload.hostName,
                ).append(" ").append(
                    Component.translatable("screen.more_cobblemon_contents.pvp.room.invite.join")
                        .withStyle { style ->
                            style.withColor(ChatFormatting.GREEN)
                                .withUnderlined(true)
                                .withInsertion(joinMarker)
                        },
                ).append(" ").append(
                    Component.translatable("screen.more_cobblemon_contents.pvp.room.invite.decline")
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
                PvpRoomClientState.lastRoom =
                    PvpRoomNavigationContract.rememberedAfterRoomList(PvpRoomClientState.lastRoom, payload.memberRoomId)
                PvpHubClient.acceptRooms(payload.rooms, payload.memberRoomId)
                PvpRoomHudOverlay.refreshControls()
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(PvpRoomStatePayload.TYPE) { payload, context ->
            context.client().execute {
                PvpHubClient.acceptRoom(payload.requestId, payload.room, payload.reopen)
                PvpRoomHudOverlay.refreshControls()
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(PvpRoomRejectedPayload.TYPE) { payload, context ->
            context.client().execute { PvpHubClient.rejectRoom(payload.requestId, payload.messageKey, payload.messageArgs) }
        }
        ClientPlayNetworking.registerGlobalReceiver(PvpSelectionStatePayload.TYPE) { payload, context ->
            context.client().execute { PvpHubClient.acceptSelection(payload.requestId, payload.state) }
        }
        ClientPlayNetworking.registerGlobalReceiver(PvpSelectionRejectedPayload.TYPE) { payload, context ->
            context.client().execute { PvpHubClient.rejectSelection(payload.requestId, payload.matchId, payload.messageKey) }
        }
        ClientPlayNetworking.registerGlobalReceiver(PvpSelectionClosedPayload.TYPE) { payload, context ->
            context.client().execute {
                PvpHubClient.closeSelection(payload.matchId)
                context.client().player?.displayClientMessage(Component.translatable(payload.messageKey), false)
            }
        }
    }

    fun send(payload: PvpSelectionIntentPayload) = ClientPlayNetworking.send(payload)

    fun exitLoungeSpectator() {
        try {
            loungeExitRequest.send {
                PvpLoungeSpectatorControls.setExitPending(true)
                ClientPlayNetworking.send(PvpLoungeExitPayload)
            }
        } catch (failure: Throwable) {
            try {
                PvpLoungeSpectatorControls.setExitPending(false)
            } catch (cleanupFailure: Throwable) {
                if (failure !== cleanupFailure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    fun resetLoungeExitRequest() = loungeExitRequest.reset()

    fun send(payload: PvpRoomIntentPayload) {
        if (payload.intent is PvpRoomIntent.Create || payload.intent is PvpRoomIntent.Join) {
            PvpRoomNavigationContract.sendTrackedOpenRequest(
                payload.intent.requestId,
                PvpRoomClientState.pendingOpenRequests,
            ) {
                ClientPlayNetworking.send(payload)
            }
            return
        }
        ClientPlayNetworking.send(payload)
    }

    fun openRoom(roomId: UUID) {
        send(PvpRoomIntentPayload(PvpRoomNavigationContract.openIntent(UUID.randomUUID(), roomId)))
    }
}

internal fun applyPvpLoungeExitResult(
    request: PendingClientRequest,
    accepted: Boolean,
    deactivateControls: () -> Unit,
    clearExitPending: () -> Unit,
): Boolean {
    val closeScreen = request.complete(accepted)
    if (accepted) deactivateControls() else clearExitPending()
    return closeScreen
}

internal object PvpRoomClientState {
    var lastRooms = emptyList<jbro.cobblemon.mcc.internal.pvp.network.PvpRoomSummaryView>()
    var lastRoom: PvpRoomClientView? = null
    val pendingOpenRequests = HashSet<UUID>()

    fun clear() {
        lastRooms = emptyList()
        lastRoom = null
        pendingOpenRequests.clear()
    }
}
