package jbro.cobblemon.morebattlecontent.internal.hub

import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.bp.BattlePointService
import jbro.cobblemon.morebattlecontent.internal.bp.shop.ShopPlayNetworking
import jbro.cobblemon.morebattlecontent.internal.compat.fabric.FactoryCommandRuntime
import jbro.cobblemon.morebattlecontent.internal.command.PvpCommandStatus
import jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpPlayNetworking
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
        PayloadTypeRegistry.playS2C().register(BattleHubStatePayload.TYPE, BattleHubStatePayload.CODEC)
        PayloadTypeRegistry.playS2C().register(BattleHubHeaderStatePayload.TYPE, BattleHubHeaderStatePayload.CODEC)
        PayloadTypeRegistry.playC2S().register(BattleHubOpenContentPayload.TYPE, BattleHubOpenContentPayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(BattleHubOpenContentPayload.TYPE) { payload, context ->
            val player = context.player()
            val opened = when (payload.content) {
                BattleHubContent.BATTLE_TOWER -> TowerPlayNetworking.open(
                    player,
                    entryContext = towerEntryContexts[player.uuid],
                ).also { success -> if (success) towerEntryContexts.remove(player.uuid) }
                BattleHubContent.BATTLE_FACTORY -> FactoryCommandRuntime.open(player)
                BattleHubContent.PVP -> PvpPlayNetworking.open(player).status == PvpCommandStatus.APPLIED
                BattleHubContent.BOSS_RAID -> false
                BattleHubContent.SHOP -> ShopPlayNetworking.open(player)
            }
            if (!opened) {
                player.sendSystemMessage(
                    Component.translatable("screen.${MoreBattleContent.MOD_ID}.hub.unavailable.${payload.content.name.lowercase()}"),
                )
            }
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            towerEntryContexts.remove(handler.player.uuid)
        }
    }

    fun open(player: ServerPlayer, towerEntryContext: TowerPlayEntryContext? = null): Boolean {
        if (!ServerPlayNetworking.canSend(player, BattleHubStatePayload.TYPE)) return false
        if (towerEntryContext == null) {
            towerEntryContexts.remove(player.uuid)
        } else {
            towerEntryContexts[player.uuid] = towerEntryContext
        }
        sendHeader(player)
        ServerPlayNetworking.send(player, BattleHubStatePayload)
        return true
    }

    fun sendHeader(player: ServerPlayer): Boolean {
        if (!ServerPlayNetworking.canSend(player, BattleHubHeaderStatePayload.TYPE)) return false
        ServerPlayNetworking.send(player, BattleHubHeaderStatePayload(balance(player)))
        return true
    }

    private fun balance(player: ServerPlayer): Long = BattlePointService.balance(player.server, player.uuid)
}
