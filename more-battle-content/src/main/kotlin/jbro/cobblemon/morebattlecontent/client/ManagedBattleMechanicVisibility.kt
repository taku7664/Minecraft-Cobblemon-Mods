package jbro.cobblemon.morebattlecontent.client

import com.cobblemon.mod.common.battles.ShowdownMoveset
import com.cobblemon.mod.common.client.gui.battle.BattleGUI
import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.battle.HideManagedBattleMechanicsPayload
import jbro.cobblemon.morebattlecontent.internal.battle.ManagedBattleMechanic
import jbro.cobblemon.morebattlecontent.internal.battle.ShowManagedBattleMechanicsPayload
import jbro.cobblemon.morebattlecontent.internal.battle.ShowManagedBattleContentPayload
import jbro.cobblemon.morebattlecontent.internal.battle.HideManagedBattleContentPayload
import jbro.cobblemon.morebattlecontent.api.presentation.ManagedBattleContentClient
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

internal class ManagedBattleMechanicVisibilityState {
    private var current: Policy? = null

    @Synchronized
    fun show(battleId: UUID, mechanics: Set<ManagedBattleMechanic>) {
        current = Policy(battleId, mechanics.toSet())
    }

    @Synchronized
    fun hide(battleId: UUID) {
        if (current?.battleId == battleId) current = null
    }

    @Synchronized
    fun clear() {
        current = null
    }

    @Synchronized
    fun policy(battleId: UUID): Set<ManagedBattleMechanic>? =
        current?.takeIf { policy -> policy.battleId == battleId }?.mechanics

    fun visibleMechanics(
        battleId: UUID,
        offered: List<ManagedBattleMechanic>,
    ): List<ManagedBattleMechanic> = policy(battleId)?.let { allowed ->
        offered.filter { mechanic -> mechanic in allowed }
    } ?: offered

    private data class Policy(
        val battleId: UUID,
        val mechanics: Set<ManagedBattleMechanic>,
    )
}

object ManagedBattleMechanicVisibilityClient {
    private val state = ManagedBattleMechanicVisibilityState()

    fun register() {
        ClientPlayNetworking.registerGlobalReceiver(ShowManagedBattleMechanicsPayload.TYPE) { payload, context ->
            context.client().execute { state.show(payload.battleId, payload.mechanics) }
        }
        ClientPlayNetworking.registerGlobalReceiver(HideManagedBattleMechanicsPayload.TYPE) { payload, context ->
            context.client().execute { state.hide(payload.battleId) }
        }
        MbcClientSessionReset.onReset("managed battle mechanic visibility", state::clear)
    }

    @JvmStatic
    fun filterGimmicks(
        battleGUI: BattleGUI,
        offered: List<ShowdownMoveset.Gimmick>,
    ): List<ShowdownMoveset.Gimmick> {
        val battleId = battleGUI.actor?.side?.battle?.battleId ?: return offered
        val policy = state.policy(battleId) ?: return offered
        return offered.filter { gimmick -> managedBattleMechanicFor(gimmick) in policy }
    }
}

object ManagedBattleContentClientNetworking {
    fun register() {
        ClientPlayNetworking.registerGlobalReceiver(ShowManagedBattleContentPayload.TYPE) { payload, context ->
            context.client().execute { ManagedBattleContentClient.show(payload.battleId, payload.contentId) }
        }
        ClientPlayNetworking.registerGlobalReceiver(HideManagedBattleContentPayload.TYPE) { payload, context ->
            context.client().execute { ManagedBattleContentClient.hide(payload.battleId) }
        }
        MbcClientSessionReset.onReset("managed battle content", ManagedBattleContentClient::clear)
    }
}

internal fun managedBattleMechanicFor(gimmick: ShowdownMoveset.Gimmick): ManagedBattleMechanic? = when (gimmick) {
    ShowdownMoveset.Gimmick.MEGA_EVOLUTION -> ManagedBattleMechanic.MEGA
    ShowdownMoveset.Gimmick.DYNAMAX -> ManagedBattleMechanic.DYNAMAX
    ShowdownMoveset.Gimmick.TERASTALLIZATION -> ManagedBattleMechanic.TERA
    ShowdownMoveset.Gimmick.Z_POWER -> ManagedBattleMechanic.Z_MOVE
    ShowdownMoveset.Gimmick.ULTRA_BURST -> null
}
