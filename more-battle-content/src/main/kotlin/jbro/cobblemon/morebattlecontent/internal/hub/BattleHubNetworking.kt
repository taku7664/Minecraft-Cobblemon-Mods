package jbro.cobblemon.morebattlecontent.internal.hub

import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.bp.BattlePointService
import jbro.cobblemon.morebattlecontent.internal.bp.shop.ShopPlayNetworking
import jbro.cobblemon.morebattlecontent.internal.compat.fabric.FactoryCommandRuntime
import jbro.cobblemon.morebattlecontent.internal.command.PvpCommandStatus
import jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpPlayNetworking
import jbro.cobblemon.morebattlecontent.internal.presentation.attemptServerUiOperation
import jbro.cobblemon.morebattlecontent.internal.tower.network.TowerPlayNetworking
import jbro.cobblemon.morebattlecontent.internal.tower.ui.TowerPlayEntryContext
import java.util.UUID
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

internal object BattleHubNetworking {
    private val towerEntryContexts = HashMap<UUID, TowerPlayEntryContext>()

    fun registerServer() {
        PayloadTypeRegistry.playS2C().register(BattleHubAccessPayload.TYPE, BattleHubAccessPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(BattleHubStatePayload.TYPE, BattleHubStatePayload.CODEC)
        PayloadTypeRegistry.playS2C().register(BattleHubHeaderStatePayload.TYPE, BattleHubHeaderStatePayload.CODEC)
        PayloadTypeRegistry.playC2S().register(BattleHubOpenContentPayload.TYPE, BattleHubOpenContentPayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(BattleHubOpenContentPayload.TYPE) { payload, context ->
            val player = context.player()
            val opened = attemptServerUiOperation(
                reportFailure = { failure -> reportFailure(player, "content ${payload.content}", failure) },
            ) {
                when (payload.content) {
                    BattleHubContent.BATTLE_TOWER -> TowerPlayNetworking.open(
                        player,
                        entryContext = towerEntryContexts[player.uuid],
                    ).also { success -> if (success) towerEntryContexts.remove(player.uuid) }
                    BattleHubContent.BATTLE_FACTORY -> FactoryCommandRuntime.open(player)
                    BattleHubContent.PVP -> PvpPlayNetworking.open(player).status == PvpCommandStatus.APPLIED
                    BattleHubContent.BOSS_RAID -> false
                    BattleHubContent.SHOP -> ShopPlayNetworking.open(player)
                }
            }
            if (!opened) {
                attemptServerUiOperation(
                    reportFailure = { failure -> reportFailure(player, "unavailable response", failure) },
                ) {
                    player.sendSystemMessage(
                        Component.translatable("screen.${MoreBattleContent.MOD_ID}.hub.unavailable.${payload.content.name.lowercase()}"),
                    )
                    true
                }
            }
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            towerEntryContexts.remove(handler.player.uuid)
        }
    }

    fun open(player: ServerPlayer, towerEntryContext: TowerPlayEntryContext? = null): Boolean {
        val opened = attemptServerUiOperation(
            reportFailure = { failure -> reportFailure(player, "open", failure) },
        ) {
            if (!ServerPlayNetworking.canSend(player, BattleHubStatePayload.TYPE)) return@attemptServerUiOperation false
            if (towerEntryContext == null) {
                towerEntryContexts.remove(player.uuid)
            } else {
                towerEntryContexts[player.uuid] = towerEntryContext
            }
            sendHeader(player)
            ServerPlayNetworking.send(player, BattleHubStatePayload)
            true
        }
        if (!opened && towerEntryContext != null) {
            towerEntryContexts.remove(player.uuid, towerEntryContext)
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
                val entries = mapOf(
                    BattleHubContent.BATTLE_TOWER to jbro.cobblemon.morebattlecontent.api.presentation.ManagedBattleContentIds.BATTLE_TOWER,
                    BattleHubContent.BATTLE_FACTORY to jbro.cobblemon.morebattlecontent.api.presentation.ManagedBattleContentIds.BATTLE_FACTORY,
                ).mapNotNull { (content, id) ->
                    val decision = jbro.cobblemon.morebattlecontent.api.access.BattleContentAccess.check(player, id,
                        jbro.cobblemon.morebattlecontent.api.access.ContentAccessAction.OPEN)
                    (decision as? jbro.cobblemon.morebattlecontent.api.access.ContentAccessDecision.Denied)?.let { content to it }
                }.toMap()
                ServerPlayNetworking.send(player, BattleHubAccessPayload(entries))
            }
            true
        }
    }

    fun clear() = towerEntryContexts.clear()

    private fun balance(player: ServerPlayer): Long = BattlePointService.balance(player.server, player.uuid)

    private fun reportFailure(player: ServerPlayer, operation: String, failure: Throwable) {
        MoreBattleContent.LOGGER.error("Battle Hub $operation failed for ${player.uuid}", failure)
    }
}
