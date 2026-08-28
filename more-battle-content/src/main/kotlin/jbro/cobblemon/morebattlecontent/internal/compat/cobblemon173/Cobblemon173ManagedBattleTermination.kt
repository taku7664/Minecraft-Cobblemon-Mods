package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.battles.BattleRegistry
import java.util.UUID

/** Ends an MBC-owned battle while its disconnecting player and temporary party are still resolvable. */
internal object Cobblemon173ManagedBattleTermination {
    fun end(battleId: UUID) {
        val battle = BattleRegistry.getBattle(battleId) ?: return
        if (!battle.ended) battle.end()
    }
}
