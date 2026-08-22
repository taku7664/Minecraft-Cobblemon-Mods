package jbro.cobblemon.morebattlecontent.client

internal class PvpLoungeExitRequestState {
    private var pending = false

    fun begin(): Boolean {
        if (pending) return false
        pending = true
        return true
    }

    fun complete(accepted: Boolean): Boolean {
        if (!pending) return false
        pending = false
        return accepted
    }

    fun reset() {
        pending = false
    }
}
