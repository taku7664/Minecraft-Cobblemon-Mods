package jbro.cobblemon.morebattlecontent.internal.terminal

import kotlin.math.PI
import kotlin.math.sin

internal data class HoloTerminalAnimationFrame(
    val rotationRadians: Float,
    val pulse: Float,
    val scanHeight: Float,
)

internal object HoloTerminalAnimation {
    const val FULL_ROTATION = (PI * 2.0).toFloat()

    fun frame(gameTime: Long, partialTick: Float): HoloTerminalAnimationFrame {
        require(gameTime >= 0) { "Game time cannot be negative" }
        require(partialTick.isFinite() && partialTick in 0.0f..1.0f) {
            "Partial tick must be a finite frame fraction"
        }
        val timestamp = gameTime.toDouble() + partialTick
        val rotation = ((timestamp * ROTATION_SPEED) % (PI * 2.0)).toFloat()
        val pulse = (sin(timestamp * PULSE_SPEED) * 0.5 + 0.5).toFloat()
        val scan = (SCAN_MINIMUM + ((timestamp * SCAN_SPEED) % 1.0) * SCAN_RANGE).toFloat()
        return HoloTerminalAnimationFrame(rotation, pulse, scan)
    }

    private const val ROTATION_SPEED = 0.045
    private const val PULSE_SPEED = 0.12
    private const val SCAN_SPEED = 0.035
    private const val SCAN_MINIMUM = 0.55
    private const val SCAN_RANGE = 0.8
}
