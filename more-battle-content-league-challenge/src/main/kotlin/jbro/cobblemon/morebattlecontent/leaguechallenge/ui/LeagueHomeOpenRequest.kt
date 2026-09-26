package jbro.cobblemon.morebattlecontent.leaguechallenge.ui

import java.util.UUID

/** One-shot server open request. Background state and occupied screens cannot steal focus. */
internal class LeagueHomeOpenRequest {
    private var nonce: UUID? = null
    private var expiresAt = 0L

    fun request(session: UUID, tick: Long) { nonce = session; expiresAt = tick + 200 }
    fun dismiss() { nonce = null }
    fun consume(session: UUID?, tick: Long, worldReady: Boolean, battleActive: Boolean, screenAvailable: Boolean): Boolean {
        if (nonce == null) return false
        if (nonce != session || tick > expiresAt || !worldReady) { dismiss(); return false }
        if (battleActive || !screenAvailable) return false
        dismiss()
        return true
    }
}
