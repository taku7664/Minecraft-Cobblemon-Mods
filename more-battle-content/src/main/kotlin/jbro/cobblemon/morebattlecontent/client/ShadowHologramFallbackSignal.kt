package jbro.cobblemon.morebattlecontent.client

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

internal data class ShadowHologramFallbackSample(
    val horizontalOffset: Float,
    val brightness: Float,
    val alpha: Float,
)

/** CPU version of the original Shadow shader cadence for shader-pack-safe entity rendering. */
internal object ShadowHologramFallbackSignal {
    fun sample(gameTicks: Double, worldY: Double): ShadowHologramFallbackSample {
        val pulseClock = floor(gameTicks * 0.5)
        val sequenceId = floor(pulseClock / 8.0)
        val sequenceStep = positiveMod(pulseClock, 8.0)
        val pulseIndex = floor(sequenceStep / 2.0)
        val pulseCount = 2.0 + step(0.48, randomNoise(sequenceId, 11.7))
        val eventActive = step(0.72, randomNoise(sequenceId, 5.31))
        val pulseActive = (1.0 - step(0.5, positiveMod(sequenceStep, 2.0))) *
            (1.0 - step(pulseCount, pulseIndex))

        val regionStart = floor(randomNoise(sequenceId, 23.9) * 3.0)
        val regionDirection = 1.0 + step(0.5, randomNoise(sequenceId, 41.3))
        val regionSequence = positiveMod(regionStart + pulseIndex * regionDirection, 3.0)
        val vertexRegion = positiveMod(floor(worldY * 1.55), 3.0)
        val regionActive = 1.0 - step(0.25, abs(vertexRegion - regionSequence))
        val glitchWindow = eventActive * pulseActive * regionActive
        val shiftDirection = randomNoise(sequenceId * 2.73, pulseIndex * 3.17) * 2.0 - 1.0

        val gameTime = positiveMod(gameTicks, 24_000.0) / 24_000.0
        val scanCoordinate = (worldY * 32.0 - gameTime * 1350.0) / 16.3362818
        val scanline = singleScanLine(scanCoordinate)
        val fineLines = 0.5 + 0.5 * sin(worldY * 138.0)
        val signalRow = floor(worldY * 12.0)
        val rowActive = step(0.36, randomNoise(signalRow, sequenceId * 1.91 + pulseIndex))
        val staticNoise = randomNoise(
            floor(worldY * 12.0) + sequenceId + pulseIndex,
            sequenceId * 0.37,
        ) * 2.0 - 1.0
        val signalBreak = glitchWindow * rowActive

        return ShadowHologramFallbackSample(
            horizontalOffset = (glitchWindow * shiftDirection * 0.042).toFloat(),
            brightness = (1.0 + scanline * 0.34 + fineLines * 0.025 + staticNoise * signalBreak * 0.15)
                .coerceIn(0.62, 1.42)
                .toFloat(),
            alpha = (0.60 + scanline * 0.16 + fineLines * 0.025 - abs(staticNoise) * signalBreak * 0.08)
                .coerceIn(0.38, 0.82)
                .toFloat(),
        )
    }

    private fun singleScanLine(coordinate: Double): Double {
        val cycle = floor(coordinate)
        val phase = fract(coordinate)
        val center = 0.5 + (randomNoise(cycle, 7.13) * 2.0 - 1.0) * 0.022
        var distance = abs(phase - center)
        distance = minOf(distance, 1.0 - distance)
        val normalized = distance / 0.085
        return exp(-normalized * normalized)
    }

    private fun randomNoise(x: Double, y: Double): Double =
        fract(sin(x * 12.9898 + y * 78.233) * 43758.5453)

    private fun step(edge: Double, value: Double): Double = if (value < edge) 0.0 else 1.0

    private fun fract(value: Double): Double = value - floor(value)

    private fun positiveMod(value: Double, divisor: Double): Double = value - divisor * floor(value / divisor)
}
