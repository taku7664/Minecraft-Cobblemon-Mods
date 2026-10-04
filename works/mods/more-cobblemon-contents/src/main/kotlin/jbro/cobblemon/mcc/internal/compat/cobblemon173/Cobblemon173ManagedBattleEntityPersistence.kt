package jbro.cobblemon.mcc.internal.compat.cobblemon173

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.battle.ManagedBattleEntityPersistencePolicy

/** Keeps battle clones and MCC-managed battle Pokemon out of chunk storage so no quit or kill can resurrect them. */
internal object Cobblemon173ManagedBattleEntityPersistence {
    @JvmStatic
    fun isTransient(entity: PokemonEntity): Boolean = try {
        ManagedBattleEntityPersistencePolicy.isTransient(
            isBattleClone = entity.isBattleClone(),
            battleId = entity.battleId,
            isManagedBattle = Cobblemon173BattleRuleHooks::isRegisteredBattle,
            isLifecycleOwned = { Cobblemon173ManagedBattleLifecycles.owns(entity.pokemon) },
        )
    } catch (failure: RuntimeException) {
        // Saving must never fail because of MCC; fall back to Cobblemon's own persistence.
        MoreCobblemonContents.LOGGER.error("Could not classify Pokemon entity {} for managed battle persistence", entity.uuid, failure)
        false
    }
}
