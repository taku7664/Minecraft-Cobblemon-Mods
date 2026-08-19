package jbro.cobblemon.morebattlecontent.internal.ai

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import jbro.cobblemon.morebattlecontent.api.ai.BattleTendencyView

/** Volatile run memory. The owning content session MUST discard its scope when that session closes. */
internal object BattleTacticalRunMemoryStore {
    private val tendenciesByScope = ConcurrentHashMap<UUID, List<BattleTendencyView>>()

    fun snapshot(scopeId: UUID?): List<BattleTendencyView> =
        scopeId?.let(tendenciesByScope::get)?.map { it.copy() }.orEmpty()

    fun record(scopeId: UUID?, tendencies: List<BattleTendencyView>) {
        if (scopeId == null || tendencies.isEmpty()) return
        tendenciesByScope[scopeId] = tendencies.map { it.copy() }
    }

    fun discard(scopeId: UUID?): Boolean = scopeId != null && tendenciesByScope.remove(scopeId) != null
}
