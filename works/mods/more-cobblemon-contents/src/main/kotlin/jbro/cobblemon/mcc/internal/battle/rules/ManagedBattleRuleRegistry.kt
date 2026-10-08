package jbro.cobblemon.mcc.internal.battle.rules

import jbro.cobblemon.mcc.api.rules.MajorBattleMechanic
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class ManagedActionSubmission(
    val hasBagItem: Boolean = false,
    val mechanics: List<ManagedSubmittedMechanic> = emptyList(),
)

enum class ManagedSubmittedMechanic {
    MEGA,
    DYNAMAX,
    TERA,
    Z_MOVE,
    UNSUPPORTED,
}

internal enum class ManagedRuleRejection(val message: String) {
    ACTOR_NOT_REGISTERED("This actor is not registered for this regulated battle"),
    BAG_ITEMS_DISABLED("Bag items cannot be used in this regulated battle"),
    MULTIPLE_MECHANICS("A side can use at most one major mechanic per battle"),
    WRONG_MECHANIC("This major mechanic is not enabled for the current battle"),
    MECHANIC_ALREADY_USED("This side has already used its major mechanic in this battle"),
}

internal data class ManagedActorMechanicState(
    val selected: MajorBattleMechanic?,
    val consumed: Boolean,
)

internal class ManagedBattleRuleRegistry {
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
            MajorBattleMechanic.MEGA -> ManagedSubmittedMechanic.MEGA
            MajorBattleMechanic.DYNAMAX -> ManagedSubmittedMechanic.DYNAMAX
            MajorBattleMechanic.TERA -> ManagedSubmittedMechanic.TERA
            null -> null
        }
        return battles.putIfAbsent(
            battleId,
            BattleRules(contentId, setOfNotNull(expected), actorIds, selectedMechanic = mechanic, allowMultiplePerTurn = false),
        ) == null
    }

    fun registerMultiple(
        battleId: UUID,
        mechanics: Set<ManagedSubmittedMechanic>,
        actorIds: Set<UUID>,
        contentId: String = UNSPECIFIED_CONTENT_ID,
    ): Boolean {
        require(actorIds.isNotEmpty()) { "actorIds must not be empty" }
        require(ManagedSubmittedMechanic.UNSUPPORTED !in mechanics) { "Unsupported mechanics cannot be enabled" }
        require(ManagedBattleContentIds.isValid(contentId)) { "contentId must be a lowercase namespaced ID" }
        return battles.putIfAbsent(
            battleId,
            BattleRules(contentId, mechanics, actorIds, selectedMechanic = null, allowMultiplePerTurn = true),
        ) == null
    }

    fun unregister(battleId: UUID): Boolean = battles.remove(battleId) != null

    fun isRegistered(battleId: UUID): Boolean = battles.containsKey(battleId)

    fun registeredBattleIds(): Set<UUID> = battles.keys.toSet()

    fun clear() = battles.clear()

    fun allowedMechanics(battleId: UUID): Set<ManagedSubmittedMechanic>? =
        battles[battleId]?.snapshotAllowedMechanics()

    fun contentId(battleId: UUID): String? = battles[battleId]?.contentId

    fun rejectionReason(
        battleId: UUID,
        actorId: UUID,
        submission: ManagedActionSubmission,
    ): ManagedRuleRejection? = battles[battleId]?.rejectionReason(actorId, submission)

    fun recordAccepted(
        battleId: UUID,
        actorId: UUID,
        submission: ManagedActionSubmission,
    ): Boolean = battles[battleId]?.recordAccepted(actorId, submission) ?: false

    fun actorMechanicState(battleId: UUID, actorId: UUID): ManagedActorMechanicState? =
        battles[battleId]?.actorMechanicState(actorId)

    fun availableMechanics(battleId: UUID, actorId: UUID): Set<ManagedSubmittedMechanic>? =
        battles[battleId]?.availableMechanics(actorId)

    private class BattleRules(
        val contentId: String,
        allowedMechanics: Set<ManagedSubmittedMechanic>,
        actorIds: Set<UUID>,
        private val selectedMechanic: MajorBattleMechanic?,
        private val allowMultiplePerTurn: Boolean,
    ) {
        private val allowedMechanics = Collections.unmodifiableSet(LinkedHashSet(allowedMechanics))
        private val actors = actorIds.associateWith { LinkedHashSet<ManagedSubmittedMechanic>() }.toMutableMap()

        fun snapshotAllowedMechanics(): Set<ManagedSubmittedMechanic> = allowedMechanics.toSet()

        @Synchronized
        fun availableMechanics(actorId: UUID): Set<ManagedSubmittedMechanic>? =
            actors[actorId]?.let { consumed -> allowedMechanics - consumed }

        @Synchronized
        fun rejectionReason(actorId: UUID, submission: ManagedActionSubmission): ManagedRuleRejection? {
            val consumed = actors[actorId] ?: return ManagedRuleRejection.ACTOR_NOT_REGISTERED
            if (submission.hasBagItem) return ManagedRuleRejection.BAG_ITEMS_DISABLED
            if ((!allowMultiplePerTurn && submission.mechanics.size > 1) ||
                submission.mechanics.distinct().size != submission.mechanics.size
            ) {
                return ManagedRuleRejection.MULTIPLE_MECHANICS
            }
            if (submission.mechanics.any { it !in allowedMechanics }) return ManagedRuleRejection.WRONG_MECHANIC
            if (submission.mechanics.any { it in consumed }) return ManagedRuleRejection.MECHANIC_ALREADY_USED
            return null
        }

        @Synchronized
        fun recordAccepted(actorId: UUID, submission: ManagedActionSubmission): Boolean {
            if (rejectionReason(actorId, submission) != null) return false
            actors.getValue(actorId).addAll(submission.mechanics)
            return true
        }

        @Synchronized
        fun actorMechanicState(actorId: UUID): ManagedActorMechanicState? =
            actors[actorId]?.let { consumed ->
                val expected = when (selectedMechanic) {
                    MajorBattleMechanic.MEGA -> ManagedSubmittedMechanic.MEGA
                    MajorBattleMechanic.DYNAMAX -> ManagedSubmittedMechanic.DYNAMAX
                    MajorBattleMechanic.TERA -> ManagedSubmittedMechanic.TERA
                    null -> null
                }
                ManagedActorMechanicState(selectedMechanic, expected != null && expected in consumed)
            }
    }

    internal companion object {
        private const val UNSPECIFIED_CONTENT_ID = "more_cobblemon_contents:managed"
        val global = ManagedBattleRuleRegistry()
    }
}
