package jbro.cobblemon.mcc.internal.hub

import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.api.access.BattleContentAccess
import jbro.cobblemon.mcc.api.access.ContentAccessAction
import jbro.cobblemon.mcc.api.access.ContentAccessDecision
import jbro.cobblemon.mcc.internal.bp.BattlePointService
import jbro.cobblemon.mcc.internal.presentation.attemptServerUiOperation
import jbro.cobblemon.mcc.internal.terminal.TerminalInteractionResult
import java.util.UUID
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

object BattleHubNetworking {
    private val terminalContexts = HashMap<UUID, TerminalInteractionResult.Verified>()

    fun registerServer() {
        PayloadTypeRegistry.playS2C().register(BattleHubAccessPayload.TYPE, BattleHubAccessPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(BattleHubStatePayload.TYPE, BattleHubStatePayload.CODEC)
        PayloadTypeRegistry.playS2C().register(BattleHubHeaderStatePayload.TYPE, BattleHubHeaderStatePayload.CODEC)
        PayloadTypeRegistry.playC2S().register(BattleHubOpenContentPayload.TYPE, BattleHubOpenContentPayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(BattleHubOpenContentPayload.TYPE) { payload, context ->
            val player = context.player()
            val opened = attemptServerUiOperation(
                reportFailure = { failure -> reportFailure(player, "content ${payload.contentId}", failure) },
            ) {
                BattleHubEntries.get(payload.contentId)?.open?.invoke(player, terminalContexts[player.uuid]) ?: false
            }
            if (!opened) {
                attemptServerUiOperation(
                    reportFailure = { failure -> reportFailure(player, "unavailable response", failure) },
                ) {
                    player.sendSystemMessage(
                        Component.translatable(
                            "screen.${MoreCobblemonContents.MOD_ID}.hub.unavailable.${payload.contentId.substringAfter(':')}",
                        ),
                    )
                    true
                }
            }
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            terminalContexts.remove(handler.player.uuid)
        }
    }

    /** Opens the hub; a verified [terminal] stays attached to the entries opened during this hub session. */
    fun open(player: ServerPlayer, terminal: TerminalInteractionResult.Verified? = null): Boolean {
        val opened = attemptServerUiOperation(
            reportFailure = { failure -> reportFailure(player, "open", failure) },
        ) {
            if (!ServerPlayNetworking.canSend(player, BattleHubStatePayload.TYPE)) return@attemptServerUiOperation false
            if (terminal == null) {
                terminalContexts.remove(player.uuid)
            } else {
                terminalContexts[player.uuid] = terminal
            }
            sendHeader(player)
            ServerPlayNetworking.send(player, BattleHubStatePayload)
            true
        }
        if (!opened && terminal != null) {
            terminalContexts.remove(player.uuid, terminal)
        }
        return opened
    }

    fun sendHeader(player: ServerPlayer): Boolean {
        return attemptServerUiOperation(
            reportFailure = { failure -> reportFailure(player, "header", failure) },
        ) {
            if (!ServerPlayNetworking.canSend(player, BattleHubHeaderStatePayload.TYPE)) return@attemptServerUiOperation false
            ServerPlayNetworking.send(player, BattleHubHeaderStatePayload(balance(player)))
            if (ServerPlayNetworking.canSend(player, BattleHubAccessPayload.TYPE)) {
                val denied = BattleHubEntries.all().mapNotNull { entry ->
                    val accessContentId = entry.accessContentId ?: return@mapNotNull null
                    val decision = BattleContentAccess.check(player, accessContentId, ContentAccessAction.OPEN)
                    (decision as? ContentAccessDecision.Denied)?.let { entry.contentId to it }
                }.toMap()
                ServerPlayNetworking.send(player, BattleHubAccessPayload(denied))
            }
            true
        }
    }

    fun clear() = terminalContexts.clear()

    private fun balance(player: ServerPlayer): Long = BattlePointService.balance(player.server, player.uuid)

    private fun reportFailure(player: ServerPlayer, operation: String, failure: Throwable) {
        MoreCobblemonContents.LOGGER.error("Battle Hub $operation failed for ${player.uuid}", failure)
    }
}
