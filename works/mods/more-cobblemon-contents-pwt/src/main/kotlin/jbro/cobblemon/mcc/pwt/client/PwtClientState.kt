package jbro.cobblemon.mcc.pwt.client

import com.cobblemon.mod.common.client.CobblemonClient
import com.google.gson.Gson
import java.util.UUID
import jbro.cobblemon.mcc.client.hub.MccHubScreen
import jbro.cobblemon.mcc.pwt.MoreCobblemonContentsPwt as Mod
import jbro.cobblemon.mcc.pwt.network.PwtAction
import jbro.cobblemon.mcc.pwt.network.PwtIntentPayload
import jbro.cobblemon.mcc.pwt.network.PwtStatePayload
import jbro.cobblemon.mcc.pwt.network.PwtView
import jbro.cobblemon.mcc.pwt.system.PwtRules
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft

/** The PWT state the server last sent, the Pokémon picked locally, and the one request in flight. */
internal object PwtClientState {
    var view: PwtView? = null
        private set
    /** Party Pokémon picked for entry, in the order picked. */
    val picked = ArrayList<UUID>()
    var pending = false
        private set
    /** A battle just ended: bring the hub back on PWT once the battle screen is gone. */
    private var reopen = false
    var ticks = 0L
        private set
    private val gson = Gson()

    fun register() {
        ClientPlayNetworking.registerGlobalReceiver(PwtStatePayload.TYPE) { payload, _ -> accept(gson.fromJson(payload.json, PwtView::class.java)) }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> view = null; picked.clear(); pending = false; reopen = false }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            ticks++
            val battle = CobblemonClient.battle != null
            // The battle takes the screen; the hub comes back on PWT when it is over.
            if (battle && MccHubScreen.showing(Mod.CONTENT)) client.setScreen(null)
            if (reopen && !battle && client.player != null && (client.screen == null || client.screen is MccHubScreen)) {
                reopen = false
                MccHubScreen.open(Mod.CONTENT)
            }
        }
    }

    private fun accept(next: PwtView) {
        view = next
        pending = false
        // Keep only picks still in the party; a run under way has its own three.
        val party = next.party.map { UUID.fromString(it.id) }.toSet()
        picked.retainAll(party)
        if (next.run != null) picked.clear()
        if (next.openScreen) reopen = true
        MccHubScreen.refresh(Mod.CONTENT)
    }

    fun toggle(id: UUID): Boolean {
        if (pending || view?.run != null) return false
        if (!picked.remove(id)) {
            if (picked.size >= PwtRules.TEAM_SIZE) return false
            picked += id
        }
        return true
    }

    fun send(action: PwtAction): Boolean {
        val nonce = view?.nonce?.let(UUID::fromString) ?: return false
        if (pending) return false
        pending = true
        ClientPlayNetworking.send(PwtIntentPayload(nonce, action, if (action == PwtAction.ENTER) picked.toList() else emptyList()))
        return true
    }

    /** True while the hub shows PWT, for redraws that need no new state. */
    fun showing(): Boolean = Minecraft.getInstance().screen.let { it is MccHubScreen && MccHubScreen.showing(Mod.CONTENT) }
}
