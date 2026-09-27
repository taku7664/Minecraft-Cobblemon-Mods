package jbro.cobblemon.mcc.internal.pvp.network

import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor
import java.util.UUID
import jbro.cobblemon.mcc.internal.battle.ManagedTurnCapture
import jbro.cobblemon.mcc.internal.battle.ManagedTurnInterceptor
import jbro.cobblemon.mcc.internal.pvp.PvpTurnCapture

/** PvP turn timers at the shared managed action-response gate. */
internal object PvpManagedTurnInterceptor : ManagedTurnInterceptor {
    override fun capture(actor: BattleActor): ManagedTurnCapture? =
        PvpPlayNetworking.captureBattleTurn(actor)?.let(::PvpManagedTurnCapture)

    override fun observe(battle: PokemonBattle) {
        PvpPlayNetworking.observeBattleTurn(battle)
    }

    override fun forget(battleId: UUID) {
        PvpPlayNetworking.forgetBattleTurn(battleId)
    }
}

private class PvpManagedTurnCapture(private val capture: PvpTurnCapture) : ManagedTurnCapture {
    override val timedOut: Boolean get() = capture.timedOut

    override fun resolveTimedOut(actor: BattleActor) {
        PvpPlayNetworking.resolveTimedOutBattleTurn(actor, capture)
    }

    override fun accept() {
        PvpPlayNetworking.acceptBattleTurn(capture)
    }

    override fun reject() {
        PvpPlayNetworking.rejectBattleTurn(capture)
    }
}
