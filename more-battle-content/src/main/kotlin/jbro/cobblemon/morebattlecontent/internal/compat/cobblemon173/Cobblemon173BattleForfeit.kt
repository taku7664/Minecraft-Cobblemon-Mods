package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor
import java.util.UUID

internal object Cobblemon173BattleForfeit {
    fun request(playerId: UUID, battleId: UUID): Boolean {
        val battle = BattleRegistry.getBattle(battleId) ?: return false
        if (battle.ended) return false
        val playerActor = battle.getActor(playerId) as? PlayerBattleActor ?: return false
        battle.writeShowdownAction("${playerActor.showdownId} forfeit")
        return true
    }
}
