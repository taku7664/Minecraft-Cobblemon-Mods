package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.net.serverhandling.battle.SpectateBattleHandler
import java.util.UUID
import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.command.SpectateCommandBackend
import jbro.cobblemon.morebattlecontent.internal.spectate.RemoteSpectateGateway
import jbro.cobblemon.morebattlecontent.internal.spectate.RemoteSpectateResult
import jbro.cobblemon.morebattlecontent.internal.spectate.RemoteSpectateService
import jbro.cobblemon.morebattlecontent.internal.battle.ManagedBattleContentNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

internal object Cobblemon173RemoteSpectate : SpectateCommandBackend {
    override fun spectate(viewer: ServerPlayer, target: ServerPlayer): RemoteSpectateResult = try {
        RemoteSpectateService(CobblemonGateway(viewer.server)).spectate(viewer.uuid, target.uuid)
    } catch (exception: RuntimeException) {
        MoreBattleContent.LOGGER.error(
            "Remote spectating failed for viewer {} and target {}",
            viewer.uuid,
            target.uuid,
            exception,
        )
        RemoteSpectateResult.BATTLE_UNAVAILABLE
    }

    private class CobblemonGateway(
        private val server: MinecraftServer,
    ) : RemoteSpectateGateway {
        override fun isSpectatingEnabled(): Boolean = Cobblemon.config.allowSpectating

        override fun participatingBattleId(playerId: UUID): UUID? =
            BattleRegistry.getBattleByParticipatingPlayerId(playerId)?.battleId

        override fun isManagedBattle(battleId: UUID): Boolean =
            Cobblemon173BattleRuleHooks.isRegisteredBattle(battleId)

        override fun spectatedManagedBattleId(playerId: UUID): UUID? =
            Cobblemon173BattleRuleHooks.registeredBattleIds().firstOrNull { battleId ->
                BattleRegistry.getBattle(battleId)?.spectators?.contains(playerId) == true
            }

        override fun beginSpectating(battleId: UUID, targetId: UUID, viewerId: UUID): Boolean {
            val target = server.playerList.getPlayer(targetId) ?: return false
            val viewer = server.playerList.getPlayer(viewerId) ?: return false
            val battle = BattleRegistry.getBattleByParticipatingPlayerId(targetId) ?: return false
            if (battle.battleId != battleId || !isManagedBattle(battleId)) return false
            if (BattleRegistry.getBattleByParticipatingPlayerId(viewerId) != null) return false

            SpectateBattleHandler.spectateBattle(target, viewer)
            val started = BattleRegistry.getBattle(battleId)?.spectators?.contains(viewerId) == true
            if (started) {
                Cobblemon173BattleRuleHooks.contentId(battleId)?.let { contentId ->
                    ManagedBattleContentNetworking.showTo(viewer, battleId, contentId)
                }
            }
            return started
        }
    }
}
