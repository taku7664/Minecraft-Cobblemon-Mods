package jbro.cobblemon.mcc.api.client

import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.api.battle.MccBattleTag

/**
 * Where the player is in MCC right now, on the client.
 *
 * @property hubTab the selected tab while the battle hub is open, else null. Content tabs use their content id
 *   (`more_cobblemon_contents:battle_tower`, ...); the core tabs are `more_cobblemon_contents:dashboard` and
 *   `more_cobblemon_contents:shop`.
 * @property battle the battle the player is fighting or watching, else null.
 */
data class MccClientState(val hubTab: String? = null, val battle: MccClientBattle? = null) {
    val hubOpen: Boolean get() = hubTab != null

    companion object {
        @JvmField
        val NONE = MccClientState()
    }
}

/**
 * A battle on this client. [tag] tells what MCC content runs it and at which stage; it is null for battles no content
 * tagged, such as wild Pokémon or plain player challenges, and may arrive a tick after the battle itself.
 */
data class MccClientBattle(val battleId: UUID, val spectating: Boolean, val tag: MccBattleTag?)

fun interface MccClientStateListener {
    fun changed(previous: MccClientState, current: MccClientState)
}

/**
 * The client's MCC state for music, camera or UI mods: read [current] at any time, or [listen] to hear each change
 * (hub opened or closed, tab switched, battle started, tagged or ended). Listeners run on the client thread, once per
 * change, checked every client tick.
 */
object MccClientContext {
    private val state = MccClientContextState()

    @JvmStatic
    fun current(): MccClientState = state.current

    /** Adds [listener]; closing the result removes it. */
    @JvmStatic
    fun listen(listener: MccClientStateListener): AutoCloseable = state.listen(listener)

    internal fun update(next: MccClientState) = state.update(next)
}

internal class MccClientContextState {
    @Volatile
    var current: MccClientState = MccClientState.NONE
        private set
    private val listeners = CopyOnWriteArrayList<MccClientStateListener>()

    fun listen(listener: MccClientStateListener): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    fun update(next: MccClientState) {
        val previous = current
        if (previous == next) return
        current = next
        listeners.forEach { listener ->
            try {
                listener.changed(previous, next)
            } catch (failure: RuntimeException) {
                MoreCobblemonContents.LOGGER.warn("MCC client state listener {} failed", listener, failure)
            } catch (failure: LinkageError) {
                MoreCobblemonContents.LOGGER.warn("MCC client state listener {} failed", listener, failure)
            }
        }
    }
}
