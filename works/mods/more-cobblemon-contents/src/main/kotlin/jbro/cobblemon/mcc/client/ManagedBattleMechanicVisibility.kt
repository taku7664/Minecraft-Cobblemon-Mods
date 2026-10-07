package jbro.cobblemon.mcc.client

import com.cobblemon.mod.common.battles.ShowdownMoveset
import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.client.gui.battle.BattleGUI
import java.util.UUID
import jbro.cobblemon.mcc.internal.battle.HideManagedBattleMechanicsPayload
import jbro.cobblemon.mcc.internal.battle.ManagedBattleMechanic
import jbro.cobblemon.mcc.internal.battle.ShowManagedBattleMechanicsPayload
import jbro.cobblemon.mcc.internal.battle.ShowManagedBattleContentPayload
import jbro.cobblemon.mcc.internal.battle.HideManagedBattleContentPayload
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentClient
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
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
        MccClientSessionReset.onReset("managed battle mechanic visibility", state::clear)
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
    /**
     * Battles the server has finished while this client still shows them: a closing scene holds the battle's end, and
     * the tag (and with it the battle's own music) stays until the battle is really gone.
     */
    private val endingBattles = mutableSetOf<UUID>()

    fun register() {
        ClientPlayNetworking.registerGlobalReceiver(ShowManagedBattleContentPayload.TYPE) { payload, context ->
            context.client().execute {
                endingBattles.remove(payload.battleId)
                ManagedBattleContentClient.show(payload.battleId, payload.tag)
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(HideManagedBattleContentPayload.TYPE) { payload, context ->
            context.client().execute {
                if (CobblemonClient.battle?.battleId == payload.battleId) endingBattles.add(payload.battleId)
                else ManagedBattleContentClient.hide(payload.battleId)
            }
        }
        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            if (endingBattles.isEmpty()) return@register
            val shown = CobblemonClient.battle?.battleId
            endingBattles.removeIf { battleId ->
                (battleId != shown).also { gone -> if (gone) ManagedBattleContentClient.hide(battleId) }
            }
        }
        MccClientSessionReset.onReset("managed battle content") {
            endingBattles.clear()
            ManagedBattleContentClient.clear()
        }
    }
}

internal fun managedBattleMechanicFor(gimmick: ShowdownMoveset.Gimmick): ManagedBattleMechanic? = when (gimmick) {
    ShowdownMoveset.Gimmick.MEGA_EVOLUTION -> ManagedBattleMechanic.MEGA
    ShowdownMoveset.Gimmick.DYNAMAX -> ManagedBattleMechanic.DYNAMAX
    ShowdownMoveset.Gimmick.TERASTALLIZATION -> ManagedBattleMechanic.TERA
    ShowdownMoveset.Gimmick.Z_POWER -> ManagedBattleMechanic.Z_MOVE
    ShowdownMoveset.Gimmick.ULTRA_BURST -> null
}
