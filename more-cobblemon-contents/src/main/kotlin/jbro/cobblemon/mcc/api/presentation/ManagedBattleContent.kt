package jbro.cobblemon.mcc.api.presentation

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object ManagedBattleContentIds {
    const val BATTLE_TOWER: String = "more_cobblemon_contents:battle_tower"
    const val BATTLE_FACTORY: String = "more_cobblemon_contents:battle_factory"
    const val PVP: String = "more_cobblemon_contents:pvp"
    const val AI_TEST: String = "more_cobblemon_contents:ai_test"

    private val CONTENT_ID = Regex("[a-z0-9_.-]+:[a-z0-9/._-]+")

    @JvmStatic
    fun isValid(value: String): Boolean = CONTENT_ID.matches(value)
}

class ManagedBattleContentClientState {
    private val contents = ConcurrentHashMap<UUID, String>()

    fun show(battleId: UUID, contentId: String) {
        require(ManagedBattleContentIds.isValid(contentId)) {
            "Managed battle content ID must be a lowercase namespaced ID"
        }
        contents[battleId] = contentId
    }

    fun hide(battleId: UUID) {
        contents.remove(battleId)
    }

    fun clear() {
        contents.clear()
    }

    fun contentId(battleId: UUID): String? = contents[battleId]
}

object ManagedBattleContentClient {
    private val state = ManagedBattleContentClientState()

    @JvmStatic
    fun contentId(battleId: UUID): String? = state.contentId(battleId)

    internal fun show(battleId: UUID, contentId: String) = state.show(battleId, contentId)

    internal fun hide(battleId: UUID) = state.hide(battleId)

    internal fun clear() = state.clear()
}
