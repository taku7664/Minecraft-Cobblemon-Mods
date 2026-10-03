package jbro.cobblemon.mcc.client

import com.cobblemon.mod.common.client.CobblemonClient
import jbro.cobblemon.mcc.api.client.MccPendingBattle
import jbro.cobblemon.mcc.api.client.MccPendingBattles
import jbro.cobblemon.mcc.internal.battle.BattleEntryCancelPayload
import jbro.cobblemon.mcc.internal.battle.BattleEntryHoldPayload
import jbro.cobblemon.mcc.internal.battle.BattleEntryReadyPayload
import jbro.cobblemon.mcc.internal.battle.BattleEntryStyle
import jbro.cobblemon.ui.extended.transition.BattleEntryKind
import jbro.cobblemon.ui.extended.transition.BattleEntryTransition
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

/**
 * Plays Cobblemon UI's battle entry transition for a battle the server holds, and tells the server to start it once
 * the screen is covered, at once when this client shows no transition.
 */
object BattleEntryClientNetworking {
    /** A pending battle that never opened is forgotten after this long, so music does not wait on it forever. */
    private const val PENDING_LIMIT_MILLIS = 10_000L
    private var pendingSince = 0L

    fun register() {
        ClientPlayNetworking.registerGlobalReceiver(BattleEntryHoldPayload.TYPE) { payload, context ->
            context.client().execute { hold(payload) }
        }
        ClientPlayNetworking.registerGlobalReceiver(BattleEntryCancelPayload.TYPE) { payload, context ->
            context.client().execute {
                if (MccPendingBattles.current()?.battleId == payload.battleId) MccPendingBattles.set(null)
                BattleEntryTransition.cancel()
            }
        }
        // The pending battle has opened (or this client left it behind): music reads the real battle from here on.
        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            val pending = MccPendingBattles.current() ?: return@register
            val battle = CobblemonClient.battle
            val stale = net.minecraft.Util.getMillis() - pendingSince > PENDING_LIMIT_MILLIS
            if (stale || battle != null && battle.battleId == pending.battleId) MccPendingBattles.set(null)
        }
        MccClientSessionReset.onReset("battle entry transition") {
            MccPendingBattles.set(null)
            BattleEntryTransition.cancel()
        }
    }

    private fun hold(payload: BattleEntryHoldPayload) {
        pendingSince = net.minecraft.Util.getMillis()
        MccPendingBattles.set(MccPendingBattle(payload.battleId, payload.style.id, payload.species?.toString(),
            payload.form, payload.labels))
        val kind = when (payload.style) {
            BattleEntryStyle.WILD -> BattleEntryKind.WILD
            BattleEntryStyle.LEGENDARY -> BattleEntryKind.LEGENDARY
            BattleEntryStyle.TRAINER -> BattleEntryKind.TRAINER
        }
        val ready = Runnable { ready(payload) }
        val playing = try {
            BattleEntryTransition.play(kind, payload.species, ready)
        } catch (failure: RuntimeException) {
            jbro.cobblemon.mcc.MoreCobblemonContents.LOGGER.warn("Battle entry transition failed to play", failure)
            false
        }
        if (!playing) ready.run()
    }

    private fun ready(payload: BattleEntryHoldPayload) {
        if (ClientPlayNetworking.canSend(BattleEntryReadyPayload.TYPE)) {
            ClientPlayNetworking.send(BattleEntryReadyPayload(payload.battleId))
        }
    }
}
