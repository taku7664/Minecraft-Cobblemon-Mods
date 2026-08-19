package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.battles.model.actor.BattleActor
import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import com.cobblemon.mod.common.battles.BagItemActionResponse
import com.cobblemon.mod.common.battles.MoveActionResponse
import com.cobblemon.mod.common.battles.ShowdownActionResponse
import jbro.cobblemon.morebattlecontent.api.rules.MajorBattleMechanic
import jbro.cobblemon.morebattlecontent.internal.tower.rules.TowerActionSubmission
import jbro.cobblemon.morebattlecontent.internal.tower.rules.TowerBattleRuleRegistry
import jbro.cobblemon.morebattlecontent.internal.tower.rules.TowerBattleRuleRegistrationWindow
import jbro.cobblemon.morebattlecontent.internal.tower.rules.TowerSubmittedMechanic
import jbro.cobblemon.morebattlecontent.internal.battle.ManagedBattleMechanic
import jbro.cobblemon.morebattlecontent.internal.battle.ManagedBattleMechanicVisibilityNetworking
import jbro.cobblemon.morebattlecontent.internal.battle.ManagedBattleContentNetworking
import java.util.UUID

internal object Cobblemon173BattleRuleHooks {
    private val registry = TowerBattleRuleRegistry.global
    private val registrationWindow = TowerBattleRuleRegistrationWindow(registry)

    @JvmStatic
    fun rejectionMessage(actor: BattleActor, responses: List<ShowdownActionResponse>): String? =
        registry.rejectionReason(actor.battle.battleId, actor.uuid, inspect(responses))?.message

    @JvmStatic
    fun recordAccepted(actor: BattleActor, responses: List<ShowdownActionResponse>) {
        registry.recordAccepted(actor.battle.battleId, actor.uuid, inspect(responses))
    }

    fun register(battleId: UUID, mechanic: MajorBattleMechanic?, actorIds: Set<UUID>): Boolean =
        registry.register(battleId, mechanic, actorIds)

    fun beginRegistration(contentId: String, mechanic: MajorBattleMechanic?, actorIds: Set<UUID>) =
        registrationWindow.begin(contentId, mechanic, actorIds)

    fun beginRegistrationMultiple(contentId: String, mechanics: Set<TowerSubmittedMechanic>, actorIds: Set<UUID>) =
        registrationWindow.beginMultiple(contentId, mechanics, actorIds)

    @JvmStatic
    fun attachConstructed(battle: PokemonBattle) {
        if (!registrationWindow.attachIfPending(battle.battleId, battle.actors.map { actor -> actor.uuid }.toSet())) return
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
    fun hideClientMechanicPolicy(battle: PokemonBattle) {
        if (registry.isRegistered(battle.battleId)) {
            ManagedBattleMechanicVisibilityNetworking.hide(battle)
            ManagedBattleContentNetworking.hide(battle)
        }
    }

    fun finishRegistration(successfulBattleId: UUID?): Boolean = registrationWindow.finish(successfulBattleId)

    fun unregister(battleId: UUID): Boolean = registry.unregister(battleId)

    fun isRegisteredBattle(battleId: UUID): Boolean = registry.isRegistered(battleId)

    @JvmStatic
    fun shouldSuppressExperience(battleId: UUID): Boolean = registry.isRegistered(battleId)

    fun registeredBattleIds(): Set<UUID> = registry.registeredBattleIds()

    fun contentId(battleId: UUID): String? = registry.contentId(battleId)

    fun mechanicPolicy(battleId: UUID, actorId: UUID): Cobblemon173MechanicPolicy? =
        registry.actorMechanicState(battleId, actorId)?.let { Cobblemon173MechanicPolicy(it.selected, it.consumed) }

    internal fun inspect(responses: List<ShowdownActionResponse>) = TowerActionSubmission(
        hasBagItem = responses.any { it is BagItemActionResponse },
        mechanics = responses.mapNotNull { (it as? MoveActionResponse)?.gimmickID }.map { gimmickId ->
            when (gimmickId) {
                "mega" -> TowerSubmittedMechanic.MEGA
                "max" -> TowerSubmittedMechanic.DYNAMAX
                "terastal" -> TowerSubmittedMechanic.TERA
                "zmove" -> TowerSubmittedMechanic.Z_MOVE
                else -> TowerSubmittedMechanic.UNSUPPORTED
            }
        },
    )
}

private fun TowerSubmittedMechanic.toManagedMechanic(): ManagedBattleMechanic? = when (this) {
    TowerSubmittedMechanic.MEGA -> ManagedBattleMechanic.MEGA
    TowerSubmittedMechanic.DYNAMAX -> ManagedBattleMechanic.DYNAMAX
    TowerSubmittedMechanic.TERA -> ManagedBattleMechanic.TERA
    TowerSubmittedMechanic.Z_MOVE -> ManagedBattleMechanic.Z_MOVE
    TowerSubmittedMechanic.UNSUPPORTED -> null
}
