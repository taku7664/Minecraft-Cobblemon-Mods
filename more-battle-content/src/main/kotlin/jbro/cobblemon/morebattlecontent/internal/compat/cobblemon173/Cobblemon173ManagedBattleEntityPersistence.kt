package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.battle.ManagedBattleEntityPersistencePolicy

/** Keeps MBC-managed battle Pokemon out of chunk storage so a killed process cannot resurrect them. */
internal object Cobblemon173ManagedBattleEntityPersistence {
    @JvmStatic
    fun isTransient(entity: PokemonEntity): Boolean = try {
        ManagedBattleEntityPersistencePolicy.isTransient(
            battleId = entity.battleId,
            isManagedBattle = Cobblemon173BattleRuleHooks::isRegisteredBattle,
            isLifecycleOwned = { Cobblemon173ManagedBattleLifecycles.owns(entity.pokemon) },
        )
    } catch (failure: RuntimeException) {
        // Saving must never fail because of MBC; fall back to Cobblemon's own persistence.
        MoreBattleContent.LOGGER.error("Could not classify Pokemon entity {} for managed battle persistence", entity.uuid, failure)
        false
    }
}
