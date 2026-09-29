package jbro.cobblemon.mcc.league.client

import com.google.gson.Gson
import java.util.UUID
import jbro.cobblemon.mcc.client.hub.MccHubScreen
import jbro.cobblemon.mcc.league.network.*
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

/** UI Toolkit consumer boundary: render snapshots, send intent, never locally advance progression. */
object LeagueClientSession {
    var current: LeagueView? = null
        private set

    /** The player's rank as the server last told it, for the hub header; null before the first word. */
    var rank: String? = null
        private set
    private val listeners = linkedSetOf<(LeagueView?) -> Unit>()
    private val gson = Gson()

    fun observe(listener: (LeagueView?) -> Unit): AutoCloseable {
        listeners += listener
        listener(current)
        return AutoCloseable { listeners -= listener }
    }

    fun send(action: LeagueAction, challengeId: String = ""): Boolean {
        val state = current ?: return false
        if (!ClientPlayNetworking.canSend(LeagueIntentPayload.TYPE)) return false
        ClientPlayNetworking.send(LeagueIntentPayload(state.nonce, UUID.randomUUID(), state.revision,
            state.catalogRevision, action, challengeId))
        return true
    }

    private fun updateRank(next: String) {
        if (rank == next) return
        rank = next
        MccHubScreen.refreshHeader()
    }

    internal fun register() {
        ClientPlayNetworking.registerGlobalReceiver(LeagueStatePayload.TYPE) { payload, context ->
            val state = gson.fromJson(payload.json, LeagueView::class.java)
            context.client().execute {
                current = state
                updateRank(state.rank)
                listeners.toList().forEach { it(state) }
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(LeagueRankPayload.TYPE) { payload, context ->
            context.client().execute { updateRank(payload.rank) }
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, client ->
            client.execute { current = null; rank = null; listeners.toList().forEach { it(null) } }
        }
    }
}
