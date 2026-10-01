package jbro.cobblemon.mcc.client

import jbro.cobblemon.mcc.internal.battle.CancelBattleEntryPayload
import jbro.cobblemon.mcc.internal.battle.StartBattleEntryPayload
import jbro.cobblemon.ui.extended.transition.BattleEntryTransition
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

/** Plays Cobblemon UI's battle entry transition when the server holds a battle for it. */
object BattleEntryClientNetworking {
    fun register() {
        ClientPlayNetworking.registerGlobalReceiver(StartBattleEntryPayload.TYPE) { payload, context ->
            context.client().execute { BattleEntryTransition.play(payload.species, payload.trainer) }
        }
        ClientPlayNetworking.registerGlobalReceiver(CancelBattleEntryPayload.TYPE) { _, context ->
            context.client().execute { BattleEntryTransition.cancel() }
        }
        MccClientSessionReset.onReset("battle entry transition", BattleEntryTransition::cancel)
    }
}
