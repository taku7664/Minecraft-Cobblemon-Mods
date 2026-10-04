package jbro.cobblemon.mcc.client

import com.cobblemon.mod.common.client.CobblemonClient
import jbro.cobblemon.mcc.api.client.MccClientBattle
import jbro.cobblemon.mcc.api.client.MccClientContext
import jbro.cobblemon.mcc.api.client.MccClientState
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentClient
import jbro.cobblemon.mcc.client.hub.MccHubScreen
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft

/** Samples the hub and the battle on screen each client tick and hands the result to [MccClientContext]. */
internal object MccClientContextTracker {
    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { client -> MccClientContext.update(sample(client)) }
    }

    private fun sample(client: Minecraft): MccClientState {
        if (client.player == null) return MccClientState.NONE
        val battle = CobblemonClient.battle?.let { battle ->
            MccClientBattle(battle.battleId, battle.spectating, ManagedBattleContentClient.tag(battle.battleId))
        }
        return MccClientState((client.screen as? MccHubScreen)?.selectedTabId, battle)
    }
}
