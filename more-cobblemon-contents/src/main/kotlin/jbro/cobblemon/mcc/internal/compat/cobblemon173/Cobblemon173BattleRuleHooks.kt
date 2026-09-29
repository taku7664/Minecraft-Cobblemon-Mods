package jbro.cobblemon.mcc.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.battles.model.actor.BattleActor
import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import com.cobblemon.mod.common.battles.BagItemActionResponse
import com.cobblemon.mod.common.battles.MoveActionResponse
import com.cobblemon.mod.common.battles.ShowdownActionResponse
import jbro.cobblemon.mcc.api.rules.MajorBattleMechanic
import jbro.cobblemon.mcc.internal.battle.rules.ManagedActionSubmission
import jbro.cobblemon.mcc.internal.battle.rules.ManagedBattleRuleRegistry
import jbro.cobblemon.mcc.internal.battle.rules.ManagedBattleRuleRegistrationWindow
import jbro.cobblemon.mcc.internal.battle.rules.ManagedSubmittedMechanic
import jbro.cobblemon.mcc.internal.battle.ManagedBattleMechanic
import jbro.cobblemon.mcc.internal.battle.ManagedBattleMechanicVisibilityNetworking
import jbro.cobblemon.mcc.internal.battle.GimmickLockedBattles
import jbro.cobblemon.mcc.internal.battle.ManagedBattleContentNetworking
import jbro.cobblemon.mcc.internal.battle.ManagedTurnInterceptors
import java.util.UUID

object Cobblemon173BattleRuleHooks {
    private val registry = ManagedBattleRuleRegistry.global
    private val registrationWindow = ManagedBattleRuleRegistrationWindow(registry)

    @JvmStatic
    fun rejectionMessage(actor: BattleActor, responses: List<ShowdownActionResponse>): String? {
        val submission = inspect(responses)
        return registry.rejectionReason(actor.battle.battleId, actor.uuid, submission)?.message
            ?: GimmickLockedBattles.rejection(actor.battle.battleId, actor.uuid, submission)
    }

    @JvmStatic
    fun recordAccepted(actor: BattleActor, responses: List<ShowdownActionResponse>) {
        registry.recordAccepted(actor.battle.battleId, actor.uuid, inspect(responses))
    }

    fun register(battleId: UUID, mechanic: MajorBattleMechanic?, actorIds: Set<UUID>): Boolean =
        registry.register(battleId, mechanic, actorIds)

    fun beginRegistration(contentId: String, mechanic: MajorBattleMechanic?, actorIds: Set<UUID>) =
        registrationWindow.begin(contentId, mechanic, actorIds)

    fun beginRegistrationMultiple(contentId: String, mechanics: Set<ManagedSubmittedMechanic>, actorIds: Set<UUID>) =
        registrationWindow.beginMultiple(contentId, mechanics, actorIds)

    @JvmStatic
    fun attachConstructed(battle: PokemonBattle) {
        if (!registrationWindow.attachIfPending(battle.battleId, battle.actors.map { actor -> actor.uuid }.toSet())) {
            // Not MCC content: players who have not earned gimmicks yet battle without them.
            GimmickLockedBattles.attach(battle)
            return
        }
        val mechanics = requireNotNull(registry.allowedMechanics(battle.battleId)) {
            "Managed battle rules were attached without an allowed mechanic snapshot"
        }.mapNotNullTo(LinkedHashSet()) { mechanic -> mechanic.toManagedMechanic() }
        ManagedBattleMechanicVisibilityNetworking.showBeforeBattleInitialization(battle, mechanics)
        ManagedBattleContentNetworking.showBeforeBattleInitialization(
            battle,
            requireNotNull(registry.contentId(battle.battleId)),
        )
    }

    @JvmStatic
    fun beforeBattleEnd(battle: PokemonBattle) {
        runManagedCleanupActionsSafely(
            reportFailure = { failure ->
                reportManagedCleanupFailureSafely(failure) {
                    jbro.cobblemon.mcc.MoreCobblemonContents.LOGGER.error(
                        "Managed battle end hook failed for battle {}",
                        compatibilityCallOrNull { battle.battleId },
                        it,
                    )
                }
            },
            { ManagedTurnInterceptors.forget(battle.battleId) },
            { hideClientMechanicPolicy(battle) },
            { GimmickLockedBattles.detach(battle) },
        )
    }

    private fun hideClientMechanicPolicy(battle: PokemonBattle) {
        if (registry.isRegistered(battle.battleId)) {
            ManagedBattleMechanicVisibilityNetworking.hide(battle)
            ManagedBattleContentNetworking.hide(battle)
        }
    }

    fun finishRegistration(successfulBattleId: UUID?): Boolean = registrationWindow.finish(successfulBattleId)

    fun unregister(battleId: UUID): Boolean = registry.unregister(battleId)

    @JvmStatic
    fun isRegisteredBattle(battleId: UUID): Boolean = registry.isRegistered(battleId)

    @JvmStatic
    fun abortFailedBattle(battleId: UUID) = Cobblemon173ManagedBattleTermination.end(battleId)

    @JvmStatic
    fun shouldSuppressExperience(battleId: UUID): Boolean = registry.isRegistered(battleId)

    fun registeredBattleIds(): Set<UUID> = registry.registeredBattleIds()

    fun clear() {
        registrationWindow.clear()
        registry.clear()
    }

    fun contentId(battleId: UUID): String? = registry.contentId(battleId)

    fun mechanicPolicy(battleId: UUID, actorId: UUID): Cobblemon173MechanicPolicy? =
        registry.actorMechanicState(battleId, actorId)?.let { Cobblemon173MechanicPolicy(it.selected, it.consumed) }

    internal fun inspect(responses: List<ShowdownActionResponse>) = ManagedActionSubmission(
        hasBagItem = responses.any { it is BagItemActionResponse },
        mechanics = responses.mapNotNull { (it as? MoveActionResponse)?.gimmickID }.map { gimmickId ->
            when (gimmickId) {
                "mega" -> ManagedSubmittedMechanic.MEGA
                "max" -> ManagedSubmittedMechanic.DYNAMAX
                "terastal" -> ManagedSubmittedMechanic.TERA
                "zmove" -> ManagedSubmittedMechanic.Z_MOVE
                else -> ManagedSubmittedMechanic.UNSUPPORTED
            }
        },
    )
}

private fun ManagedSubmittedMechanic.toManagedMechanic(): ManagedBattleMechanic? = when (this) {
    ManagedSubmittedMechanic.MEGA -> ManagedBattleMechanic.MEGA
    ManagedSubmittedMechanic.DYNAMAX -> ManagedBattleMechanic.DYNAMAX
    ManagedSubmittedMechanic.TERA -> ManagedBattleMechanic.TERA
    ManagedSubmittedMechanic.Z_MOVE -> ManagedBattleMechanic.Z_MOVE
    ManagedSubmittedMechanic.UNSUPPORTED -> null
}
