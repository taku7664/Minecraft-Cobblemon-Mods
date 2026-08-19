package jbro.cobblemon.morebattlecontent.internal.tower.rules

import jbro.cobblemon.morebattlecontent.api.rules.MajorBattleMechanic
import jbro.cobblemon.morebattlecontent.api.presentation.ManagedBattleContentIds
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class TowerActionSubmission(
    val hasBagItem: Boolean = false,
    val mechanics: List<TowerSubmittedMechanic> = emptyList(),
)

internal enum class TowerSubmittedMechanic {
    MEGA,
    DYNAMAX,
    TERA,
    Z_MOVE,
    UNSUPPORTED,
}

internal enum class TowerRuleRejection(val message: String) {
    ACTOR_NOT_REGISTERED("This actor is not registered for this regulated battle"),
    BAG_ITEMS_DISABLED("Bag items cannot be used in this regulated battle"),
    MULTIPLE_MECHANICS("A side can use at most one major mechanic per battle"),
    WRONG_MECHANIC("This major mechanic is not enabled for the current battle"),
    MECHANIC_ALREADY_USED("This side has already used its major mechanic in this battle"),
}

internal data class TowerActorMechanicState(
    val selected: MajorBattleMechanic?,
    val consumed: Boolean,
)

internal class TowerBattleRuleRegistry {
    private val battles = ConcurrentHashMap<UUID, BattleRules>()

    fun register(
        battleId: UUID,
        mechanic: MajorBattleMechanic?,
        actorIds: Set<UUID>,
        contentId: String = UNSPECIFIED_CONTENT_ID,
    ): Boolean {
        require(actorIds.isNotEmpty()) { "actorIds must not be empty" }
        require(ManagedBattleContentIds.isValid(contentId)) { "contentId must be a lowercase namespaced ID" }
        val expected = when (mechanic) {
            MajorBattleMechanic.MEGA -> TowerSubmittedMechanic.MEGA
            MajorBattleMechanic.DYNAMAX -> TowerSubmittedMechanic.DYNAMAX
            MajorBattleMechanic.TERA -> TowerSubmittedMechanic.TERA
            null -> null
        }
        return battles.putIfAbsent(
            battleId,
            BattleRules(contentId, setOfNotNull(expected), actorIds, selectedForTower = mechanic, allowMultiplePerTurn = false),
        ) == null
    }

    fun registerMultiple(
        battleId: UUID,
        mechanics: Set<TowerSubmittedMechanic>,
        actorIds: Set<UUID>,
        contentId: String = UNSPECIFIED_CONTENT_ID,
    ): Boolean {
        require(actorIds.isNotEmpty()) { "actorIds must not be empty" }
        require(TowerSubmittedMechanic.UNSUPPORTED !in mechanics) { "Unsupported mechanics cannot be enabled" }
        require(ManagedBattleContentIds.isValid(contentId)) { "contentId must be a lowercase namespaced ID" }
        return battles.putIfAbsent(
            battleId,
            BattleRules(contentId, mechanics, actorIds, selectedForTower = null, allowMultiplePerTurn = true),
        ) == null
    }

    fun unregister(battleId: UUID): Boolean = battles.remove(battleId) != null

    fun isRegistered(battleId: UUID): Boolean = battles.containsKey(battleId)

    fun registeredBattleIds(): Set<UUID> = battles.keys.toSet()

    fun allowedMechanics(battleId: UUID): Set<TowerSubmittedMechanic>? =
        battles[battleId]?.snapshotAllowedMechanics()

    fun contentId(battleId: UUID): String? = battles[battleId]?.contentId

    fun rejectionReason(
        battleId: UUID,
        actorId: UUID,
        submission: TowerActionSubmission,
    ): TowerRuleRejection? = battles[battleId]?.rejectionReason(actorId, submission)

    fun recordAccepted(
        battleId: UUID,
        actorId: UUID,
        submission: TowerActionSubmission,
    ): Boolean = battles[battleId]?.recordAccepted(actorId, submission) ?: false

    fun actorMechanicState(battleId: UUID, actorId: UUID): TowerActorMechanicState? =
        battles[battleId]?.actorMechanicState(actorId)

    private class BattleRules(
        val contentId: String,
        private val allowedMechanics: Set<TowerSubmittedMechanic>,
        actorIds: Set<UUID>,
        private val selectedForTower: MajorBattleMechanic?,
        private val allowMultiplePerTurn: Boolean,
    ) {
        private val actors = actorIds.associateWith { LinkedHashSet<TowerSubmittedMechanic>() }.toMutableMap()

        fun snapshotAllowedMechanics(): Set<TowerSubmittedMechanic> = allowedMechanics.toSet()

        @Synchronized
        fun rejectionReason(actorId: UUID, submission: TowerActionSubmission): TowerRuleRejection? {
            val consumed = actors[actorId] ?: return TowerRuleRejection.ACTOR_NOT_REGISTERED
            if (submission.hasBagItem) return TowerRuleRejection.BAG_ITEMS_DISABLED
            if ((!allowMultiplePerTurn && submission.mechanics.size > 1) ||
                submission.mechanics.distinct().size != submission.mechanics.size
            ) {
                return TowerRuleRejection.MULTIPLE_MECHANICS
            }
            if (submission.mechanics.any { it !in allowedMechanics }) return TowerRuleRejection.WRONG_MECHANIC
            if (submission.mechanics.any { it in consumed }) return TowerRuleRejection.MECHANIC_ALREADY_USED
            return null
        }

        @Synchronized
        fun recordAccepted(actorId: UUID, submission: TowerActionSubmission): Boolean {
            if (rejectionReason(actorId, submission) != null) return false
            actors.getValue(actorId).addAll(submission.mechanics)
            return true
        }

        @Synchronized
        fun actorMechanicState(actorId: UUID): TowerActorMechanicState? =
            actors[actorId]?.let { consumed ->
                val expected = when (selectedForTower) {
                    MajorBattleMechanic.MEGA -> TowerSubmittedMechanic.MEGA
                    MajorBattleMechanic.DYNAMAX -> TowerSubmittedMechanic.DYNAMAX
                    MajorBattleMechanic.TERA -> TowerSubmittedMechanic.TERA
                    null -> null
                }
                TowerActorMechanicState(selectedForTower, expected != null && expected in consumed)
            }
    }

    internal companion object {
        private const val UNSPECIFIED_CONTENT_ID = "cobblemon_more_battle_content:managed"
        val global = TowerBattleRuleRegistry()
    }
}
