package jbro.cobblemon.morebattlecontent.leaguechallenge.client

import com.google.gson.Gson
import java.util.UUID
import jbro.cobblemon.morebattlecontent.leaguechallenge.network.*
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

/** UI Toolkit consumer boundary: render snapshots, send intent, never locally advance progression. */
object LeagueClientSession {
    var current: LeagueView? = null
        private set
    private val listeners = linkedSetOf<(LeagueView?) -> Unit>()
    private val gson = Gson()

    fun observe(listener: (LeagueView?) -> Unit): AutoCloseable {
        listeners += listener
        listener(current)
        return AutoCloseable { listeners -= listener }
    }

    fun send(action: LeagueAction, challengeId: String = "") {
        val state = current ?: return
        if (!ClientPlayNetworking.canSend(LeagueIntentPayload.TYPE)) return
        ClientPlayNetworking.send(LeagueIntentPayload(state.nonce, UUID.randomUUID(), state.revision,
            state.catalogRevision, action, challengeId))
    }

    internal fun register() {
        ClientPlayNetworking.registerGlobalReceiver(LeagueStatePayload.TYPE) { payload, context ->
            val state = gson.fromJson(payload.json, LeagueView::class.java)
            context.client().execute {
                current = state
                listeners.toList().forEach { it(state) }
            }
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, client ->
            client.execute { current = null; listeners.toList().forEach { it(null) } }
        }
    }
}
