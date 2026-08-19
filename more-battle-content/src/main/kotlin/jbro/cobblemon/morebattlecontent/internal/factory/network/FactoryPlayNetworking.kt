package jbro.cobblemon.morebattlecontent.internal.factory.network

import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.command.FactoryCommandBackend
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryPlayError
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryPlayResult
import jbro.cobblemon.morebattlecontent.internal.factory.ui.FactoryPlayIntent
import jbro.cobblemon.morebattlecontent.internal.hub.BattleHubNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.level.ServerPlayer

internal object FactoryPlayNetworking {
    private lateinit var backend: FactoryCommandBackend

    fun registerServer(backend: FactoryCommandBackend) {
        this.backend = backend
        PayloadTypeRegistry.playS2C().register(FactoryPlayStatePayload.TYPE, FactoryPlayStatePayload.CODEC)
        PayloadTypeRegistry.playS2C().register(FactoryPlayRejectedPayload.TYPE, FactoryPlayRejectedPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(FactoryPlayIntentPayload.TYPE, FactoryPlayIntentPayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(FactoryPlayIntentPayload.TYPE) { payload, context ->
            val player = context.player()
            try {
                respond(player, payload.intent.requestId, handle(player, payload.intent))
            } catch (exception: RuntimeException) {
                MoreBattleContent.LOGGER.error("Battle Factory screen mutation failed for ${player.uuid}", exception)
                ServerPlayNetworking.send(
                    player,
                    FactoryPlayRejectedPayload(payload.intent.requestId, FactoryPlayError.BATTLE_UNAVAILABLE),
                )
            }
        }
    }

    fun open(player: ServerPlayer): Boolean {
        if (!ServerPlayNetworking.canSend(player, FactoryPlayStatePayload.TYPE)) return false
        val result = backend.status(player)
        if (result !is FactoryPlayResult.Accepted) return false
        BattleHubNetworking.sendHeader(player)
        ServerPlayNetworking.send(player, FactoryPlayStatePayload(null, result.view))
        return true
    }

    fun push(player: ServerPlayer) {
        if (!ServerPlayNetworking.canSend(player, FactoryPlayStatePayload.TYPE)) return
        val result = backend.status(player)
        if (result is FactoryPlayResult.Accepted) {
            BattleHubNetworking.sendHeader(player)
            ServerPlayNetworking.send(player, FactoryPlayStatePayload(null, result.view))
        }
    }

    private fun handle(player: ServerPlayer, intent: FactoryPlayIntent): FactoryPlayResult = when (intent) {
        is FactoryPlayIntent.Start -> backend.start(player, intent.format, intent.levelMode)
        is FactoryPlayIntent.SelectRentals -> backend.select(player, intent.setIds)
        is FactoryPlayIntent.ReviseSelection -> backend.revise(player)
        is FactoryPlayIntent.BeginBattle -> backend.battle(player, intent.orderedSetIds)
        is FactoryPlayIntent.KeepTeam -> backend.keep(player)
        is FactoryPlayIntent.Swap -> backend.swap(player, intent.outgoingSetId, intent.incomingToken)
        is FactoryPlayIntent.Abandon -> backend.abandon(player)
    }

    private fun respond(player: ServerPlayer, requestId: java.util.UUID, result: FactoryPlayResult) {
        when (result) {
            is FactoryPlayResult.Accepted ->
                ServerPlayNetworking.send(player, FactoryPlayStatePayload(requestId, result.view))
            is FactoryPlayResult.Rejected ->
                ServerPlayNetworking.send(player, FactoryPlayRejectedPayload(requestId, result.error))
        }
    }
}
