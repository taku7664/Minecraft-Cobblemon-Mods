package jbro.cobblemon.morebattlecontent.leaguechallenge.system

import java.util.UUID

data class TerminalAnchor(val id: UUID, val dimension: String, val x: Int, val y: Int, val z: Int)
data class TerminalObservation(val terminalId: UUID?, val dimension: String,
    val playerX: Double, val playerY: Double, val playerZ: Double,
    val blockPresent: Boolean, val permitted: Boolean, val livingNonSpectator: Boolean, val tick: Long)

/** Authorization is independent of Minecraft classes so bypass conditions can be tested directly. */
object TerminalAuthorization {
    fun reject(anchor: TerminalAnchor, observed: TerminalObservation, touched: Long): String? {
        if (!observed.blockPresent || !observed.permitted || !observed.livingNonSpectator ||
            observed.terminalId != anchor.id || observed.dimension != anchor.dimension) return "terminal_invalid"
        val dx = observed.playerX - (anchor.x + .5)
        val dy = observed.playerY - (anchor.y + .5)
        val dz = observed.playerZ - (anchor.z + .5)
        val distance = dx * dx + dy * dy + dz * dz
        if (!distance.isFinite() || distance > 64.0) return "terminal_invalid"
        if (observed.tick < touched || observed.tick - touched >= 12000) return "terminal_expired"
        return null
    }
}
