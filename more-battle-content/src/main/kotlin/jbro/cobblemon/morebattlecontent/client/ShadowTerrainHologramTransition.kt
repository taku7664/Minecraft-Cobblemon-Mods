package jbro.cobblemon.morebattlecontent.client

import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.presentation.BattleArenaHologramProjection

internal data class ShadowTerrainHologramSnapshot(
    val projection: BattleArenaHologramProjection,
    val arenaCenter: ShadowTerrainArenaCenter,
    val arenaDirection: ShadowTerrainArenaDirection,
    val strength: Float,
    val effectAgeSeconds: Float,
)

internal data class ShadowTerrainArenaCenter(
    val x: Double,
    val y: Double,
    val z: Double,
)

internal data class ShadowTerrainArenaDirection(
    val x: Double,
    val z: Double,
)

internal class ShadowTerrainHologramTransition(
    private val fadeDurationNanos: Long,
) {
    init {
        require(fadeDurationNanos > 0L)
    }

    private var projection: BattleArenaHologramProjection? = null
    private var arenaCenter: ShadowTerrainArenaCenter? = null
    private var arenaDirection: ShadowTerrainArenaDirection? = null
    private var transitionStartedNanos = 0L
    private var effectStartedNanos = 0L
    private var transitionStartStrength = 0F
    private var targetStrength = 0F

    fun show(next: BattleArenaHologramProjection, nowNanos: Long) {
        if (projection?.battleId == next.battleId) {
            val currentStrength = strengthAt(nowNanos)
            projection = next
            transitionStartedNanos = nowNanos
            transitionStartStrength = currentStrength
            targetStrength = 1F
            return
        }
        projection = next
        arenaCenter = ShadowTerrainArenaCenter(next.centerX, next.centerY, next.centerZ)
        arenaDirection = ShadowTerrainArenaDirection(next.opponentDirectionX, next.opponentDirectionZ)
        transitionStartedNanos = nowNanos
        effectStartedNanos = nowNanos
        transitionStartStrength = 0F
        targetStrength = 1F
    }

    fun hide(battleId: UUID, nowNanos: Long) {
        val current = projection ?: return
        if (current.battleId != battleId || targetStrength == 0F) return
        transitionStartStrength = strengthAt(nowNanos)
        transitionStartedNanos = nowNanos
        targetStrength = 0F
    }

    fun clear() {
        projection = null
        arenaCenter = null
        arenaDirection = null
        effectStartedNanos = 0L
        transitionStartStrength = 0F
        targetStrength = 0F
    }

    fun isRetained(): Boolean = projection != null

    fun snapshot(nowNanos: Long): ShadowTerrainHologramSnapshot? {
        val current = projection ?: return null
        val capturedCenter = arenaCenter ?: return null
        val capturedDirection = arenaDirection ?: return null
        val strength = strengthAt(nowNanos)
        if (targetStrength == 0F && strength <= 0F) {
            clear()
            return null
        }
        val effectAgeSeconds = ((nowNanos - effectStartedNanos).coerceAtLeast(0L) / NANOS_PER_SECOND).toFloat()
        return ShadowTerrainHologramSnapshot(current, capturedCenter, capturedDirection, strength, effectAgeSeconds)
    }

    private fun strengthAt(nowNanos: Long): Float {
        val elapsed = (nowNanos - transitionStartedNanos).coerceAtLeast(0L)
        val progress = (elapsed.toDouble() / fadeDurationNanos.toDouble()).coerceIn(0.0, 1.0).toFloat()
        return transitionStartStrength + (targetStrength - transitionStartStrength) * progress
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
