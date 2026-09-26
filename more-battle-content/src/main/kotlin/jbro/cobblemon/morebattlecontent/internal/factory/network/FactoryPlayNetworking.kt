package jbro.cobblemon.morebattlecontent.internal.factory.network

import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.command.FactoryCommandBackend
import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.reportManagedCleanupFailureSafely
import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.runManagedCleanupActionsSafely
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
            val result = try {
                handle(player, payload.intent)
            } catch (exception: RuntimeException) {
                rejectFailedMutation(player, payload.intent.requestId, exception)
                return@registerGlobalReceiver
            } catch (error: LinkageError) {
                rejectFailedMutation(player, payload.intent.requestId, error)
                return@registerGlobalReceiver
            }
            try {
                respond(player, payload.intent.requestId, result)
            } catch (failure: RuntimeException) {
                reportMutationResponseFailure(player, failure)
            } catch (failure: LinkageError) {
                reportMutationResponseFailure(player, failure)
            }
        }
    }

    fun open(player: ServerPlayer): Boolean {
        if (!jbro.cobblemon.morebattlecontent.api.access.BattleContentAccess.allow(
                player, jbro.cobblemon.morebattlecontent.api.presentation.ManagedBattleContentIds.BATTLE_FACTORY,
                jbro.cobblemon.morebattlecontent.api.access.ContentAccessAction.OPEN,
            )) return false
        if (!ServerPlayNetworking.canSend(player, FactoryPlayStatePayload.TYPE)) return false
        return try {
            val result = backend.status(player)
            if (result !is FactoryPlayResult.Accepted) return false
            BattleHubNetworking.sendHeader(player)
            ServerPlayNetworking.send(player, FactoryPlayStatePayload(null, result.view))
            true
        } catch (failure: RuntimeException) {
            reportOpenFailure(player, failure)
            false
        } catch (failure: LinkageError) {
            reportOpenFailure(player, failure)
            false
        }
    }

    fun push(player: ServerPlayer) {
        if (!ServerPlayNetworking.canSend(player, FactoryPlayStatePayload.TYPE)) return
        try {
            val result = backend.status(player)
            if (result is FactoryPlayResult.Accepted) {
                BattleHubNetworking.sendHeader(player)
                ServerPlayNetworking.send(player, FactoryPlayStatePayload(null, result.view))
            }
        } catch (failure: RuntimeException) {
            reportPushFailure(player, failure)
        } catch (failure: LinkageError) {
            reportPushFailure(player, failure)
        }
    }

    private fun rejectFailedMutation(player: ServerPlayer, requestId: java.util.UUID, failure: Throwable) {
        runManagedCleanupActionsSafely(
            reportFailure = {},
            {
                MoreBattleContent.LOGGER.error("Battle Factory screen mutation failed for ${player.uuid}", failure)
            },
            {
                ServerPlayNetworking.send(
                    player,
                    FactoryPlayRejectedPayload(requestId, FactoryPlayError.BATTLE_UNAVAILABLE),
                )
            },
        )
    }

    private fun reportOpenFailure(player: ServerPlayer, failure: Throwable) {
        reportManagedCleanupFailureSafely(failure) {
            MoreBattleContent.LOGGER.error("Battle Factory screen could not be opened for ${player.uuid}", it)
        }
    }

    private fun reportPushFailure(player: ServerPlayer, failure: Throwable) {
        reportManagedCleanupFailureSafely(failure) {
            MoreBattleContent.LOGGER.error("Battle Factory screen update failed for ${player.uuid}", it)
        }
    }

    private fun reportMutationResponseFailure(player: ServerPlayer, failure: Throwable) {
        reportManagedCleanupFailureSafely(failure) {
            MoreBattleContent.LOGGER.error("Battle Factory screen response failed for ${player.uuid}", it)
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
