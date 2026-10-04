package jbro.cobblemon.mcc.api.rules

import java.util.concurrent.CopyOnWriteArrayList
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

/**
 * Lets content mods keep battle gimmicks (Mega Evolution, Dynamax, Terastallization and Z-Moves) from players who
 * have not earned them yet. A lock covers every battle outside MCC's own content that is not between players:
 * wild Pokemon, NPC trainers and the like. MCC content battles keep their own rules, and a battle with players on
 * both sides keeps every gimmick.
 */
object BattleGimmickLocks {
    /** Why [player] may not use gimmicks yet, shown to them once per session; null when this lock lets them. */
    fun interface Lock {
        fun reason(player: ServerPlayer): Component?
    }

    private val locks = CopyOnWriteArrayList<Lock>()

    /** Adds [lock] until the returned handle is closed. */
    fun register(lock: Lock): AutoCloseable {
        locks += lock
        return AutoCloseable { locks -= lock }
    }

    /** The first reason a registered lock gives for [player], or null when no lock holds them. */
    fun reason(player: ServerPlayer): Component? = locks.firstNotNullOfOrNull { lock ->
        try {
            lock.reason(player)
        } catch (failure: RuntimeException) {
            // A broken lock must not take gimmicks away from everyone.
            null
        }
    }
}
