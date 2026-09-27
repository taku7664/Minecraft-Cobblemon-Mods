package jbro.cobblemon.morebattlecontent.internal.battle

import java.util.UUID

/**
 * Decides whether a battle entity must never reach chunk storage.
 *
 * Cobblemon writes its battle-clone marker as `battleClone` but matches lowercase keys on load, so a
 * saved clone comes back as an ordinary wild Pokemon. Shutdown cleanup cannot cover a killed process,
 * so MBC battle entities are kept out of every save instead: while their battle is registered, and
 * during the post-battle recall window while the lifecycle registry still owns them.
 */
internal object ManagedBattleEntityPersistencePolicy {
    fun isTransient(
        battleId: UUID?,
        isManagedBattle: (UUID) -> Boolean,
        isLifecycleOwned: () -> Boolean,
    ): Boolean = (battleId != null && isManagedBattle(battleId)) || isLifecycleOwned()
}
