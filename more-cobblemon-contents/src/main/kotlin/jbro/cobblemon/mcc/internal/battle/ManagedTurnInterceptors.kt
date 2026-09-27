package jbro.cobblemon.mcc.internal.battle

import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Lets a content pace or time out player turns at the shared managed action-response gate. */
internal interface ManagedTurnInterceptor {
    /** This interceptor's claim on [actor]'s current request, or null when it does not own the turn. */
    fun capture(actor: BattleActor): ManagedTurnCapture?

    /** Runs at the end of every battle tick. */
    fun observe(battle: PokemonBattle)

    /** Runs when a battle ends. */
    fun forget(battleId: UUID)
}

/** One captured turn; exactly one of [accept], [reject] or [resolveTimedOut] settles it. */
internal interface ManagedTurnCapture {
    val timedOut: Boolean

    fun resolveTimedOut(actor: BattleActor)

    fun accept()

    fun reject()
}

internal object ManagedTurnInterceptors {
    private val interceptors = CopyOnWriteArrayList<ManagedTurnInterceptor>()

    fun register(interceptor: ManagedTurnInterceptor): AutoCloseable {
        interceptors += interceptor
        return AutoCloseable { interceptors.remove(interceptor) }
    }

    @JvmStatic
    fun capture(actor: BattleActor): ManagedTurnCapture? = interceptors.firstNotNullOfOrNull { it.capture(actor) }

    @JvmStatic
    fun observe(battle: PokemonBattle) = interceptors.forEach { it.observe(battle) }

    @JvmStatic
    fun forget(battleId: UUID) = interceptors.forEach { it.forget(battleId) }
}

internal object ManagedTurnResponseCardinality {
    @JvmStatic
    fun accepts(activeChoices: Int, forcedSwitchChoices: Int, responseCount: Int): Boolean {
        require(activeChoices >= 0 && forcedSwitchChoices >= 0 && responseCount >= 0)
        return responseCount == maxOf(activeChoices, forcedSwitchChoices)
    }
}
