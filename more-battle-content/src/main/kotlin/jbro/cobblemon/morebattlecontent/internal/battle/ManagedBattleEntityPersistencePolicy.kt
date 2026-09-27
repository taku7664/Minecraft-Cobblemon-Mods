package jbro.cobblemon.morebattlecontent.internal.battle

import java.util.UUID

/**
 * Decides whether a battle entity must never reach chunk storage.
 *
 * Cobblemon writes its battle-clone marker as `battleClone` but matches lowercase keys on load, so a
 * saved clone comes back as an ordinary wild Pokemon. Shutdown cleanup cannot cover a killed process,
 * and on a singleplayer quit it cannot even find the entity: Cobblemon resolves sent-out entities
 * through `Minecraft.getSingleplayerServer()`, which the client clears before the integrated server
 * ends battles and saves. Battle clones are therefore kept out of every save, as are MBC battle
 * entities while their battle is registered or the lifecycle registry still owns them.
 */
internal object ManagedBattleEntityPersistencePolicy {
    fun isTransient(
        isBattleClone: Boolean,
        battleId: UUID?,
        isManagedBattle: (UUID) -> Boolean,
        isLifecycleOwned: () -> Boolean,
    ): Boolean = isBattleClone || (battleId != null && isManagedBattle(battleId)) || isLifecycleOwned()
}
