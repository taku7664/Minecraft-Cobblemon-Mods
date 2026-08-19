package jbro.cobblemon.morebattlecontent.internal.tower.rules

import jbro.cobblemon.morebattlecontent.api.rules.MajorBattleMechanic
import java.util.UUID

internal class TowerBattleRuleRegistrationWindow(
    private val registry: TowerBattleRuleRegistry,
) {
    private val pending = ThreadLocal<Pending?>()

    fun begin(contentId: String, mechanic: MajorBattleMechanic?, actorIds: Set<UUID>) {
        check(pending.get() == null) { "A Battle Tower rule registration is already pending on this thread" }
        require(actorIds.isNotEmpty()) { "actorIds must not be empty" }
        pending.set(Pending(contentId, mechanic, null, actorIds.toSet()))
    }

    fun beginMultiple(contentId: String, mechanics: Set<TowerSubmittedMechanic>, actorIds: Set<UUID>) {
        check(pending.get() == null) { "A regulated battle rule registration is already pending on this thread" }
        require(actorIds.isNotEmpty()) { "actorIds must not be empty" }
        pending.set(Pending(contentId, null, mechanics.toSet(), actorIds.toSet()))
    }

    fun attachIfPending(battleId: UUID, actorIds: Set<UUID>): Boolean {
        val current = pending.get() ?: return false
        if (current.actorIds != actorIds || current.attachedBattleId != null) return false
        val registered = current.mechanics?.let {
            registry.registerMultiple(battleId, it, actorIds, current.contentId)
        } ?: registry.register(battleId, current.mechanic, actorIds, current.contentId)
        if (!registered) return false
        current.attachedBattleId = battleId
        return true
    }

    fun finish(successfulBattleId: UUID?): Boolean {
        val current = pending.get() ?: return false
        pending.remove()
        val attached = current.attachedBattleId
        if (attached != null && attached == successfulBattleId) return true
        if (attached != null) registry.unregister(attached)
        return false
    }

    private class Pending(
        val contentId: String,
        val mechanic: MajorBattleMechanic?,
        val mechanics: Set<TowerSubmittedMechanic>?,
        val actorIds: Set<UUID>,
        var attachedBattleId: UUID? = null,
    )
}
