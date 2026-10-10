package jbro.cobblemon.policy.admin

import java.util.UUID
import java.util.ArrayDeque

/** Server-thread queue: console execution must start outside an existing Minecraft command context. */
internal class ConsoleCommandQueue {
    data class Request(val playerId: UUID, val command: String)
    enum class Rejection { PERMISSION, EMPTY, INVALID, TOO_LONG, RECURSION, BUSY }
    private val pending = ArrayDeque<Request>()
    var isExecuting: Boolean = false
        private set

    fun submit(playerId: UUID, permission: Int, input: String): Rejection? {
        if (permission < 4) return Rejection.PERMISSION
        if (isExecuting) return Rejection.RECURSION
        if (input.length > 2048) return Rejection.TOO_LONG
        if (input.any { Character.isISOControl(it) }) return Rejection.INVALID
        val command = input.trim().removePrefix("/").trim()
        if (command.isEmpty()) return Rejection.EMPTY
        if (pending.size >= 32) return Rejection.BUSY
        pending.addLast(Request(playerId, command))
        return null
    }

    fun drain(permission: (UUID) -> Int?, execute: (Request) -> Unit, denied: (Request) -> Unit) {
        repeat(4) {
            val request = pending.pollFirst() ?: return
            if ((permission(request.playerId) ?: 0) < 4) {
                denied(request)
            } else {
                isExecuting = true
                try {
                    execute(request)
                } finally {
                    isExecuting = false
                }
            }
        }
    }

    fun clear() {
        pending.clear()
    }
}
