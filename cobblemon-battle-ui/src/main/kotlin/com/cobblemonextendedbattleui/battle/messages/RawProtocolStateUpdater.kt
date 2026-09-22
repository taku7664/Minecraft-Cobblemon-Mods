package jbro.cobblemon.battleui.extended.battle.messages

import jbro.cobblemon.battleui.extended.BattleStateTracker
import java.util.UUID

/** Handles state-bearing Showdown instructions that Cobblemon 1.8.1 forwards as literal text. */
object RawProtocolStateUpdater {
    private const val CLEAR_POSITIVE_BOOST = "-clearpositiveboost"
    private const val SPECTRAL_THIEF_EFFECT = "move: Spectral Thief"
    private val PNX_PATTERN = Regex("^p[1-4][a-z]$")

    fun process(rawMessage: String, resolveActiveUuid: (String) -> UUID?): Boolean {
        val fields = rawMessage.split('|')
        if (fields.size < 5 || fields[1] != CLEAR_POSITIVE_BOOST) return false

        val effect = fields[4].removePrefix("[from] ").trim()
        if (!effect.equals(SPECTRAL_THIEF_EFFECT, ignoreCase = true)) return false

        val targetPnx = fields[2].substringBefore(':').trim()
        if (!PNX_PATTERN.matches(targetPnx)) return false

        val targetUuid = resolveActiveUuid(targetPnx) ?: return false
        BattleStateTracker.clearPositiveStats(targetUuid)
        return true
    }
}
