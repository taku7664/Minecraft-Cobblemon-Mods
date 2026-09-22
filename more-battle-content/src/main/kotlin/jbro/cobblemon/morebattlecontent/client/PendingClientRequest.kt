package jbro.cobblemon.morebattlecontent.client

internal class PendingClientRequest {
    var isPending = false
        private set

    fun begin(): Boolean {
        if (isPending) return false
        isPending = true
        return true
    }

    fun send(action: () -> Unit): Boolean {
        if (!begin()) return false
        try {
            action()
        } catch (failure: Throwable) {
            reset()
            throw failure
        }
        return true
    }

    fun complete(accepted: Boolean): Boolean {
        if (!isPending) return false
        isPending = false
        return accepted
    }

    fun reset() {
        isPending = false
    }
}
